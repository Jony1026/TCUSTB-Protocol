package net.tdteam.ustb.auth.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import net.tdteam.ustb.auth.infrastructure.SchoolSsoClient;
import net.tdteam.ustb.auth.model.ChallengeResponse;
import net.tdteam.ustb.auth.model.LoginRequest;
import net.tdteam.ustb.auth.model.SessionResponse;
import net.tdteam.ustb.auth.model.SsoStatusResponse;
import net.tdteam.ustb.common.error.ProtocolException;
import net.tdteam.ustb.user.infrastructure.UserDataRepository;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 服务
 *
 * @author itsjony01
 * @date 2026-09-30
 */
@Service
public class IdentityService {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Duration SESSION_TTL = Duration.ofHours(2);
    private static final int MAX_CHALLENGES = 500;
    private static final int MAX_SESSIONS = 2_000;
    private final ConcurrentHashMap<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final UserDataRepository userData;

    public IdentityService(UserDataRepository userData) {
        this.userData = userData;
    }

    public ChallengeResponse issueChallenge(String account) {
        purgeExpired();
        if (challenges.size() >= MAX_CHALLENGES) {
            throw new ProtocolException(HttpStatus.SERVICE_UNAVAILABLE, "Too many pending logins");
        }
        SchoolSsoClient client = new SchoolSsoClient();
        client.prepare(account);
        String id = token();
        Instant expiry = Instant.now().plus(CHALLENGE_TTL);
        challenges.put(id, new Challenge(client, expiry, account));
        return new ChallengeResponse(id, expiry, client.captchaRequired(), client.captchaType());
    }

    //人机验证的实现
    public byte[] captcha(String id) {
        Challenge challenge = challenges.get(id);
        if (challenge == null || !challenge.expiresAt().isAfter(Instant.now())) {
            challenges.remove(id);
            throw new ProtocolException(HttpStatus.NOT_FOUND, "Challenge expired or not found");
        }
        return challenge.client().captcha();
    }

    public SessionResponse signIn(LoginRequest request) {
        return signIn(request, null);
    }

    /** 登录成功后关联当前微信用户，绑定状态由 SQLite 独立保存。@author itsjony01 @date 2026-10-06 */
    public SessionResponse signIn(LoginRequest request, String wechatAuthorization) {
        Challenge challenge = challenges.remove(request.challengeId());
        if (challenge == null || !challenge.expiresAt().isAfter(Instant.now())) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "Challenge expired or already used");
        }
        if (challenge.account() == null || !challenge.account().equals(request.account())) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "Create a challenge for this account first");
        }
        purgeExpired();
        if (sessions.size() >= MAX_SESSIONS) {
            throw new ProtocolException(HttpStatus.SERVICE_UNAVAILABLE, "Too many active sessions");
        }
        challenge.client().login(request.account(), request.password(), request.captcha());
        //
        String realName = null;
        try {
            realName = challenge.client().realName(request.account());
        } catch (ProtocolException exception) {
            LOG.warn("School profile unavailable: code={}", exception.code());
        }
        String linkedOpenId = null;
        if (wechatAuthorization != null && !wechatAuthorization.isBlank()) {
            String wechatToken = wechatAuthorization.startsWith("Bearer ") ? wechatAuthorization.substring(7) : wechatAuthorization;
            linkedOpenId = userData.findWechatSession(wechatToken, Instant.now())
                    .map(UserDataRepository.WechatSession::openId).orElse(null);
        }
        String accessToken = token();
        Instant expiry = Instant.now().plus(SESSION_TTL);
        sessions.put(accessToken, new Session(request.account(), realName, challenge.client(), expiry, linkedOpenId));
        if (linkedOpenId != null) userData.saveAcademicBinding(linkedOpenId, request.account(), realName, Instant.now());
        return new SessionResponse(accessToken, request.account(), realName, expiry);
    }

    public SessionResponse current(String accessToken) {
        Session session = activeSession(accessToken);
        return new SessionResponse(accessToken, session.account(), session.realName(), session.expiresAt());
    }

    /**
     * 检测学校 SSO Cookie 的真实状态，并区分过期与学校暂不可达。
     *
     * @author itsjony01
     * @date 2026-10-04
     */
    public SsoStatusResponse ssoStatus(String accessToken) {
        Session session = activeSession(accessToken);
        Instant checkedAt = Instant.now();
        try {
            session.client().verifySession();
            return new SsoStatusResponse("ACTIVE", true, checkedAt);
        } catch (ProtocolException exception) {
            if ("SCHOOL_SESSION_UNVERIFIED".equals(exception.code())) {
                return new SsoStatusResponse("EXPIRED", false, checkedAt);
            }
            LOG.warn("School SSO status unavailable: code={}", exception.code());
            return new SsoStatusResponse("UNAVAILABLE", false, checkedAt);
        }
    }

    //获取在线的实例
    public SchoolSsoClient schoolSession(String accessToken) {
        return activeSession(accessToken).client();
    }

    /** 公共课表优先使用调用者会话，否则复用任一有效只读会话。@author itsjony01 @date 2026-10-06 */
    public SchoolSsoClient schoolSessionOrShared(String accessToken) {
        if (accessToken != null && !accessToken.isBlank()) return schoolSession(accessToken);
        purgeExpired();
        return sessions.values().stream().filter(session -> session.expiresAt().isAfter(Instant.now()))
                .map(Session::client).findFirst()
                .orElseThrow(() -> new ProtocolException(HttpStatus.SERVICE_UNAVAILABLE,
                        "PUBLIC_SCHOOL_SESSION_UNAVAILABLE", "No public school session is currently available"));
    }

    /** 返回教务令牌关联的微信用户，个人快照据此隔离。@author itsjony01 @date 2026-10-06 */
    public String linkedOpenId(String accessToken) {
        return activeSession(accessToken).openId();
    }

    private Session activeSession(String accessToken) {
        //卧槽我刚刚忘记这个了我说怎么一直空指针
        if (accessToken == null || accessToken.isBlank()) {
            throw academicSessionExpired();
        }
        Session session = sessions.get(accessToken);
        if (session == null || !session.expiresAt().isAfter(Instant.now())) {
            sessions.remove(accessToken);
            //过期了 你走开（
            throw academicSessionExpired();
        }
        return session;
    }

    public void signOut(String accessToken) {
        sessions.remove(accessToken);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        challenges.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ProtocolException academicSessionExpired() {
        return new ProtocolException(HttpStatus.UNAUTHORIZED, "ACADEMIC_SESSION_EXPIRED",
                "教务认证会话已过期，请重新认证");
    }

    private record Challenge(SchoolSsoClient client, Instant expiresAt, String account) {
    }

    private record Session(String account, String realName, SchoolSsoClient client, Instant expiresAt, String openId) {
    }
}
