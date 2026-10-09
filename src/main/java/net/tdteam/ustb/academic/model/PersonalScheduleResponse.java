package net.tdteam.ustb.academic.model;

import java.time.Instant;
import java.util.List;

public record PersonalScheduleResponse(String date, String currentWeek, String endWeek, String tips,
                                       boolean outsideTeachingCalendar,
                                       List<Day> course, Instant savedAt, String source) {
    public PersonalScheduleResponse(String date, String currentWeek, String endWeek, String tips,
                                    boolean outsideTeachingCalendar, List<Day> course) {
        this(date, currentWeek, endWeek, tips, outsideTeachingCalendar, course, null, "LIVE");
    }

    /** 附加快照来源与保存时间，不改变学校课表主体结构。@author itsjony01 @date 2026-10-06 */
    public PersonalScheduleResponse withSnapshot(Instant savedAt, String source) {
        return new PersonalScheduleResponse(date, currentWeek, endWeek, tips, outsideTeachingCalendar,
                course, savedAt, source);
    }

    public record Day(List<List<Course>> items) {
    }

    public record Course(String courseName, String place, String teacher, String className,
                         String teachWeek, String teachNo, String startAt, String endAt) {
    }
}
