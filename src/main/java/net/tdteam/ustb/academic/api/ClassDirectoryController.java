package net.tdteam.ustb.academic.api;

import net.tdteam.ustb.academic.application.ClassDirectoryService;
import net.tdteam.ustb.academic.model.ClassDirectoryResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 班级目录接口，使用已登录的学校会话
 * @author itsjony01
 * @date 2026-10-01
 */
@RestController
@RequestMapping("/api/v1/academic")
public class ClassDirectoryController {
    private final ClassDirectoryService service;

    public ClassDirectoryController(ClassDirectoryService service) {
        this.service = service;
    }

    /**
     * 返回按在校年级分组的班级列表，已有服务端快照时无需绑定教务。
     * @author itsjony01
     * @date 2026-10-01
     */
    @GetMapping("/classes")
    public ResponseEntity<ClassDirectoryResponse> classes(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.classes(optionalBearer(authorization)));
    }

    private static String optionalBearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") && authorization.length() > 7
                ? authorization.substring(7) : null;
    }
}
