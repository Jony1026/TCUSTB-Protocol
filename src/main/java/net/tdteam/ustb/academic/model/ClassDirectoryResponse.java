package net.tdteam.ustb.academic.model;

import java.util.List;

public record ClassDirectoryResponse(int year, List<GradeGroup> grades) {
    public record GradeGroup(int grade, List<String> names) {
    }
}
