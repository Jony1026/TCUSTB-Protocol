package net.tdteam.ustb.academic.model;

import java.util.List;

public record ClassScheduleResponse(String className, List<Day> course) {
    public record Day(List<List<Course>> items) {
    }

    public record Course(String courseName, String place, String teacher, String className,
                         String teachWeek, String teachNo, String startAt, String endAt) {
    }
}
