package net.tdteam.ustb.academic.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import net.tdteam.ustb.auth.application.IdentityService;
import net.tdteam.ustb.auth.infrastructure.SchoolSsoClient;
import net.tdteam.ustb.common.error.ProtocolException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 根据旧版协议字段构造学校页面，验证个人日期课表；这些样例不是学校实时数据。
 * @author itsjony01
 * @date 2026-10-01
 */
class PersonalScheduleServiceTest {
    @Test
    void parsesTooltipRecordsAndSkipsWeekHeader() {
        var result = PersonalScheduleService.parse(fixture("高等数学", true), "2026-10-01");
        assertEquals("2026-10-01", result.date());
        assertEquals("第10周", result.currentWeek());
        assertEquals("22周", result.endWeek());
        assertEquals("第10周/22周", result.tips());
        assertFalse(result.outsideTeachingCalendar());
        assertEquals(7, result.course().size());
        assertEquals(6, result.course().get(0).items().size());
        var courses = result.course().get(0).items().get(0);
        assertEquals(2, courses.size());
        assertEquals("高等数学", courses.get(0).courseName());
        assertEquals("高等数学", courses.get(1).courseName());
        assertEquals("01-02节", courses.get(0).teachNo());
        assertEquals("第10周", courses.get(0).teachWeek());
        assertEquals("测试教师", courses.get(0).teacher());
        assertEquals("8教401", courses.get(0).place());
        assertEquals("08:15", courses.get(0).startAt());
        assertEquals("09:50", courses.get(0).endAt());
        assertEquals("基础外语", result.course().get(6).items().get(5).get(0).courseName());
        assertTrue(result.course().get(3).items().stream().allMatch(List::isEmpty));
    }

    @Test
    void distinguishesOutsideCalendarFromMalformedPages() {
        var outside = PersonalScheduleService.parse(Jsoup.parse("<script>alert('当前日期不在教学周历内')</script>"), "2026-08-01");
        assertTrue(outside.outsideTeachingCalendar());
        assertEquals(7, outside.course().size());
        assertEquals("SCHOOL_PERSONAL_SCHEDULE_FORMAT_CHANGED", assertThrows(ProtocolException.class,
                () -> PersonalScheduleService.parse(Jsoup.parse("<html>学校正在维护</html>"), "2026-10-01")).code());
        var empty = fixture("", false);
        assertFalse(PersonalScheduleService.parse(empty, "2026-10-01").outsideTeachingCalendar());
        assertTrue(PersonalScheduleService.parse(empty, "2026-10-01").course().stream()
                .allMatch(day -> day.items().stream().allMatch(List::isEmpty)));
    }

    @Test
    void supportsAlternateTooltipAndExplicitEmptyCells() {
        var page = fixture("", false);
        page.select("tr").get(1).children().get(1).html("<div data-title='课程名称：数据结构<br>上课时间：第10周星期一[03-04]节<br>上课地点：教学楼201'>数据结构</div>");
        page.select("tr").get(1).children().get(2).html("<div class='kbcontent'><span>无课</span></div>");
        var result = PersonalScheduleService.parse(page, "2026-10-01");
        assertEquals("数据结构", result.course().get(0).items().get(0).get(0).courseName());
        assertEquals("教学楼201", result.course().get(0).items().get(0).get(0).place());
        assertTrue(result.course().get(1).items().get(0).isEmpty());
    }

    @Test
    void rejectsWrongWeekHeaderMissingRowsAndUnrecognizedCourses() {
        var wrongHeader = fixture("高等数学", false);
        wrongHeader.select("th").get(1).text("星期日");
        assertThrows(ProtocolException.class, () -> PersonalScheduleService.parse(wrongHeader, "2026-10-01"));
        var missing = fixture("高等数学", false);
        missing.select("tr").last().remove();
        assertThrows(ProtocolException.class, () -> PersonalScheduleService.parse(missing, "2026-10-01"));
        var unrecognized = fixture("高等数学", false);
        unrecognized.select("tr").get(1).children().get(1).html("<span>未识别课程</span>");
        assertThrows(ProtocolException.class, () -> PersonalScheduleService.parse(unrecognized, "2026-10-01"));
    }

    @Test
    void doesNotInventTimesOrAcceptDifferentResponseDate() {
        var page = fixture("高等数学", false);
        page.select("tr").get(1).children().first().text("第一大节");
        assertEquals("", PersonalScheduleService.parse(page, "2026-10-01").course().get(0).items().get(0).get(0).startAt());
        page.body().append("<input name='rq' value='2026-09-30'>");
        assertThrows(ProtocolException.class, () -> PersonalScheduleService.parse(page, "2026-10-01"));
    }

    @Test
    void rejectsInvalidDatesBeforeSchoolNetworkAccess() {
        var identity = mock(IdentityService.class);
        var school = mock(SchoolSsoClient.class);
        when(identity.schoolSession("token")).thenReturn(school);
        var service = new PersonalScheduleService(identity);
        for (String date : new String[] { null, "", "2026-02-29", "2026-13-01", "2026-1-1", "2026-10-01&xh=other" }) {
            assertEquals("INVALID_DATE", assertThrows(ProtocolException.class, () -> service.schedule("token", date)).code());
        }
        verifyNoInteractions(school);
    }

    @Test
    void usesCurrentSessionAndNeverCachesCompletedSchedules() {
        var identity = mock(IdentityService.class);
        var first = mock(SchoolSsoClient.class);
        var second = mock(SchoolSsoClient.class);
        when(identity.schoolSession("first")).thenReturn(first);
        when(identity.schoolSession("second")).thenReturn(second);
        when(first.personalSchedulePage("2026-10-01")).thenReturn(fixture("调整前", false), fixture("调整后", false));
        when(second.personalSchedulePage("2026-10-01")).thenReturn(fixture("其他用户课程", false));
        var service = new PersonalScheduleService(identity);
        assertEquals("调整前", service.schedule("first", "2026-10-01").course().get(0).items().get(0).get(0).courseName());
        assertEquals("调整后", service.schedule("first", "2026-10-01").course().get(0).items().get(0).get(0).courseName());
        assertEquals("其他用户课程", service.schedule("second", "2026-10-01").course().get(0).items().get(0).get(0).courseName());
        verify(first, times(2)).personalSchedulePage("2026-10-01");
        when(identity.schoolSession("first")).thenThrow(new ProtocolException(HttpStatus.UNAUTHORIZED, "Expired"));
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ProtocolException.class,
                () -> service.schedule("first", "2026-10-01")).status());
    }

    private static Document fixture(String lesson, boolean duplicates) {
        StringBuilder html = new StringBuilder("<script>$(\"#li_showWeek\").html(\"<span>第10周</span>/22周\");</script><table><tr><th></th>");
        for (String day : List.of("一", "二", "三", "四", "五", "六", "日")) html.append("<th>星期").append(day).append("</th>");
        html.append("</tr>");
        for (int slot = 0; slot < 6; slot++) {
            html.append("<tr><td>第").append(slot + 1).append("大节<br/>08:15-09:50</td>");
            for (int day = 0; day < 7; day++) {
                html.append("<td>");
                if (!lesson.isBlank() && ((slot == 0 && day == 0) || (slot == 5 && day == 6))) {
                    String name = day == 0 ? lesson : "基础外语";
                    String record = "<div title='课程学分：2 课程属性：必修 课程名称：" + name
                            + "<br>任课教师：测试教师<br>上课时间：第10周星期一[01-02]节<br>上课地点：8教401'>" + name + "</div>";
                    html.append(record);
                    if (duplicates && day == 0) html.append(record);
                } else html.append("&nbsp;");
                html.append("</td>");
            }
            html.append("</tr>");
        }
        return Jsoup.parse(html.append("</table>").toString());
    }
}
