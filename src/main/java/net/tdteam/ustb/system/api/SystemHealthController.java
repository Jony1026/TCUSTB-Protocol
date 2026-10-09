package net.tdteam.ustb.system.api;

import java.time.Instant;
import net.tdteam.ustb.auth.infrastructure.SchoolSsoClient;
import net.tdteam.ustb.system.model.SystemHealthResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 田园小纸箱源站健康检测接口。
 *
 * @author itsjony01
 * @date 2026-10-04
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemHealthController {
    @GetMapping("/health")
    public ResponseEntity<SystemHealthResponse> health() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SystemHealthResponse("田园小纸箱", "UP", Instant.now()));
    }

    /**
     * 无需绑定账号即可检测学校统一认证入口是否可达，不提交任何用户凭据。
     *
     * @author itsjony01
     * @date 2026-10-04
     */
    @GetMapping("/school-sso-health")
    public ResponseEntity<SystemHealthResponse> schoolSsoHealth() {
        new SchoolSsoClient().prepare();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SystemHealthResponse("学校教务 SSO", "UP", Instant.now()));
    }
}
