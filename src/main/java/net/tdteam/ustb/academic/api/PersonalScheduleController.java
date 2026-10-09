package net.tdteam.ustb.academic.api;

import net.tdteam.ustb.academic.application.PersonalScheduleService;
import net.tdteam.ustb.academic.model.PersonalScheduleResponse;
import net.tdteam.ustb.common.error.ProtocolException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户的个人课表入口，不接受其他学生的学号，不缓存私有课表。
 * @author itsjony01
 * @date 2026-10-01
 */
@RestController
@RequestMapping("/api/v1/academic/my")
public class PersonalScheduleController {
    private final PersonalScheduleService service;

    public PersonalScheduleController(PersonalScheduleService service) {
        this.service = service;
    }

    /** 日期由前端按北京时间选择，学校会话始终来自登录令牌。@author itsjony01 @date 2026-10-01 */
    @GetMapping("/schedule")
    public ResponseEntity<PersonalScheduleResponse> schedule(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Wechat-Authorization", required = false) String wechatAuthorization,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "false") boolean refresh) {
        String academicToken = bearer(authorization);
        String wechatToken = bearer(wechatAuthorization);
        PersonalScheduleResponse response = wechatToken == null && !refresh
                ? service.schedule(academicToken, date)
                : service.schedule(academicToken, wechatToken, date, refresh);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    private static String bearer(String authorization) {
        if (authorization == null || authorization.isBlank()) return null;
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ProtocolException(HttpStatus.UNAUTHORIZED, "Bearer token required");
        }
        return authorization.substring(7);
    }
}
