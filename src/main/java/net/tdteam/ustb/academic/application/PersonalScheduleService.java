package net.tdteam.ustb.academic.application;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.tdteam.ustb.academic.model.PersonalScheduleResponse;
import net.tdteam.ustb.auth.application.IdentityService;
import net.tdteam.ustb.common.error.ProtocolException;
import net.tdteam.ustb.user.infrastructure.UserDataRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 解析学校首页日期课表；只使用当前学生会话，不从班级课表推测个人选课。
 * @author itsjony01
 * @date 2026-10-01
 */
@Service
public class PersonalScheduleService {
    private static final List<String> DAYS = List.of("一", "二", "三", "四", "五", "六", "日");
    private static final Pattern TIME = Pattern.compile("([0-2]?[0-9]:[0-5][0-9])\\s*[-—–~至]\\s*([0-2]?[0-9]:[0-5][0-9])");
    private static final Pattern FIELD = Pattern.compile("(课程学分|课程属性|课程名称|上课班级|任课教师|授课教师|上课教师|教师|上课时间|上课地点)\\s*[：:]\\s*");
    private static final Pattern TEACH_NO = Pattern.compile("[\\[【]([^]】]+)[]】]\\s*节");
    private static final Pattern WEEK_SCRIPT = Pattern.compile("\\$\\(\\s*[\"']#li_showWeek[\"']\\s*\\)\\s*\\.html\\(\\s*([\"'])(.*?)\\1\\s*\\)", Pattern.DOTALL);
    private static final Pattern CURRENT_WEEK = Pattern.compile("第\\s*[0-9]+\\s*周");
    private static final Pattern END_WEEK = Pattern.compile("/\\s*([0-9]+\\s*周)");
    private final IdentityService identity;
    private final UserDataRepository userData;

    @Autowired
    public PersonalScheduleService(IdentityService identity, UserDataRepository userData) {
        this.identity = identity;
        this.userData = userData;
    }

    PersonalScheduleService(IdentityService identity) {
        this.identity = identity;
        this.userData = null;
    }

    public PersonalScheduleResponse schedule(String token, String date) {
        return schedule(token, null, date, true);
    }

    /** 默认优先返回 SQLite 快照，refresh=true 时才访问学校并覆盖保存。@author itsjony01 @date 2026-10-06 */
    public PersonalScheduleResponse schedule(String token, String wechatToken, String date, boolean refresh) {
        String normalized = validateDate(date);
        String openId = wechatOpenId(wechatToken);
        LocalDate weekStart = weekStart(normalized);
        if (!refresh && openId != null && userData != null) {
            var cached = userData.findPersonalSchedule(openId, weekStart);
            if (cached.isPresent()) return cached.get().response().withSnapshot(cached.get().savedAt(), "CACHE");
        }
        var school = identity.schoolSession(token);
        if (openId != null && identity.linkedOpenId(token) != null && !openId.equals(identity.linkedOpenId(token))) {
            throw new ProtocolException(HttpStatus.FORBIDDEN, "ACADEMIC_BINDING_MISMATCH",
                    "Academic session belongs to another Wechat user");
        }
        PersonalScheduleResponse response = parse(school.personalSchedulePage(normalized), normalized);
        Instant savedAt = Instant.now();
        if (openId != null && userData != null) userData.savePersonalSchedule(openId, weekStart, normalized, response, savedAt);
        return response.withSnapshot(savedAt, "LIVE");
    }

    private String wechatOpenId(String token) {
        if (token == null || token.isBlank() || userData == null) return null;
        return userData.findWechatSession(token, Instant.now()).map(UserDataRepository.WechatSession::openId)
                .orElseThrow(() -> new ProtocolException(HttpStatus.UNAUTHORIZED, "WECHAT_SESSION_EXPIRED",
                        "微信登录状态已过期，请重新登录"));
    }

    private static LocalDate weekStart(String date) {
        LocalDate value = LocalDate.parse(date);
        return value.minusDays(value.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }


    static PersonalScheduleResponse parse(Document page, String date) {
        if (page.text().contains("当前日期不在教学周历内") || page.select("script").stream()
                .anyMatch(script -> script.data().contains("当前日期不在教学周历内"))) {
            return new PersonalScheduleResponse(date, "", "", "所选日期不在教学周历内", true, emptyWeek());
        }
        // 学校若回显查询日期，必须与请求一致，避免把旧页面当作当前日期课表。
        Element selectedDate = page.selectFirst("input[name=rq][value]");
        if (selectedDate != null && !selectedDate.val().isBlank() && !date.equals(selectedDate.val())) throw formatError();
        Element table = page.select("table").stream().filter(candidate -> candidate.select("tr").stream()
                .anyMatch(row -> row.closest("table") == candidate && isWeekHeader(row)))
                .findFirst().orElseThrow(PersonalScheduleService::formatError);
        List<Element> rows = table.select("tr").stream()
                .filter(row -> row.closest("table") == table && !isWeekHeader(row))
                .filter(row -> !cells(row).isEmpty()).toList();
        if (rows.size() != 6) throw formatError();
        List<List<List<PersonalScheduleResponse.Course>>> week = new ArrayList<>();
        for (int day = 0; day < 7; day++) week.add(new ArrayList<>());
        for (Element row : rows) {
            List<Element> cells = cells(row);
            if (cells.size() != 8 || cells.stream().anyMatch(cell ->
                    (cell.hasAttr("colspan") && !cell.attr("colspan").equals("1"))
                    || (cell.hasAttr("rowspan") && !cell.attr("rowspan").equals("1")))) throw formatError();
            String rowTitle = cells.get(0).text();
            Matcher time = TIME.matcher(rowTitle);
            boolean hasTime = time.find();
            String startAt = hasTime ? time.group(1) : "";
            String endAt = hasTime ? time.group(2) : "";
            String period = hasTime ? rowTitle.substring(0, time.start()).trim() : rowTitle.trim();
            for (int day = 0; day < 7; day++) {
                week.get(day).add(parseCourses(cells.get(day + 1), period, startAt, endAt));
            }
        }
        String weekLabel = weekLabel(page);
        Matcher current = CURRENT_WEEK.matcher(weekLabel);
        Matcher end = END_WEEK.matcher(weekLabel);
        return new PersonalScheduleResponse(date, current.find() ? current.group().replaceAll("\\s", "") : "",
                end.find() ? end.group(1).replaceAll("\\s", "") : "", weekLabel, false,
                week.stream().map(slots -> new PersonalScheduleResponse.Day(List.copyOf(slots))).toList());
    }

    // 优先读取每条课程的提示属性；没有提示时只接受带完整字段的格内文本。
    private static List<PersonalScheduleResponse.Course> parseCourses(Element cell, String period, String startAt, String endAt) {
        List<String> details = new ArrayList<>();
        for (Element element : cell.getAllElements()) {
            for (String attribute : List.of("title", "data-original-title", "data-content", "data-title")) {
                String value = element.attr(attribute);
                if (value.contains("课程名称")) {
                    details.add(Jsoup.parse(value).text());
                    break;
                }
            }
        }
        if (details.isEmpty()) {
            String text = cell.text().replace('\u00a0', ' ').trim();
            if (text.isBlank() || text.equals("无") || text.equals("无课") || text.equals("—") || text.equals("-")) return List.of();
            if (!cell.text().contains("课程名称")) throw formatError();
            details.add(cell.text());
        }
        List<PersonalScheduleResponse.Course> courses = new ArrayList<>();
        for (String detail : details) {
            Map<String, String> fields = fields(detail);
            String name = fields.getOrDefault("课程名称", "");
            String teachingTime = fields.getOrDefault("上课时间", "");
            if (name.isBlank() || teachingTime.isBlank()) throw formatError();
            Matcher teachNo = TEACH_NO.matcher(teachingTime);
            String teacher = List.of("任课教师", "授课教师", "上课教师", "教师").stream()
                    .map(key -> fields.getOrDefault(key, "")).filter(value -> !value.isBlank()).findFirst().orElse("");
            String teachWeek = teachingTime.split("(?:星期|周)[一二三四五六日天]", 2)[0].trim();
            courses.add(new PersonalScheduleResponse.Course(name, fields.getOrDefault("上课地点", ""), teacher,
                    fields.getOrDefault("上课班级", ""), teachWeek,
                    teachNo.find() ? teachNo.group(1) + "节" : period, startAt, endAt));
        }
        return List.copyOf(courses);
    }

    // 按学校字段标签切分，避免把课程名、教学周和教室混在一起。
    private static Map<String, String> fields(String detail) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = FIELD.matcher(detail);
        String key = null;
        int valueStart = 0;
        while (matcher.find()) {
            if (key != null && fields.put(key, detail.substring(valueStart, matcher.start()).trim()) != null) throw formatError();
            key = matcher.group(1);
            valueStart = matcher.end();
        }
        if (key != null && fields.put(key, detail.substring(valueStart).trim()) != null) throw formatError();
        return fields;
    }

    private static String weekLabel(Document page) {
        Element label = page.getElementById("li_showWeek");
        //    Element label = page.getElementById("showWeek");
        //return "idk";
        if (label != null && !label.text().isBlank()) return label.text();
        for (Element script : page.select("script")) {
            Matcher matcher = WEEK_SCRIPT.matcher(script.data());
            if (matcher.find()) return Jsoup.parse(matcher.group(2)).text();
        }
        return "";
    }

    private static boolean isWeekHeader(Element row) {
        List<Element> cells = cells(row);
        if (cells.size() != 8) return false;
        for (int day = 0; day < 7; day++) {
            String text = cells.get(day + 1).text().replace("天", "日");
            if (!text.contains("星期" + DAYS.get(day)) && !text.contains("周" + DAYS.get(day))) return false;
        }
        return true;
    }

    private static List<Element> cells(Element row) {
        return row.children().stream().filter(element -> element.normalName().equals("td") || element.normalName().equals("th")).toList();
    }

    private static List<PersonalScheduleResponse.Day> emptyWeek() {
        List<PersonalScheduleResponse.Day> days = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            days.add(new PersonalScheduleResponse.Day(List.of(List.of(), List.of(), List.of(), List.of(), List.of(), List.of())));
        }
        return List.copyOf(days);
    }

    private static String validateDate(String date) {
        try {
            if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
            return LocalDate.parse(date).toString();
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "Date must be a valid yyyy-MM-dd date");
        }
    }

    private static ProtocolException formatError() {
        return new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_PERSONAL_SCHEDULE_FORMAT_CHANGED", "School personal timetable format could not be parsed");
    }
}
