package net.tdteam.ustb.academic.model;

import java.util.List;

/** 学校教师课表的七日六节网格，课程名保留学校原文。@author itsjony01 @date 2026-10-01 */
public record TeacherScheduleResponse(String teacherName, List<Day> course) {
    public record Day(List<List<Course>> items) {
    }

    public record Course(String courseName, String place, String teacher, String className,
                         String teachWeek, String teachNo, String startAt, String endAt) {
    }
}
