package net.tdteam.ustb.academic.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.tdteam.ustb.academic.model.TeacherScheduleResponse;
import net.tdteam.ustb.auth.application.IdentityService;
import net.tdteam.ustb.auth.infrastructure.SchoolSsoClient;
import net.tdteam.ustb.common.error.ProtocolException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 从服务端 JSON 读取教师目录，缺失时采集；教师课表始终实时查询学校。@author itsjony01 @date 2026-10-01 */
@Service
public class TeacherScheduleService {
    private static final Pattern SEMESTER = Pattern.compile("20[0-9]{2}-20[0-9]{2}-[12]");
    private static final Pattern WEEK = Pattern.compile("^(.*?)\\s*[（(]([^()（）]*周)[）)]\\s*$");
    private static final String[] PERIODS = {"1-2节", "3-4节", "5-6节", "7-8节", "9-10节", "11-13节"};
    private static final String[] STARTS = {"08:00", "09:55", "13:10", "15:00", "16:50", "19:10"};
    private static final String[] ENDS = {"09:35", "11:30", "14:45", "16:35", "18:25", "21:35"};
    private static final long OPTIONS_TTL = Duration.ofMinutes(1).toNanos();
    private final IdentityService identity;
    private final Path directoryPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<SchoolSsoClient, OptionsEntry> optionsCache = new ConcurrentHashMap<>();

    @Autowired
    public TeacherScheduleService(IdentityService identity) {
        this(identity, Path.of("data/teacher-directory"));
    }

    TeacherScheduleService(IdentityService identity, Path directoryPath) {
        this.identity = identity;
        this.directoryPath = directoryPath.toAbsolutePath().normalize();
    }

    /** 学期和节次模式必须从学校当前教师筛选表单读取。@author itsjony01 @date 2026-10-01 */
    public Options options(String token) {
        return schoolOptions(identity.schoolSessionOrShared(token));
    }

    /**
     * 已登录用户读取服务端名单；指定院系时读取对应快照，未指定时汇总全部院系。
     * @author itsjony01
     * @date 2026-10-01
     */
    public Directory directory(String token, String semester, String college) {
        SchoolSsoClient school = identity.schoolSessionOrShared(token);
        if (semester == null || !SEMESTER.matcher(semester).matches()) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "INVALID_SEMESTER", "Invalid school semester");
        }
        if (college != null && college.length() > 120) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "INVALID_COLLEGE", "Unknown school college");
        }
        if (college == null || college.isBlank()) return allDirectories(school, semester);
        return collegeDirectory(school, semester, college, null);
    }

    /**
     * 不向学校发送空院系的全校课表请求；按院系读取或补齐快照后再去重汇总。
     * @author itsjony01
     * @date 2026-10-01
     */
    private Directory allDirectories(SchoolSsoClient school, String semester) {
        Options options = schoolOptions(school);
        validateSemester(options, semester);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Option college : options.college()) {
            try {
                names.addAll(collegeDirectory(school, semester, college.value(), options).names());
            } catch (ProtocolException exception) {
                // 个别院系没有本学期课表时仍应保留其他院系的可搜索教师。
                if (!"SCHOOL_TEACHER_DIRECTORY_UNAVAILABLE".equals(exception.code())) throw exception;
            }
        }
        if (names.isEmpty()) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_TEACHER_DIRECTORY_UNAVAILABLE",
                    "School returned no teacher names for this semester");
        }
        return new Directory(semester, "", List.copyOf(names));
    }

    /**
     * 单个院系只在 JSON 缺失时采集，避免重复向学校请求相同教师目录。
     * @author itsjony01
     * @date 2026-10-01
     */
    private Directory collegeDirectory(SchoolSsoClient school, String semester, String college, Options suppliedOptions) {
        Path snapshot = directoryPath.resolve(semester).resolve(
                Base64.getUrlEncoder().withoutPadding().encodeToString(college.getBytes(StandardCharsets.UTF_8)) + ".json");
        if (Files.exists(snapshot)) return readDirectory(snapshot, semester, college);
        synchronized (this) {
            if (Files.exists(snapshot)) return readDirectory(snapshot, semester, college);
            Options options = suppliedOptions == null ? schoolOptions(school) : suppliedOptions;
            String timeModel = validateSemester(options, semester);
            if (options.college().stream().noneMatch(option -> option.value().equals(college))) {
                throw new ProtocolException(HttpStatus.BAD_REQUEST, "INVALID_COLLEGE", "Unknown school college");
            }
            Document page = school.teacherSchedulePage(semester, timeModel, "", college);
            List<String> names = List.copyOf(new LinkedHashSet<>(teacherRows(page).stream()
                    .map(row -> row.children().get(0).text().trim()).toList()));
            if (names.isEmpty()) {
                throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_TEACHER_DIRECTORY_UNAVAILABLE",
                        "School returned no teacher names for this college and semester");
            }
            if (names.stream().anyMatch(name -> name.isBlank() || name.length() > 80)) throw formatError();
            Directory result = new Directory(semester, college, names);
            writeDirectory(snapshot, result);
            return result;
        }
    }

    /** 快照损坏时显式报错，不覆盖用户维护的 JSON。@author itsjony01 @date 2026-10-01 */
    private Directory readDirectory(Path snapshot, String semester, String college) {
        try {
            Directory directory = mapper.readValue(snapshot.toFile(), Directory.class);
            if (directory == null || !semester.equals(directory.semester()) || !college.equals(directory.college())
                    || directory.names() == null || directory.names().isEmpty()
                    || directory.names().stream().anyMatch(name -> name == null || name.isBlank() || name.length() > 80)) {
                throw new IOException("Invalid teacher directory snapshot");
            }
            return directory;
        } catch (IOException exception) {
            throw new ProtocolException(HttpStatus.SERVICE_UNAVAILABLE, "TEACHER_DIRECTORY_STORAGE_FAILED",
                    "Teacher directory snapshot is unavailable");
        }
    }

    /** 在同一目录先写临时文件，再原子替换目标，避免返回半截教师名单。@author itsjony01 @date 2026-10-01 */
    private void writeDirectory(Path snapshot, Directory directory) {
        Path temp = null;
        try {
            Files.createDirectories(snapshot.getParent());
            temp = Files.createTempFile(snapshot.getParent(), "teachers-", ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), directory);
            try {
                Files.move(temp, snapshot, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, snapshot);
            }
        } catch (IOException exception) {
            throw new ProtocolException(HttpStatus.SERVICE_UNAVAILABLE, "TEACHER_DIRECTORY_STORAGE_FAILED",
                    "Teacher directory snapshot could not be saved");
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {
                    // 临时文件清理失败不覆盖原始存储异常。
                }
            }
        }
    }

    /** 精确核对教师表头；学校按姓名模糊搜索不能直接等同精确课表。@author itsjony01 @date 2026-10-01 */
    public TeacherScheduleResponse schedule(String token, String teacherName, String semester) {
        SchoolSsoClient school = identity.schoolSessionOrShared(token);
        if (teacherName == null || teacherName.isBlank() || teacherName.length() > 40
                || !teacherName.matches("[\\p{L}·•\\s]{1,40}")) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "INVALID_TEACHER", "Invalid teacher name");
        }
        Options options = schoolOptions(school);
        String timeModel = validateSemester(options, semester);
        Document page = school.teacherSchedulePage(semester, timeModel, teacherName.trim());
        return parse(page, teacherName.trim());
    }

    /** 学校筛选项短期复用，避免名单与单人查询前重复加载筛选页。@author itsjony01 @date 2026-10-01 */
    private Options schoolOptions(SchoolSsoClient school) {
        OptionsEntry cached = optionsCache.get(school);
        if (cached != null && cached.expiresAt() - System.nanoTime() > 0) return cached.options();
        Options loaded = parseOptions(school.teacherScheduleOptionsPage());
        if (optionsCache.size() > 100) optionsCache.entrySet().removeIf(entry -> entry.getValue().expiresAt() - System.nanoTime() <= 0);
        if (optionsCache.size() > 100) optionsCache.clear();
        optionsCache.put(school, new OptionsEntry(System.nanoTime() + OPTIONS_TTL, loaded));
        return loaded;
    }

    static Options parseOptions(Document page) {
        List<Option> semesters = readOptions(page, "xnxqh").stream()
                .filter(option -> SEMESTER.matcher(option.value()).matches()).toList();
        List<Option> modes = readOptions(page, "kbjcmsid");
        List<Option> colleges = readOptions(page, "skyx").stream()
                .filter(option -> !option.name().matches(".*(全部|所有|不限|请选择).*"))
                .toList();
        if (semesters.isEmpty() || modes.isEmpty() || colleges.isEmpty()) throw formatError();
        return new Options(semesters, modes, colleges);
    }

    private static List<Option> readOptions(Document page, String name) {
        return page.select("select[name=" + name + "] option, select#" + name + " option").stream()
                .filter(option -> !option.val().isBlank())
                .map(option -> new Option(option.val(), option.text().trim(), option.hasAttr("selected"))).distinct().toList();
    }

    private static String validateSemester(Options options, String semester) {
        if (semester == null || options.semester().stream().noneMatch(option -> option.value().equals(semester))) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "INVALID_SEMESTER", "Unknown school semester");
        }
        return options.timeModel().stream().filter(Option::checked).findFirst()
                .orElse(options.timeModel().get(0)).value();
    }

    static List<Element> teacherRows(Document page) {
        Element table = page.select("table").stream().filter(candidate -> {
            List<Element> headers = candidate.select("th[colspan=6]");
            return headers.size() == 7 && headers.get(0).text().contains("星期一")
                    && headers.get(6).text().contains("星期日");
        }).findFirst().orElseThrow(TeacherScheduleService::formatError);
        List<Element> rows = table.select("td[align=center] > nobr").stream()
                .filter(label -> label.closest("table") == table)
                .map(label -> label.closest("tr")).toList();
        for (Element row : rows) {
            List<Element> cells = row.children().stream().filter(cell -> cell.normalName().equals("td")).toList();
            if (cells.size() != 43 || cells.get(0).selectFirst("nobr") == null
                    || cells.get(0).text().isBlank()) throw formatError();
        }
        return rows;
    }

    static TeacherScheduleResponse parse(Document page, String teacherName) {
        List<Element> matches = teacherRows(page).stream()
                .filter(row -> row.children().get(0).text().trim().equals(teacherName)).toList();
        if (matches.isEmpty()) throw new ProtocolException(HttpStatus.NOT_FOUND, "TEACHER_NOT_FOUND", "Teacher not found");
        List<TeacherScheduleResponse.Day> days = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            List<List<TeacherScheduleResponse.Course>> slots = new ArrayList<>();
            for (int slot = 0; slot < 6; slot++) {
                LinkedHashSet<TeacherScheduleResponse.Course> courses = new LinkedHashSet<>();
                for (Element match : matches) {
                    List<Element> cells = match.children();
                    courses.addAll(parseCourses(cells.get(1 + day * 6 + slot), teacherName, slot));
                }
                slots.add(List.copyOf(courses));
            }
            days.add(new TeacherScheduleResponse.Day(List.copyOf(slots)));
        }
        return new TeacherScheduleResponse(teacherName, List.copyOf(days));
    }

    private static List<TeacherScheduleResponse.Course> parseCourses(Element cell, String teacherName, int slot) {
        List<Element> records = cell.select("div.kbcontent1");
        if (records.isEmpty() && !cell.text().replace('\u00a0', ' ').isBlank()) throw formatError();
        List<TeacherScheduleResponse.Course> courses = new ArrayList<>();
        for (Element record : records) {
            List<String> lines = Arrays.stream(record.html().split("(?i)<br\\s*/?>"))
                    .map(fragment -> Jsoup.parse(fragment).text().trim()).filter(line -> !line.isBlank()).toList();
            if (lines.size() < 2 || lines.get(0).isBlank()) throw formatError();
            int weekIndex = -1;
            Matcher week = null;
            for (int i = 1; i < lines.size(); i++) {
                Matcher candidate = WEEK.matcher(lines.get(i));
                if (candidate.matches()) { weekIndex = i; week = candidate; break; }
            }
            if (weekIndex < 0 || week == null) throw formatError();
            String className = weekIndex > 1 ? String.join(" ", lines.subList(1, weekIndex)) : "";
            String place = weekIndex + 1 < lines.size() ? String.join(" ", lines.subList(weekIndex + 1, lines.size())) : "";
            courses.add(new TeacherScheduleResponse.Course(lines.get(0), place, teacherName, className,
                    week.group(2), PERIODS[slot], STARTS[slot], ENDS[slot]));
        }
        return List.copyOf(courses);
    }

    private static ProtocolException formatError() {
        return new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_TEACHER_SCHEDULE_FORMAT_CHANGED",
                "School teacher timetable format could not be parsed");
    }

    public record Option(String value, String name, boolean checked) { }
    public record Options(List<Option> semester, List<Option> timeModel, List<Option> college) { }
    public record Directory(String semester, String college, List<String> names) { }
    private record OptionsEntry(long expiresAt, Options options) { }
}
