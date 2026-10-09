package net.tdteam.ustb.auth.api;

import net.tdteam.ustb.auth.application.IdentityService;
import net.tdteam.ustb.auth.model.ChallengeResponse;
import net.tdteam.ustb.auth.model.LoginRequest;
import net.tdteam.ustb.auth.model.SessionResponse;
import net.tdteam.ustb.auth.model.SsoStatusResponse;
import net.tdteam.ustb.common.error.ProtocolException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 北京科技大学天津学院SSO认证系统API
 * 注：API底层接口均来自对教务系统、贝壳小盒子的逆向、抓包。为个人开发，与北京科技大学天津学院无关。
 *
 * @author itsjony01
 * @date 2026-09-30
 */
@RestController
@RequestMapping("/api/v1/identity")
public class IdentityController {
    private final IdentityService service;

    public IdentityController(IdentityService service) {
        this.service = service;
    }

    @GetMapping("/challenges")
    public ResponseEntity<ChallengeResponse> challenge(@RequestParam(required = false) String account) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.issueChallenge(account));
    }

    @GetMapping(value = "/challenges/{id}/captcha", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> captcha(@PathVariable String id) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.captcha(id));
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionResponse> login(
            @RequestHeader(value = "X-Wechat-Authorization", required = false) String wechatAuthorization,
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.signIn(request, wechatAuthorization));
    }

    @GetMapping("/sessions/current")
    public ResponseEntity<SessionResponse> current(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.current(bearer(authorization)));
    }

    @GetMapping("/sessions/current/sso-status")
    public ResponseEntity<SsoStatusResponse> ssoStatus(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.ssoStatus(bearer(authorization)));
    }

    @DeleteMapping("/sessions/current")
    public ResponseEntity<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        service.signOut(bearer(authorization));
        return ResponseEntity.noContent().build();
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ProtocolException(HttpStatus.UNAUTHORIZED, "Bearer token required");
        }
        return authorization.substring(7);
    }
}
