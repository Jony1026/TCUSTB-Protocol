package net.tdteam.ustb.academic.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.tdteam.ustb.auth.application.IdentityService;
import net.tdteam.ustb.auth.infrastructure.SchoolSsoClient;
import net.tdteam.ustb.common.error.ProtocolException;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 教师学期选项、目录和精确课表解析测试。@author itsjony01 @date 2026-10-01 */
class TeacherScheduleServiceTest {
    @TempDir Path temp;
    private static final String OPTIONS = "<select name='xnxqh'><option value='2026-2027-1' selected>2026-2027-1</option>"
            + "</select><select name='kbjcmsid'><option value='mode' selected>默认节次模式</option></select>"
            + "<select name='skyx'><option value='all' selected>全部院系</option>"
            + "<option value='college-a'>计算机学院</option>"
            + "<option value='college-b'>其他学院</option></select>";

    @Test
    void storesNamesOnceAndReadsSnapshotAcrossSessions() throws IOException {
        IdentityService identity = mock(IdentityService.class);
        SchoolSsoClient school = mock(SchoolSsoClient.class);
        when(identity.schoolSession("token")).thenReturn(school);
        when(school.teacherScheduleOptionsPage()).thenReturn(Jsoup.parse(OPTIONS));
        when(school.teacherSchedulePage("2026-2027-1", "mode", "", "college-a"))
                .thenReturn(Jsoup.parse(table("张三", "李四")));
        when(school.teacherSchedulePage("2026-2027-1", "mode", "张三"))
                .thenReturn(Jsoup.parse(table("张三")));
        var service = new TeacherScheduleService(identity, temp);
        assertEquals("mode", service.options("token").timeModel().get(0).value());
        assertEquals("college-a", service.options("token").college().get(0).value());
        assertEquals(List.of("张三", "李四"), service.directory("token", "2026-2027-1", "college-a").names());
        assertEquals(List.of("张三", "李四"), service.directory("token", "2026-2027-1", "college-a").names());
        assertEquals("college-a", service.directory("token", "2026-2027-1", "college-a").college());
        assertEquals(1, Files.walk(temp).filter(path -> path.toString().endsWith(".json")).count());
        // 新进程等效的服务实例和另一登录会话直接读取文件，不向学校查询名单。
        SchoolSsoClient otherSchool = mock(SchoolSsoClient.class);
        when(identity.schoolSession("other-token")).thenReturn(otherSchool);
        assertEquals(List.of("张三", "李四"), new TeacherScheduleService(identity, temp)
                .directory("other-token", "2026-2027-1", "college-a").names());
        assertEquals("张三", service.schedule("token", "张三", "2026-2027-1").teacherName());
        assertEquals("张三", service.schedule("token", "张三", "2026-2027-1").teacherName());
        verify(school).teacherSchedulePage("2026-2027-1", "mode", "", "college-a");
        verify(school, times(2)).teacherSchedulePage("2026-2027-1", "mode", "张三");
        verify(school, times(1)).teacherScheduleOptionsPage();
        assertEquals("INVALID_SEMESTER", assertThrows(ProtocolException.class,
                () -> service.directory("token", "../../file", "college-a")).code());
        assertEquals("INVALID_COLLEGE", assertThrows(ProtocolException.class,
                () -> service.directory("token", "2026-2027-1", "x".repeat(121))).code());
    }

    @Test
    void rebuildsOnlyDeletedSemesterCollegeAndRejectsCorruptSnapshot() throws IOException {
        IdentityService identity = mock(IdentityService.class);
        SchoolSsoClient school = mock(SchoolSsoClient.class);
        when(identity.schoolSession("token")).thenReturn(school);
        when(school.teacherScheduleOptionsPage()).thenReturn(Jsoup.parse(OPTIONS));
        when(school.teacherSchedulePage("2026-2027-1", "mode", "", "college-a"))
                .thenReturn(Jsoup.parse(table("张三")));
        when(school.teacherSchedulePage("2026-2027-1", "mode", "", "college-b"))
                .thenReturn(Jsoup.parse(table("李四")));
        var service = new TeacherScheduleService(identity, temp);
        service.directory("token", "2026-2027-1", "college-a");
        service.directory("token", "2026-2027-1", "college-b");
        List<Path> files = Files.walk(temp).filter(path -> path.toString().endsWith(".json")).toList();
        assertEquals(2, files.size());
        Path a = files.stream().filter(path -> {
            try { return Files.readString(path).contains("college-a"); } catch (IOException e) { throw new RuntimeException(e); }
        }).findFirst().orElseThrow();
        Files.delete(a);
        assertEquals(List.of("张三"), service.directory("token", "2026-2027-1", "college-a").names());
        verify(school, times(2)).teacherSchedulePage("2026-2027-1", "mode", "", "college-a");
        verify(school, times(1)).teacherSchedulePage("2026-2027-1", "mode", "", "college-b");
        Files.writeString(a, "{broken");
        assertEquals("TEACHER_DIRECTORY_STORAGE_FAILED", assertThrows(ProtocolException.class,
                () -> service.directory("token", "2026-2027-1", "college-a")).code());
        assertEquals("{broken", Files.readString(a));
    }

    @Test
    void neverSavesFailedOrEmptySchoolCollection() throws IOException {
        IdentityService identity = mock(IdentityService.class);
        SchoolSsoClient school = mock(SchoolSsoClient.class);
        when(identity.schoolSession("token")).thenReturn(school);
        when(school.teacherScheduleOptionsPage()).thenReturn(Jsoup.parse(OPTIONS));
        when(school.teacherSchedulePage("2026-2027-1", "mode", "", "college-a"))
                .thenReturn(Jsoup.parse(table()));
        var service = new TeacherScheduleService(identity, temp);
        assertEquals("SCHOOL_TEACHER_DIRECTORY_UNAVAILABLE", assertThrows(ProtocolException.class,
                () -> service.directory("token", "2026-2027-1", "college-a")).code());
        assertFalse(Files.exists(temp.resolve("2026-2027-1")));
        when(school.teacherSchedulePage("2026-2027-1", "mode", "", "college-a"))
                .thenReturn(Jsoup.parse(table("王五")));
        assertEquals(List.of("王五"), service.directory("token", "2026-2027-1", "college-a").names());
        assertTrue(Files.exists(temp.resolve("2026-2027-1")));
    }

    @Test
    void aggregatesAllCollegeDirectoriesWhenCollegeIsNotSelected() {
        IdentityService identity = mock(IdentityService.class);
        SchoolSsoClient school = mock(SchoolSsoClient.class);
        when(identity.schoolSession("token")).thenReturn(school);
        when(school.teacherScheduleOptionsPage()).thenReturn(Jsoup.parse(OPTIONS));
        when(school.teacherSchedulePage("2026-2027-1", "mode", "", "college-a"))
                .thenReturn(Jsoup.parse(table("张三", "李四")));
        when(school.teacherSchedulePage("2026-2027-1", "mode", "", "college-b"))
                .thenReturn(Jsoup.parse(table("李四", "王五")));
        var service = new TeacherScheduleService(identity, temp);
        var all = service.directory("token", "2026-2027-1", "");
        assertEquals("", all.college());
        assertEquals(List.of("张三", "李四", "王五"), all.names());
        assertEquals(List.of("张三", "李四"), service.directory("token", "2026-2027-1", "college-a").names());
        verify(school, times(1)).teacherSchedulePage("2026-2027-1", "mode", "", "college-a");
        verify(school, times(1)).teacherSchedulePage("2026-2027-1", "mode", "", "college-b");
    }

    @Test
    void mergesDuplicateTeacherRowsAndParsesOverlappingCourses() {
        String html = table("张三", "张三丰");
        var result = TeacherScheduleService.parse(Jsoup.parse(html), "张三");
        assertEquals("张三", result.teacherName());
        assertEquals(7, result.course().size());
        var courses = result.course().get(0).items().get(0);
        assertEquals(2, courses.size());
        assertEquals("程序设计基础计2301", courses.get(0).courseName());
        assertEquals("张三", courses.get(0).teacher());
        assertEquals("1-8周", courses.get(0).teachWeek());
        assertEquals("10教301", courses.get(0).place());
        assertEquals("08:00", courses.get(0).startAt());
        assertEquals("TEACHER_NOT_FOUND", assertThrows(ProtocolException.class,
                () -> TeacherScheduleService.parse(Jsoup.parse(html), "张四")).code());
        var duplicate = TeacherScheduleService.parse(Jsoup.parse(table("张三", "张三")), "张三");
        assertEquals(2, duplicate.course().get(0).items().get(0).size());
    }

    @Test
    void mergesDifferentRowsForSameTeacherWithoutDuplicatingCourses() {
        String first = tableWithCourse("张三", "程序设计基础", "1-8周", "10教301");
        String second = tableWithCourse("张三", "高等数学", "9-16周", "5教202");
        var result = TeacherScheduleService.parse(Jsoup.parse(mergeRows(first, second)), "张三");
        assertEquals(2, result.course().get(0).items().get(0).size());
        assertEquals("程序设计基础", result.course().get(0).items().get(0).get(0).courseName());
        assertEquals("高等数学", result.course().get(0).items().get(0).get(1).courseName());
    }

    @Test
    void failsClosedOnUnexpectedSchoolHtml() {
        assertEquals("SCHOOL_TEACHER_SCHEDULE_FORMAT_CHANGED", assertThrows(ProtocolException.class,
                () -> TeacherScheduleService.parse(Jsoup.parse("<html>登录成功</html>"), "张三")).code());
        assertEquals("SCHOOL_TEACHER_SCHEDULE_FORMAT_CHANGED", assertThrows(ProtocolException.class,
                () -> TeacherScheduleService.parse(Jsoup.parse(table("张三").replace("(1-8周)", "未知周次")), "张三")).code());
        assertEquals("SCHOOL_TEACHER_SCHEDULE_FORMAT_CHANGED", assertThrows(ProtocolException.class,
                () -> TeacherScheduleService.parseOptions(Jsoup.parse("<select name='xnxqh'></select>"))).code());
    }

    private static String table(String... names) {
        StringBuilder html = new StringBuilder("<table><tr>");
        for (String day : List.of("一", "二", "三", "四", "五", "六", "日")) {
            html.append("<th colspan='6'>星期").append(day).append("</th>");
        }
        html.append("</tr>");
        for (String name : names) {
            html.append("<tr><td align='center'><nobr>").append(name).append("</nobr></td>");
            for (int index = 0; index < 42; index++) {
                html.append(index == 0 ? "<td><div class='kbcontent1'>程序设计基础计2301<br>张三 (1-8周)<br>10教301</div>"
                        + "<div class='kbcontent1'>程序设计基础计2301<br>张三 (9-16周)<br>10教301</div></td>"
                        : "<td>&nbsp;</td>");
            }
            html.append("</tr>");
        }
        return html.append("</table>").toString();
    }

    /** 构造单条课程记录，用于验证同名教师多行合并。@author itsjony01 @date 2026-10-01 */
    private static String tableWithCourse(String teacher, String course, String week, String place) {
        StringBuilder html = new StringBuilder("<table><tr>");
        for (String day : List.of("一", "二", "三", "四", "五", "六", "日")) {
            html.append("<th colspan='6'>星期").append(day).append("</th>");
        }
        html.append("</tr><tr><td align='center'><nobr>").append(teacher).append("</nobr></td>");
        for (int index = 0; index < 42; index++) {
            html.append(index == 0
                    ? "<td><div class='kbcontent1'>" + course + "<br>" + teacher + " (" + week + ")<br>" + place + "</div></td>"
                    : "<td>&nbsp;</td>");
        }
        return html.append("</tr></table>").toString();
    }

    /** 将两个独立测试表拼成学校可能返回的同名教师多行结果。@author itsjony01 @date 2026-10-01 */
    private static String mergeRows(String first, String second) {
        int firstTableEnd = first.lastIndexOf("</table>");
        int secondHeaderEnd = second.indexOf("</tr>") + "</tr>".length();
        int secondTableEnd = second.lastIndexOf("</table>");
        return first.substring(0, firstTableEnd) + second.substring(secondHeaderEnd, secondTableEnd) + "</table>";
    }
}
