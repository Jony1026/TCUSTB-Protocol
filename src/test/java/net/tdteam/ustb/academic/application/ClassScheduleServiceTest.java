package net.tdteam.ustb.academic.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.tdteam.ustb.academic.model.ClassDirectoryResponse;
import net.tdteam.ustb.auth.application.IdentityService;
import net.tdteam.ustb.auth.infrastructure.SchoolSsoClient;
import net.tdteam.ustb.common.error.ProtocolException;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/**
 * 验证班级边界、学期限制和学校表格解析。
 * @author itsjony01
 * @date 2026-10-01
 */
class ClassScheduleServiceTest {
    @Test
    void parsesDayMajorColumnsAndKeepsOverlappingRecords() {
        StringBuilder html = new StringBuilder("<table>");
        html.append("<tr>");
        for (String day : List.of("一", "二", "三", "四", "五", "六", "日")) {
            html.append("<th colspan='6'>星期").append(day).append("</th>");
        }
        html.append("</tr><tr><td align='center'><nobr>计科2301</nobr></td>");
        for (int index = 0; index < 42; index++) {
            html.append(index == 7 ? "<td><nobr><div class='kbcontent1'>高等数学计科2301<br>测试教师 (1-8周)<br>教学楼101</div>"
                    + "<div class='kbcontent1'>高等数学<br>计科2301<br>测试教师 (9-16周)<br>教学楼101</div></nobr></td>"
                    : "<td><nobr>&nbsp;</nobr></td>");
        }
        html.append("</tr>");
        html.append("<tr><td align='center'><nobr>计科2302</nobr></td></tr></table>");
        var result = ClassScheduleService.parse(Jsoup.parse(html.toString()), "计科2301");
        assertEquals(7, result.course().size());
        assertEquals(6, result.course().get(0).items().size());
        assertEquals(0, result.course().get(0).items().get(0).size());
        var courses = result.course().get(1).items().get(1);
        assertEquals(2, courses.size());
        assertEquals("高等数学", courses.get(0).courseName());
        assertEquals("高等数学", courses.get(1).courseName());
        assertEquals("测试教师", courses.get(0).teacher());
        assertEquals("1-8周", courses.get(0).teachWeek());
        assertEquals("教学楼101", courses.get(0).place());
        assertEquals("3-4节", courses.get(0).teachNo());
    }

    @Test
    void refusesUnexpectedTableAndWrongClass() {
        var page = Jsoup.parse("<table><tr><td align='center'><nobr>计科2301</nobr></td><td>高等数学</td></tr></table>");
        assertEquals("SCHOOL_SCHEDULE_FORMAT_CHANGED",
                assertThrows(ProtocolException.class, () -> ClassScheduleService.parse(page, "计科2301")).code());
        assertEquals("SCHOOL_CLASS_NOT_FOUND",
                assertThrows(ProtocolException.class, () -> ClassScheduleService.parse(page, "计科2302")).code());
    }

    @Test
    void rejectsUnknownClassAndInvalidSemesterBeforeSchoolRequest() {
        IdentityService identity = mock(IdentityService.class);
        ClassDirectoryService directory = mock(ClassDirectoryService.class);
        when(directory.classes("token")).thenReturn(new ClassDirectoryResponse(2026,
                List.of(new ClassDirectoryResponse.GradeGroup(2023, List.of("计科2301")))));
        var service = new ClassScheduleService(identity, directory);
        assertEquals("CLASS_NOT_FOUND",
                assertThrows(ProtocolException.class, () -> service.schedule("token", "计科2302", "2026-2027-1")).code());
        assertEquals("INVALID_SEMESTER",
                assertThrows(ProtocolException.class, () -> service.schedule("token", "计科2301", "2027-2028-1")).code());
        verifyNoInteractions(identity);
    }

    /** 学期以学校实际选项为准，学年第二学期也可以正常选择。@author itsjony01 @date 2026-10-01 */
    @Test
    void readsRealSchoolOptionsAndRequiresAuthentication() {
        IdentityService identity = mock(IdentityService.class);
        ClassDirectoryService directory = mock(ClassDirectoryService.class);
        SchoolSsoClient school = mock(SchoolSsoClient.class);
        when(directory.classes("token")).thenReturn(new ClassDirectoryResponse(2026,
                List.of(new ClassDirectoryResponse.GradeGroup(2023, List.of("计科2301")))));
        when(identity.schoolSession("token")).thenReturn(school);
        when(school.classScheduleOptionsPage()).thenReturn(Jsoup.parse("<select name='xnxqh'>"
                + "<option value='2026-2027-2' selected>春季</option><option value='2026-2027-1'>秋季</option>"
                + "<option value='2022-2023-1'>旧学期</option></select>"));
        var service = new ClassScheduleService(identity, directory);
        var options = service.semesters("token");
        assertEquals(2, options.size());
        assertEquals("2026-2027-2", options.get(0).value());
        assertEquals(true, options.get(0).checked());
        when(directory.classes("expired")).thenThrow(new ProtocolException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Expired"));
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED,
                assertThrows(ProtocolException.class, () -> service.schedule("expired", "计科2301", "2026-2027-1")).status());
    }

    /** 每次查询课表都读取学校，学期选项短缓存且跨会话隔离。@author itsjony01 @date 2026-10-01 */
    @Test
    void refreshesSchedulesAndCachesOptionsOnlyPerSession() {
        IdentityService identity = mock(IdentityService.class);
        ClassDirectoryService directory = mock(ClassDirectoryService.class);
        SchoolSsoClient first = mock(SchoolSsoClient.class);
        SchoolSsoClient second = mock(SchoolSsoClient.class);
        AtomicLong clock = new AtomicLong(1);
        var snapshot = new ClassDirectoryResponse(2026,
                List.of(new ClassDirectoryResponse.GradeGroup(2023, List.of("计科2301", "计科2302"))));
        when(directory.classes("first")).thenReturn(snapshot);
        when(directory.classes("second")).thenReturn(snapshot);
        when(identity.schoolSession("first")).thenReturn(first);
        when(identity.schoolSession("second")).thenReturn(second);
        when(first.classSchedulePage(2023, "2026-2027-1", "计科2301"))
                .thenReturn(schedulePage("高等数学"), schedulePage("线性代数"), schedulePage("线性代数"));
        when(first.classSchedulePage(2023, "2026-2027-1", "计科2302")).thenReturn(schedulePage());
        when(second.classSchedulePage(2023, "2026-2027-1", "计科2301")).thenReturn(schedulePage());
        var options = Jsoup.parse("<select name='xnxqh'><option value='2026-2027-1'>秋季</option></select>");
        when(first.classScheduleOptionsPage()).thenReturn(options);
        when(second.classScheduleOptionsPage()).thenReturn(options);
        var service = new ClassScheduleService(identity, directory, clock::get);

        assertEquals("高等数学", service.schedule("first", "计科2301", "2026-2027-1")
                .course().get(0).items().get(0).get(0).courseName());
        assertEquals("线性代数", service.schedule("first", "计科2301", "2026-2027-1")
                .course().get(0).items().get(0).get(0).courseName());
        assertEquals("计科2302", service.schedule("first", "计科2302", "2026-2027-1").className());
        verify(first, times(2)).classSchedulePage(2023, "2026-2027-1", "计科2301");
        verify(first).classSchedulePage(2023, "2026-2027-1", "计科2302");
        service.schedule("second", "计科2301", "2026-2027-1");
        verify(second).classSchedulePage(2023, "2026-2027-1", "计科2301");

        service.semesters("first");
        service.semesters("first");
        service.semesters("second");
        verify(first).classScheduleOptionsPage();
        verify(second).classScheduleOptionsPage();
        clock.addAndGet(Duration.ofMinutes(2).toNanos());
        service.semesters("first");
        verify(first, times(2)).classScheduleOptionsPage();
        verify(directory, times(6)).classes("first");
    }

    /** 学校失败不能污染缓存，并发同键只发送一次请求。@author itsjony01 @date 2026-10-01 */
    @Test
    void retriesFailuresAndCoalescesConcurrentRequests() throws Exception {
        IdentityService identity = mock(IdentityService.class);
        ClassDirectoryService directory = mock(ClassDirectoryService.class);
        SchoolSsoClient school = mock(SchoolSsoClient.class);
        when(directory.classes("token")).thenReturn(new ClassDirectoryResponse(2026,
                List.of(new ClassDirectoryResponse.GradeGroup(2023, List.of("计科2301")))));
        when(identity.schoolSession("token")).thenReturn(school);
        when(school.classSchedulePage(2023, "2026-2027-1", "计科2301"))
                .thenThrow(new ProtocolException(org.springframework.http.HttpStatus.BAD_GATEWAY, "School unavailable"))
                .thenReturn(schedulePage());
        var service = new ClassScheduleService(identity, directory);
        assertThrows(ProtocolException.class, () -> service.schedule("token", "计科2301", "2026-2027-1"));
        service.schedule("token", "计科2301", "2026-2027-1");
        verify(school, times(2)).classSchedulePage(2023, "2026-2027-1", "计科2301");

        var otherSemester = "2025-2026-1";
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(school.classSchedulePage(2023, otherSemester, "计科2301")).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return schedulePage();
        });
        CompletableFuture<?> first = CompletableFuture.runAsync(() -> service.schedule("token", "计科2301", otherSemester));
        try {
            assertEquals(true, started.await(5, TimeUnit.SECONDS));
            CompletableFuture<?> second = CompletableFuture.runAsync(() -> service.schedule("token", "计科2301", otherSemester));
            release.countDown();
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
            verify(school).classSchedulePage(2023, otherSemester, "计科2301");
        } finally {
            release.countDown();
        }
    }

    private static org.jsoup.nodes.Document schedulePage() {
        return schedulePage(null);
    }

    private static org.jsoup.nodes.Document schedulePage(String lesson) {
        StringBuilder html = new StringBuilder("<table><tr>");
        for (String day : List.of("一", "二", "三", "四", "五", "六", "日")) {
            html.append("<th colspan='6'>星期").append(day).append("</th>");
        }
        html.append("</tr>");
        for (String name : List.of("计科2301", "计科2302")) {
            html.append("<tr><td align='center'><nobr>").append(name).append("</nobr></td>");
            for (int index = 0; index < 42; index++) {
                if (index == 0 && lesson != null && name.equals("计科2301")) {
                    html.append("<td><nobr><div class='kbcontent1'>").append(lesson).append(name)
                            .append("<br>测试教师 (1-8周)<br>教学楼101</div></nobr></td>");
                } else html.append("<td><nobr>&nbsp;</nobr></td>");
            }
            html.append("</tr>");
        }
        return Jsoup.parse(html.append("</table>").toString());
    }
}
