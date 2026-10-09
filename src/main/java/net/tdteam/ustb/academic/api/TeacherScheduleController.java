package net.tdteam.ustb.academic.api;

import net.tdteam.ustb.academic.application.TeacherScheduleService;
import net.tdteam.ustb.academic.model.TeacherScheduleResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/academic/teachers")
public class TeacherScheduleController {
    private final TeacherScheduleService service;

    public TeacherScheduleController(TeacherScheduleService service) {
        this.service = service;
    }

    @GetMapping("/options")
    public ResponseEntity<TeacherScheduleService.Options> options(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.options(optionalBearer(authorization)));
    }

    @GetMapping
    public ResponseEntity<TeacherScheduleService.Directory> directory(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam String semester,
            @RequestParam(required = false, defaultValue = "") String college) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.directory(optionalBearer(authorization), semester, college));
    }

    @GetMapping("/{teacherName}/schedule")
    public ResponseEntity<TeacherScheduleResponse> schedule(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String teacherName, @RequestParam String semester) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.schedule(optionalBearer(authorization), teacherName, semester));
    }

    private static String optionalBearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") && authorization.length() > 7
                ? authorization.substring(7) : null;
    }
}
