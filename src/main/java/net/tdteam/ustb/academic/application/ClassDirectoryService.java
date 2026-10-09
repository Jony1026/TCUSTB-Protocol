package net.tdteam.ustb.academic.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import net.tdteam.ustb.academic.model.ClassDirectoryResponse;
import net.tdteam.ustb.auth.application.IdentityService;
import net.tdteam.ustb.auth.infrastructure.SchoolSsoClient;
import net.tdteam.ustb.common.error.ProtocolException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 从服务端 JSON 读取班级目录；仅首次缺失时从学校课表生成快照。
 * @author itsjony01
 * @date 2026-10-01
 */
@Service
public class ClassDirectoryService {
    private final IdentityService identity;
    private final Clock clock;
    private final Path snapshotPath;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public ClassDirectoryService(IdentityService identity) {
        this(identity, Clock.system(ZoneId.of("Asia/Shanghai")), Path.of("data/class-directory.json"));
    }

    ClassDirectoryService(IdentityService identity, Clock clock, Path snapshotPath) {
        this.identity = identity;
        this.clock = clock;
        this.snapshotPath = snapshotPath.toAbsolutePath().normalize();
    }

    /**
     * 每次读取文件，替换 JSON 后无需重启；仍需先验证登录令牌。
     * @author itsjony01
     * @date 2026-10-01
     */
    public ClassDirectoryResponse classes(String accessToken) {
        if (Files.exists(snapshotPath)) return readSnapshot();
        synchronized (this) {
            if (Files.exists(snapshotPath)) return readSnapshot();
            var school = identity.schoolSessionOrShared(accessToken);
            ClassDirectoryResponse response = fetchSchool(school);
            writeSnapshot(response);
            return response;
        }
    }

    /**
     * 仅从已验证的教务会话按在校学年采集班级，合并其入学学期记录。
     * @author itsjony01
     * @date 2026-10-01
     */
    private ClassDirectoryResponse fetchSchool(SchoolSsoClient school) {
        int year = academicYear(clock);
        List<ClassDirectoryResponse.GradeGroup> grades = new ArrayList<>();
        for (int grade = year; grade >= year - 3; grade--) {
            LinkedHashSet<String> names = new LinkedHashSet<>(parseClasses(school.classDirectoryPage(grade), grade));
            if (grade < year) {
                String firstSemester = grade + "-" + (grade + 1) + "-1";
                names.addAll(parseClasses(school.classDirectoryPage(grade, firstSemester), grade));
            }
            grades.add(new ClassDirectoryResponse.GradeGroup(grade, List.copyOf(names)));
        }
        if (grades.stream().allMatch(group -> group.names().isEmpty())) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_CLASS_DIRECTORY_UNAVAILABLE",
                    "School returned no class directory data");
        }
        return new ClassDirectoryResponse(year, grades);
    }

    /**
     * 读取缓存快照
     * @author itsjony01
     * @date 2026-10-01
     */
    private ClassDirectoryResponse readSnapshot() {
        try {
            ClassDirectoryResponse response = mapper.readValue(snapshotPath.toFile(), ClassDirectoryResponse.class);
            if (response == null || response.year() < 2000 || response.grades() == null
                    || response.grades().isEmpty() || response.grades().stream().allMatch(group ->
                    group == null || group.names() == null || group.names().isEmpty())
                    || response.grades().stream().anyMatch(group ->
                    group == null || group.names() == null || group.names().stream().anyMatch(name -> name == null))) {
                throw new IOException("Invalid class directory snapshot");
            }
            return response;
        } catch (IOException exception) {
            throw new ProtocolException(HttpStatus.SERVICE_UNAVAILABLE, "CLASS_DIRECTORY_STORAGE_FAILED",
                    "Class directory snapshot is unavailable");
        }
    }

    /**
     * 一个课表的缓存，避免每次查询把教务挤爆
     * @author itsjony01
     * @date 2026-10-01
     */
    private void writeSnapshot(ClassDirectoryResponse response) {
        Path temp = null;
        try {
            Files.createDirectories(snapshotPath.getParent());
            temp = Files.createTempFile(snapshotPath.getParent(), "class-directory-", ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), response);
            try {
                Files.move(temp, snapshotPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, snapshotPath);
            }
        } catch (IOException exception) {
            throw new ProtocolException(HttpStatus.SERVICE_UNAVAILABLE, "CLASS_DIRECTORY_STORAGE_FAILED",
                    "Class directory snapshot could not be saved");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 保存失败时临时文件清理不应覆盖原始错误。
                }
            }
        }
    }

    /**
     * 春季学期仍属上一学年，不能在一月提前移除即将毕业的年级。
     * @author itsjony01
     * @date 2026-10-01
     */
    static int academicYear(Clock clock) {
        LocalDate today = LocalDate.now(clock);
        return today.getMonthValue() >= 9 ? today.getYear() : today.getYear() - 1;
    }

    /**
     * 仅从班级表头提取指定入学年级，去重且不把课程内容当成班级。
     * @author itsjony01
     * @date 2026-10-01
     */
    static List<String> parseClasses(Document page, int grade) {
        String shortYear = String.format("%02d", grade % 100);
        Pattern cohort = Pattern.compile("^.{1,50}(?<![0-9])(?:" + grade + "|" + shortYear
                + ")(?:[0-9]{2}|级[0-9]{1,2}班?)$");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Element cell : page.select("td[align=center] > nobr")) {
            String name = cell.text().replace('\u00a0', ' ').trim();
            if (cohort.matcher(name).matches()) names.add(name);
        }
        return List.copyOf(names);
    }
}
