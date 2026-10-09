package net.tdteam.ustb.academic.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import net.tdteam.ustb.auth.application.IdentityService;
import net.tdteam.ustb.auth.infrastructure.SchoolSsoClient;
import net.tdteam.ustb.common.error.ProtocolException;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证学校课表 HTML 中的班级提取、在校年级过滤及去重。
 * @author itsjony01
 * @date 2026-10-01
 */
class ClassDirectoryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsOnlyRequestedCohortFromClassCells() {
        var page = Jsoup.parse("<table><tr><td align='center'><nobr>计科2301</nobr></td>"
                + "<td align='center'><nobr>计科2301</nobr></td>"
                + "<td align='center'><nobr>软件工程2023级1班</nobr></td>"
                + "<td align='center'><nobr>计科2201</nobr></td>"
                + "<td align='center'><nobr>班级</nobr></td>"
                + "<td align='center'><div><nobr>思想道德与法治 计科2301 教师 (16周)</nobr></div></td>"
                + "<td><nobr>其他2401</nobr></td></tr></table>");
        assertEquals(List.of("计科2301", "软件工程2023级1班"),
                ClassDirectoryService.parseClasses(page, 2023));
    }

    @Test
    void emptyResultDoesNotInventClasses() {
        assertEquals(List.of(), ClassDirectoryService.parseClasses(Jsoup.parse("<html></html>"), 2026));
    }

    /**
     * 春季仍属上一学年，秋季才切换到新生年级。
     * @author itsjony01
     * @date 2026-10-01
     */
    @Test
    void usesAcademicYearInSchoolTimezone() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        assertEquals(2025, ClassDirectoryService.academicYear(
                Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), zone)));
        assertEquals(2026, ClassDirectoryService.academicYear(
                Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), zone)));
    }

    /**
     * 汇总四个在校年级的当前及入学学期课表，班级名只保留一次。
     * @author itsjony01
     * @date 2026-10-01
     */
    @Test
    void mergesCohortsAndDeduplicatesHistoricalClasses() {
        IdentityService identity = mock(IdentityService.class);
        SchoolSsoClient school = mock(SchoolSsoClient.class);
        when(identity.schoolSession("token")).thenReturn(school);
        when(identity.schoolSession("other-token")).thenReturn(school);
        for (int grade = 2023; grade <= 2026; grade++) {
            when(school.classDirectoryPage(grade)).thenReturn(classPage("计科" + (grade % 100) + "01"));
            if (grade < 2026) {
                when(school.classDirectoryPage(grade, grade + "-" + (grade + 1) + "-1"))
                        .thenReturn(classPage("计科" + (grade % 100) + "01", "软件" + (grade % 100) + "01"));
            }
        }
        var clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        Path file = tempDir.resolve("data/classes.json");
        var service = new ClassDirectoryService(identity, clock, file);
        var result = service.classes("token");
        assertEquals(2026, result.year());
        assertEquals(List.of(2026, 2025, 2024, 2023),
                result.grades().stream().map(group -> group.grade()).toList());
        assertEquals(List.of("计科2301", "软件2301"), result.grades().get(3).names());
        verify(school).classDirectoryPage(2026);
        verify(school).classDirectoryPage(2023, "2023-2024-1");
        assertTrue(Files.isRegularFile(file));
        assertEquals(result, service.classes("other-token"));
        verify(school, times(1)).classDirectoryPage(2026);
    }

    /**
     * 快照可由管理员更新；损坏的文件不能被学校响应静默覆盖。
     * @author itsjony01
     * @date 2026-10-01
     */
    @Test
    void readsAndValidatesManuallyUpdatedSnapshot() throws IOException {
        Path file = tempDir.resolve("classes.json");
        Files.writeString(file, "{\"year\":2026,\"grades\":[{\"grade\":2026,\"names\":[\"计科2601\"]}]}");
        IdentityService identity = mock(IdentityService.class);
        when(identity.schoolSession("token")).thenReturn(mock(SchoolSsoClient.class));
        var service = new ClassDirectoryService(identity, Clock.systemUTC(), file);
        assertEquals(List.of("计科2601"), service.classes("token").grades().get(0).names());
        Files.writeString(file, "invalid JSON");
        assertEquals("CLASS_DIRECTORY_STORAGE_FAILED",
                assertThrows(ProtocolException.class, () -> service.classes("token")).code());
    }

    @Test
    void doesNotPersistEmptySchoolDirectory() {
        IdentityService identity = mock(IdentityService.class);
        SchoolSsoClient school = mock(SchoolSsoClient.class);
        when(identity.schoolSession("token")).thenReturn(school);
        for (int grade = 2023; grade <= 2026; grade++) {
            when(school.classDirectoryPage(grade)).thenReturn(Jsoup.parse("<html></html>"));
            if (grade < 2026) when(school.classDirectoryPage(grade, grade + "-" + (grade + 1) + "-1"))
                    .thenReturn(Jsoup.parse("<html></html>"));
        }
        Path file = tempDir.resolve("classes.json");
        var clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        var service = new ClassDirectoryService(identity, clock, file);
        assertEquals("SCHOOL_CLASS_DIRECTORY_UNAVAILABLE",
                assertThrows(ProtocolException.class, () -> service.classes("token")).code());
        assertTrue(Files.notExists(file));
    }

    private static org.jsoup.nodes.Document classPage(String... names) {
        StringBuilder html = new StringBuilder("<table><tr>");
        for (String name : names) html.append("<td align='center'><nobr>").append(name).append("</nobr></td>");
        return Jsoup.parse(html.append("</tr></table>").toString());
    }
}
