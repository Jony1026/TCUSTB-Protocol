package net.tdteam.ustb.academic.application;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.tdteam.ustb.academic.model.ClassDirectoryResponse;
import net.tdteam.ustb.academic.model.ClassScheduleResponse;
import net.tdteam.ustb.auth.application.IdentityService;
import net.tdteam.ustb.auth.infrastructure.SchoolSsoClient;
import net.tdteam.ustb.common.error.ProtocolException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 校验班级目录并从学校原生班级课表中提取指定班级的课程。
 * @author itsjony01
 * @date 2026-10-01
 */
@Service
public class ClassScheduleService {
    private static final Pattern SEMESTER = Pattern.compile("(20[0-9]{2})-(20[0-9]{2})-([12])");
    private static final Pattern WEEK = Pattern.compile("^(.*?)\\s*[（(]([^()（）]*周)[）)]\\s*$");
    private static final String[] PERIODS = {"1-2节", "3-4节", "5-6节", "7-8节", "9-10节", "11-13节"};
    private static final long OPTIONS_TTL_NANOS = java.time.Duration.ofMinutes(1).toNanos();
    private static final int MAX_CACHED_OPTIONS = 24;
    private final IdentityService identity;
    private final ClassDirectoryService directory;
    private final LongSupplier nanoTime;
    private final Map<SchoolSsoClient, CachedPage> optionsPages = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<PageKey, CompletableFuture<Document>> inFlightPages = new ConcurrentHashMap<>();

    @Autowired
    public ClassScheduleService(IdentityService identity, ClassDirectoryService directory) {
        this(identity, directory, System::nanoTime);
    }

    ClassScheduleService(IdentityService identity, ClassDirectoryService directory, LongSupplier nanoTime) {
        this.identity = identity;
        this.directory = directory;
        this.nanoTime = nanoTime;
    }


    //学期从学校获取
    public List<Option> semesters(String token) {
        int oldest = directory.classes(token).grades().stream().mapToInt(ClassDirectoryResponse.GradeGroup::grade).min().orElse(2026);
        SchoolSsoClient school = identity.schoolSessionOrShared(token);
        Document page = cachedOptions(school);
        List<Option> result = new ArrayList<>();
        for (Element option : page.select("select[name=xnxqh] option, select#xnxqh option")) {
            if (SEMESTER.matcher(option.val()).matches() && Integer.parseInt(option.val().substring(0, 4)) >= oldest) {
                result.add(new Option(option.val(), option.text(), option.hasAttr("selected")));
            }
        }
        if (result.isEmpty()) {
            synchronized (optionsPages) {
                optionsPages.remove(school);
            }
            throw formatError();
        }
        return result;
    }

    /**
     * 名称必须来自当前服务端目录，避免任意学校查询及跨年级误匹配。
     * @author itsjony01
     * @date 2026-10-01
     */
    public ClassScheduleResponse schedule(String token, String className, String semester) {
        ClassDirectoryResponse snapshot = directory.classes(token);

        ClassDirectoryResponse.GradeGroup group = snapshot.grades().stream()
                .filter(grade -> grade.names().contains(className)).findFirst()
                .orElseThrow(() -> new ProtocolException(HttpStatus.BAD_REQUEST, "CLASS_NOT_FOUND", "Unknown class"));
        Matcher match = SEMESTER.matcher(semester == null ? "" : semester);
        if (!match.matches() || Integer.parseInt(match.group(2)) != Integer.parseInt(match.group(1)) + 1
                || Integer.parseInt(match.group(1)) < group.grade()
                || Integer.parseInt(match.group(1)) > snapshot.year()) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "INVALID_SEMESTER", "Invalid semester");
        }
        SchoolSsoClient school = identity.schoolSessionOrShared(token);
        PageKey key = new PageKey(school, group.grade(), semester, className);
        Document page = inFlightPage(key, () -> school.classSchedulePage(group.grade(), semester, className));
        return parse(page, className);
    }

    /**
     * 学期选项短期复用，课表不复用；同一课表下载中的并发请求才共享结果。
     * @author itsjony01
     * @date 2026-10-01
     */
    private Document cachedOptions(SchoolSsoClient school) {
        CachedPage entry;
        boolean owner = false;
        synchronized (optionsPages) {
            long now = nanoTime.getAsLong();
            optionsPages.entrySet().removeIf(item -> now - item.getValue().expiresAtNanos() >= 0);
            entry = optionsPages.get(school);
            if (entry == null) {
                entry = new CachedPage(now + OPTIONS_TTL_NANOS, new CompletableFuture<>());
                optionsPages.put(school, entry);
                owner = true;
                if (optionsPages.size() > MAX_CACHED_OPTIONS) {
                    Iterator<SchoolSsoClient> oldest = optionsPages.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                }
            }
        }
        if (owner) {
            try {
                entry.result().complete(school.classScheduleOptionsPage());
            } catch (RuntimeException exception) {
                entry.result().completeExceptionally(exception);
                synchronized (optionsPages) {
                    optionsPages.remove(school, entry);
                }
            }
        }
        return await(entry.result());
    }

    private Document inFlightPage(PageKey key, Supplier<Document> loader) {
        CompletableFuture<Document> pending = new CompletableFuture<>();
        CompletableFuture<Document> existing = inFlightPages.putIfAbsent(key, pending);
        if (existing != null) return await(existing);
        try {
            pending.complete(loader.get());
            return await(pending);
        } catch (RuntimeException exception) {
            pending.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlightPages.remove(key, pending);
        }
    }

    private static Document await(CompletableFuture<Document> result) {
        try {
            return result.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            throw exception;
        }
    }

    private record PageKey(SchoolSsoClient school, int grade, String semester, String className) {
    }

    private record CachedPage(long expiresAtNanos, CompletableFuture<Document> result) {
    }

   //格子分配
    static ClassScheduleResponse parse(Document page, String className) {
        Element label = page.select("td[align=center] > nobr").stream()
                .filter(cell -> className.equals(cell.text().trim())).findFirst()
                .orElseThrow(() -> new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_CLASS_NOT_FOUND",
                        "School did not return the requested class"));
        Element row = label.closest("tr");
        List<Element> cells = row.children().stream().filter(cell -> cell.normalName().equals("td")).toList();
        if (cells.size() != 43 || cells.get(0) != label.parent()) throw formatError();
        Element table = row.closest("table");
        List<Element> headers = table.select("th[colspan=6]");
        if (headers.size() != 7 || !headers.get(0).text().contains("星期一")
                || !headers.get(6).text().contains("星期日")) throw formatError();
        List<ClassScheduleResponse.Day> days = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            List<List<ClassScheduleResponse.Course>> slots = new ArrayList<>();
            for (int slot = 0; slot < 6; slot++) {
                slots.add(parseCourses(cells.get(1 + day * 6 + slot), className, PERIODS[slot]));
            }
            days.add(new ClassScheduleResponse.Day(List.copyOf(slots)));
        }
        return new ClassScheduleResponse(className, List.copyOf(days));
    }

    /**
     * 学校课程块有三行和四行两种：三行时班级名紧跟课程名，四行时独占第二行。
     * @author itsjony01
     * @date 2026-10-01
     */
    private static List<ClassScheduleResponse.Course> parseCourses(Element cell, String className, String teachNo) {
        List<ClassScheduleResponse.Course> courses = new ArrayList<>();
        var records = cell.select("div.kbcontent1");
        //return null;
        if (records.isEmpty() && !cell.text().replace('\u00a0', ' ').isBlank()) throw formatError();
        for (Element record : records) {
            List<String> lines = java.util.Arrays.stream(record.html().split("(?i)<br\\s*/?>"))
                    .map(fragment -> org.jsoup.Jsoup.parse(fragment).text().trim())
                    .filter(line -> !line.isBlank()).toList();
            if (lines.size() < 2) throw formatError();
            int teacherIndex = lines.size() > 2 && lines.get(1).contains(className) ? 2 : 1;
            String name = lines.get(0);
            if (teacherIndex == 1 && name.endsWith(className)) name = name.substring(0, name.length() - className.length()).trim();
            Matcher week = WEEK.matcher(lines.get(teacherIndex));
            if (name.isBlank() || !week.matches()) throw formatError();
            String place = teacherIndex + 1 < lines.size() ? String.join(" ", lines.subList(teacherIndex + 1, lines.size())) : "";
            courses.add(new ClassScheduleResponse.Course(name, place, week.group(1).trim(),
                    teacherIndex == 2 ? lines.get(1) : className, week.group(2), teachNo, null, null));
        }
        //没招了
        return List.copyOf(courses);
    }

    private static ProtocolException formatError() {
        return new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_SCHEDULE_FORMAT_CHANGED",
                "School timetable format could not be parsed");
    }

    public record Option(String value, String name, boolean checked) {
    }
}
