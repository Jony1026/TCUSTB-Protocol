package net.tdteam.ustb.academic.api;

import java.util.List;
import net.tdteam.ustb.academic.application.ClassScheduleService;
import net.tdteam.ustb.academic.model.ClassScheduleResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 班级课表查询入口，仅使用工具箱令牌，不暴露学校会话。
 * @author itsjony01
 * @date 2026-10-01
 */
@RestController
@RequestMapping("/api/v1/academic")
public class ClassScheduleController {
    private final ClassScheduleService service;

    public ClassScheduleController(ClassScheduleService service) {
        this.service = service;
    }

    //学期
    @GetMapping("/classes/semesters")
    public ResponseEntity<List<ClassScheduleService.Option>> semesters(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.semesters(optionalBearer(authorization)));
    }

    @GetMapping("/classes/{className}/schedule")
    public ResponseEntity<ClassScheduleResponse> schedule(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String className, @RequestParam String semester) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.schedule(optionalBearer(authorization), className, semester));
    }

    private static String optionalBearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") && authorization.length() > 7
                ? authorization.substring(7) : null;
    }
}
