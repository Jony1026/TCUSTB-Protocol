package net.tdteam.ustb.auth.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.tdteam.ustb.common.error.ProtocolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * In-process fixtures for login redirects, cookies and encryption.
 *
 * @author itsjony01
 * @date 2026-09-30
 */
class SchoolSsoClientTest {
    private static final String SALT = "abcdefghijklmnop";
    private HttpServer server;
    private URI origin;
    private final AtomicInteger protectedVisits = new AtomicInteger();
    private boolean anonymousMain;
    private boolean requireCaptcha;
    private boolean sliderCaptcha;
    private int rejectedStatus = 200;
    private String rejectedMessage = "Invalid captcha";
    private String callback = "/jsxsd/";
    private boolean authenticationSucceeded;
    private boolean dropSsoCookie;
    private final AtomicInteger passwordPosts = new AtomicInteger();
    private final AtomicInteger ssoVisits = new AtomicInteger();
    private String profileAccount = "student";
    private boolean missingProfile;
    private final AtomicReference<String> classRequestBody = new AtomicReference<>();
    private final AtomicReference<String> classRequestCookie = new AtomicReference<>();
    private final AtomicReference<String> classRequestMethod = new AtomicReference<>();
    private String classResponse = "<table><tr><td align='center'><nobr>计科2301</nobr></td></tr></table>";
    private int classStatus = 200;
    private final AtomicInteger classVisits = new AtomicInteger();
    private boolean transientClassFailure;
    private boolean retryAfter;
    private long classDelayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/authserver/login", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null) {
                    ssoVisits.incrementAndGet();
                    assertEquals(origin.resolve("/jsxsd/").toString(), URLDecoder.decode(
                            query.substring("service=".length()), StandardCharsets.UTF_8));
                    String cookies = exchange.getRequestHeaders().getFirst("Cookie");
                    if (!authenticationSucceeded || cookies == null || !cookies.contains("SSO=fixture")) {
                        reply(exchange, 200, "<form id='pwdFromId'>Login required</form>");
                        return;
                    }
                    exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=school-session; Path=/jsxsd");
                    exchange.getResponseHeaders().add("Location", callback);
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=auth-session; Path=/authserver");
                reply(exchange, 200, "<script>var captchaSwitch = '" + (sliderCaptcha ? "2" : "1")
                        + "'; var _badCredentialsCount='3';</script>"
                        + "<form id='loginFromId'></form><form id='pwdFromId'><input id='pwdEncryptSalt' value='" + SALT
                        + "'><input name='execution' value='dynamic'><input name='lt' value=''>"
                        + "<div id='captchaDiv' class='" + (requireCaptcha ? "" : "hide") + "'></div></form>");
                return;
            }
            assertEquals(null, exchange.getRequestURI().getRawQuery());
            passwordPosts.incrementAndGet();
            assertTrue(exchange.getRequestHeaders().getFirst("Cookie").contains("JSESSIONID=auth-session"));
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> fields = Arrays.stream(form.split("&")).map(v -> v.split("=", 2))
                    .collect(Collectors.toMap(v -> URLDecoder.decode(v[0], StandardCharsets.UTF_8),
                            v -> URLDecoder.decode(v[1], StandardCharsets.UTF_8)));
            assertEquals("dynamic", fields.get("execution"));
            assertEquals("student", fields.get("username"));
            assertEquals("secret", decrypt(fields.get("password")));
            if (fields.get("captcha").equals("wrong")) {
                reply(exchange, rejectedStatus, "<form id='pwdFromId'>" + rejectedMessage + "</form>");
            } else {
                authenticationSucceeded = true;
                if (!dropSsoCookie) exchange.getResponseHeaders().add("Set-Cookie", "SSO=fixture; Path=/authserver");
                exchange.getResponseHeaders().add("Location", "https://portal.example/not-followed");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });
        server.createContext("/authserver/checkNeedCaptcha.htl", exchange -> {
            replyJson(exchange, "{\"isNeed\":" + requireCaptcha + "}");
        });
        server.createContext("/jsxsd/", exchange -> reply(exchange, 404, "Not found"));
        server.createContext("/jsxsd/sso.jsp", exchange -> {
            assertTrue(exchange.getRequestHeaders().getFirst("Cookie").contains("JSESSIONID=school-session"));
            exchange.getResponseHeaders().add("Location", "/jsxsd/framework/xsMain.jsp");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/jsxsd/framework/xsMain.jsp", exchange -> {
            assertTrue(exchange.getRequestHeaders().getFirst("Cookie").contains("JSESSIONID=school-session"));
            protectedVisits.incrementAndGet();
            if (anonymousMain) {
                reply(exchange, 200, "<script>window.location.href='https://authserver.tjustb.cn/authserver/login'</script>");
            } else {
                reply(exchange, 200, "<html><title>Student portal</title></html>");
            }
        });
        server.createContext("/jsxsd/grxx/xsxx", exchange -> {
            assertTrue(exchange.getRequestHeaders().getFirst("Cookie").contains("JSESSIONID=school-session"));
            if (missingProfile) {
                reply(exchange, 404, "Not found");
                return;
            }
            reply(exchange, 200, "<html><table><tr><td>学号：</td><td>" + profileAccount
                    + "</td></tr><tr><td>姓名：</td><td>测试学生</td></tr></table></html>");
        });
        server.createContext("/jsxsd/kbcx/kbxx_xzb_ifr", exchange -> {
            int visit = classVisits.incrementAndGet();
            classRequestMethod.set(exchange.getRequestMethod());
            classRequestCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            classRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (retryAfter) exchange.getResponseHeaders().add("Retry-After", "60");
            if (classDelayMillis > 0) {
                try {
                    Thread.sleep(classDelayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            reply(exchange, transientClassFailure && visit == 1 ? 503 : classStatus, classResponse);
        });
        server.createContext("/jsxsd/kbcx/kbxx_teacher", exchange -> {
            classRequestMethod.set(exchange.getRequestMethod());
            classRequestCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            reply(exchange, 200, "<select name='xnxqh'><option value='2026-2027-1'>2026-2027-1</option></select>"
                    + "<select name='kbjcmsid'><option value='mode'>默认</option></select>"
                    + "<select name='skyx'><option value='college-a'>计算机学院</option></select>");
        });
        server.createContext("/jsxsd/kbcx/kbxx_teacher_ifr", exchange -> {
            classRequestMethod.set(exchange.getRequestMethod());
            classRequestCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            classRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(origin.resolve("/jsxsd/kbcx/kbxx_teacher").toString(),
                    exchange.getRequestHeaders().getFirst("Referer"));
            reply(exchange, 200, "<table><tr><td align='center'><nobr>张三</nobr></td></tr></table>");
        });
        server.createContext("/jsxsd/framework/main_index_loadkb.jsp", exchange -> {
            classRequestMethod.set(exchange.getRequestMethod());
            classRequestCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            classRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(origin.resolve("/jsxsd/framework/xsMain_new.jsp?t1=1").toString(),
                    exchange.getRequestHeaders().getFirst("Referer"));
            reply(exchange, classStatus, classResponse);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void logsInUsingSchoolCookieAndDynamicParameters() {
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare("student");
        assertFalse(client.captchaRequired());
        client.login("student", "secret", "");
        assertEquals(1, protectedVisits.get());
        assertEquals(1, passwordPosts.get());
        assertEquals(1, ssoVisits.get());
    }

    /** 真实访问教务主页检测有效与失效的 SSO Cookie。@author itsjony01 @date 2026-10-04 */
    @Test
    void verifiesCurrentSchoolSession() {
        SchoolSsoClient client = loggedInClient();
        client.verifySession();
        anonymousMain = true;
        ProtocolException error = assertThrows(ProtocolException.class, client::verifySession);
        assertEquals("SCHOOL_SESSION_UNVERIFIED", error.code());
    }

    /**
     * 班级目录按年级和可选学期 POST，并复用登录后的教务 Cookie。
     * @author itsjony01
     * @date 2026-10-01
     */
    @Test
    void postsGradeAndSemesterWithSchoolCookie() {
        SchoolSsoClient client = loggedInClient();
        assertEquals("计科2301", client.classDirectoryPage(2023, "2023-2024-1").selectFirst("nobr").text());
        assertEquals("POST", classRequestMethod.get());
        assertTrue(classRequestCookie.get().contains("JSESSIONID=school-session"));
        Map<String, String> fields = Arrays.stream(classRequestBody.get().split("&"))
                .map(value -> value.split("=", 2))
                .collect(Collectors.toMap(value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8),
                        value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8)));
        assertEquals("2023", fields.get("sknj"));
        assertEquals("2023-2024-1", fields.get("xnxqh"));
        client.classDirectoryPage(2026);
        assertTrue(classRequestBody.get().contains("sknj=2026"));
        assertTrue(classRequestBody.get().contains("xnxqh="));
    }

    @Test
    void rejectsExpiredSessionInsteadOfReturningEmptyDirectory() {
        SchoolSsoClient client = loggedInClient();
        classResponse = "<script>window.location.href='/authserver/login'</script>";
        ProtocolException error = assertThrows(ProtocolException.class, () -> client.classDirectoryPage(2026));
        assertEquals(401, error.status().value());
        assertEquals("SCHOOL_SESSION_UNVERIFIED", error.code());
    }

    @Test
    void rejectsUpstreamErrorsInsteadOfReturningEmptyDirectory() {
        SchoolSsoClient client = loggedInClient();
        classStatus = 503;
        ProtocolException error = assertThrows(ProtocolException.class, () -> client.classDirectoryPage(2026));
        assertEquals(503, error.status().value());
        assertEquals("SCHOOL_BUSY", error.code());
        assertEquals(2, classVisits.get());
    }

    /** 短暂故障最多重试一次，保持原来的表单和学校会话。@author itsjony01 @date 2026-10-01 */
    @Test
    void recoversFromTransientAcademicFailure() {
        SchoolSsoClient client = loggedInClient();
        transientClassFailure = true;
        assertEquals("计科2301", client.classSchedulePage(2023, "2026-2027-1", "计科2301").selectFirst("nobr").text());
        assertEquals(2, classVisits.get());
        assertEquals("POST", classRequestMethod.get());
        assertTrue(classRequestBody.get().contains("xnxqh=2026-2027-1"));
        assertTrue(classRequestCookie.get().contains("JSESSIONID=school-session"));
        Map<String, String> fields = Arrays.stream(classRequestBody.get().split("&"))
                .map(value -> value.split("=", 2))
                .collect(Collectors.toMap(value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8),
                        value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8)));
        assertEquals("计科2301", fields.get("skbj"));
    }

    /** 尊重学校限流与恢复时间，不自动重复请求。@author itsjony01 @date 2026-10-01 */
    @Test
    void doesNotRetryRateLimitOrRetryAfter() {
        SchoolSsoClient client = loggedInClient();
        classStatus = 429;
        assertEquals("SCHOOL_BUSY", assertThrows(ProtocolException.class,
                () -> client.classSchedulePage(2023, "2026-2027-1", "计科2301")).code());
        assertEquals(1, classVisits.get());
        classStatus = 503;
        retryAfter = true;
        assertEquals("SCHOOL_BUSY", assertThrows(ProtocolException.class,
                () -> client.classSchedulePage(2023, "2026-2027-1", "计科2301")).code());
        assertEquals(2, classVisits.get());
    }

    /** 登录失效和页面格式异常不进入网络重试。@author itsjony01 @date 2026-10-01 */
    @Test
    void doesNotRetryExpiredSession() {
        SchoolSsoClient client = loggedInClient();
        classStatus = 401;
        assertEquals("SCHOOL_SESSION_UNVERIFIED", assertThrows(ProtocolException.class,
                () -> client.classSchedulePage(2023, "2026-2027-1", "计科2301")).code());
        assertEquals(1, classVisits.get());
    }

    /** 慢响应到达总预算后返回明确超时，不再耗时重试。@author itsjony01 @date 2026-10-01 */
    @Test
    void boundsAcademicWaitingTime() {
        SchoolSsoClient client = new SchoolSsoClient(origin, origin, Duration.ofMillis(200));
        classDelayMillis = 700;
        long started = System.nanoTime();
        assertEquals("SCHOOL_TIMEOUT", assertThrows(ProtocolException.class,
                () -> client.classSchedulePage(2023, "2026-2027-1", "计科2301")).code());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000);
        assertTrue(classVisits.get() <= 1);
    }

    private SchoolSsoClient loggedInClient() {
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare("student");
        client.login("student", "secret", "");
        return client;
    }

    /** 个人接口只提交日期并沿用当前登录 Cookie，不接收其他学号。@author itsjony01 @date 2026-10-01 */
    @Test
    void postsPersonalScheduleDateWithOwnSchoolCookie() {
        SchoolSsoClient client = loggedInClient();
        client.personalSchedulePage("2026-10-01");
        assertEquals("POST", classRequestMethod.get());
        assertEquals("rq=2026-10-01", classRequestBody.get());
        assertTrue(classRequestCookie.get().contains("JSESSIONID=school-session"));
        classResponse = "<script>window.location.href='/authserver/login'</script>";
        assertEquals(401, assertThrows(ProtocolException.class,
                () -> client.personalSchedulePage("2026-10-01")).status().value());
    }

    /** 教师目录和单人课表都走学校教师接口，仅姓名筛选不同。@author itsjony01 @date 2026-10-01 */
    @Test
    void postsTeacherScheduleFiltersWithOwnSchoolCookie() {
        SchoolSsoClient client = loggedInClient();
        assertEquals("mode", client.teacherScheduleOptionsPage().selectFirst("select[name=kbjcmsid] option").val());
        assertEquals("GET", classRequestMethod.get());
        assertTrue(classRequestCookie.get().contains("JSESSIONID=school-session"));

        assertEquals("张三", client.teacherSchedulePage("2026-2027-1", "mode", "", "college-a")
                .selectFirst("nobr").text());
        Map<String, String> directory = Arrays.stream(classRequestBody.get().split("&"))
                .map(value -> value.split("=", 2))
                .collect(Collectors.toMap(value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8),
                        value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8)));
        assertEquals("POST", classRequestMethod.get());
        assertEquals("2026-2027-1", directory.get("xnxqh"));
        assertEquals("mode", directory.get("kbjcmsid"));
        assertEquals("", directory.get("skjs"));
        assertEquals("college-a", directory.get("skyx"));
        assertTrue(classRequestCookie.get().contains("JSESSIONID=school-session"));

        client.teacherSchedulePage("2026-2027-1", "mode", "张三");
        assertTrue(classRequestBody.get().contains("skjs=%E5%BC%A0%E4%B8%89"));
    }

    /**
     * Only an authenticated profile belonging to the logged-in student supplies a display name.
     * @author itsjony01
     * @date 2026-10-01
     */
    @Test
    void readsVerifiedStudentName() {
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare("student");
        client.login("student", "secret", "");
        assertEquals("测试学生", client.realName("student"));
    }

    @Test
    void ignoresProfileWithDifferentAccount() {
        profileAccount = "another-student";
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare("student");
        client.login("student", "secret", "");
        assertEquals(null, client.realName("student"));
    }

    @Test
    void missingProfileDoesNotInventName() {
        missingProfile = true;
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare("student");
        client.login("student", "secret", "");
        assertEquals(null, client.realName("student"));
    }

    /**
     * 认证入口返回跳转不代表教务会话成功，缺少 SSO Cookie 时不得签发登录结果。
     * @author itsjony01
     * @date 2026-10-01
     */
    @Test
    void authenticationRedirectAloneIsNotSchoolLogin() {
        dropSsoCookie = true;
        ProtocolException error = rejectedCallback();
        assertEquals("SCHOOL_SESSION_UNVERIFIED", error.code());
        assertEquals(1, passwordPosts.get());
        assertEquals(0, protectedVisits.get());
    }

    @Test
    void exposesCaptchaOnlyWhenSchoolShowsIt() {
        requireCaptcha = true;
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare("student");
        assertTrue(client.captchaRequired());
        assertEquals("image", client.captchaType());
    }

    @Test
    void sliderCaptchaDoesNotFallBackToImageOrTryPassword() {
        requireCaptcha = true;
        sliderCaptcha = true;
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare("student");
        assertEquals("slider", client.captchaType());
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> client.login("student", "secret", ""));
        assertEquals(422, error.status().value());
    }

    @Test
    void rejectedCredentialsCannotCreateSession() {
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare();
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> client.login("student", "secret", "wrong"));
        assertEquals(401, error.status().value());
        assertEquals(0, protectedVisits.get());
    }

    @Test
    void anonymousJavascriptRedirectIsNotAuthentication() {
        anonymousMain = true;
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare();
        assertThrows(ProtocolException.class, () -> client.login("student", "secret", "1234"));
    }

    /**
     * 覆盖学校返回 401、凭证错误、验证码要求和真正服务故障的分流。
     * @author itsjony01
     * @date 2026-10-01
     */
    @Test
    void school401IsNotReportedAsGatewayFailure() {
        rejectedStatus = 401;
        ProtocolException error = rejectedLogin();
        assertEquals(401, error.status().value());
        assertEquals("SCHOOL_LOGIN_REJECTED", error.code());
    }

    @Test
    void schoolCredentialMessageHasSpecificCode() {
        rejectedStatus = 401;
        rejectedMessage = "您提供的用户名或者密码有误";
        assertEquals("SCHOOL_CREDENTIALS_REJECTED", rejectedLogin().code());
    }

    @Test
    void schoolVerificationMessageIsNotPasswordError() {
        rejectedStatus = 401;
        rejectedMessage = "图形动态码错误";
        ProtocolException error = rejectedLogin();
        assertEquals(422, error.status().value());
        assertEquals("SCHOOL_VERIFICATION_REQUIRED", error.code());
    }

    @Test
    void expiredFormIsNotPasswordError() {
        rejectedStatus = 401;
        rejectedMessage = "登录凭证不可用";
        assertEquals("SCHOOL_FORM_EXPIRED", rejectedLogin().code());
    }

    @Test
    void schoolServerErrorRemainsGatewayFailure() {
        rejectedStatus = 503;
        ProtocolException error = rejectedLogin();
        assertEquals(502, error.status().value());
        assertEquals("SCHOOL_LOGIN_UNAVAILABLE", error.code());
    }

    private ProtocolException rejectedLogin() {
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare("student");
        return assertThrows(ProtocolException.class, () -> client.login("student", "secret", "wrong"));
    }

    @Test
    void passwordEncryptionAddsRandomPrefix() {
        String first = SchoolSsoClient.encrypt("secret", SALT);
        String second = SchoolSsoClient.encrypt("secret", SALT);
        assertFalse(first.equals(second));
        assertEquals("secret", decrypt(first));
        assertEquals("secret", decrypt(second));
    }

    /**
     * 回跳仅升级学校原域名，URL 中的学校会话与票据保持原样。
     * @author itsjony01
     * @date 2026-10-01
     */
    @Test
    void upgradesRewrittenSchoolCallbacksWithoutLosingParameters() {
        SchoolSsoClient client = new SchoolSsoClient(URI.create("https://auth.example"), URI.create("https://jw.example"));
        URI callbackUri = URI.create("http://jw.example/jsxsd/sso.jsp;jsessionid=fixture?ticket=fixture%2Bticket");
        assertEquals("https://jw.example/jsxsd/sso.jsp;jsessionid=fixture?ticket=fixture%2Bticket",
                client.schoolRequestUri(callbackUri).toString());
        assertEquals("https://jw.example/jsxsd/framework/xsMain.jsp",
                client.schoolRequestUri(URI.create("http://jw.example:80/jsxsd/framework/xsMain.jsp")).toString());
    }

    @Test
    void rejectsExternalAndNonSchoolHttpRedirects() {
        SchoolSsoClient client = new SchoolSsoClient(URI.create("https://auth.example"), URI.create("https://jw.example"));
        for (String target : java.util.List.of("https://evil.example/jsxsd/", "http://jw.example.evil/jsxsd/",
                "http://jw.example:8080/jsxsd/", "http://user@jw.example/jsxsd/", "http://jw.example/other/",
                "http://jw.example/jsxsd/../other/", "http://auth.example/authserver/login")) {
            ProtocolException error = assertThrows(ProtocolException.class,
                    () -> client.schoolRequestUri(URI.create(target)));
            assertEquals("SCHOOL_REDIRECT_BLOCKED", error.code());
        }
    }

    @Test
    void followsSchoolSessionRewriting() {
        callback = "/jsxsd/sso.jsp;jsessionid=fixture";
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare("student");
        client.login("student", "secret", "");
        assertEquals(2, protectedVisits.get());
    }

    @Test
    void rejectsUnknownCallbackWithoutSendingTicket() {
        callback = "https://unexpected.example/jsxsd/?ticket=fixture-secret";
        ProtocolException error = rejectedCallback();
        assertEquals("SCHOOL_REDIRECT_BLOCKED", error.code());
        assertFalse(error.getMessage().contains("fixture-secret"));
        assertEquals(0, protectedVisits.get());
    }

    private ProtocolException rejectedCallback() {
        SchoolSsoClient client = new SchoolSsoClient(origin, origin);
        client.prepare("student");
        return assertThrows(ProtocolException.class, () -> client.login("student", "secret", ""));
    }

    private static String decrypt(String encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SALT.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(new byte[16]));
            byte[] clear = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            return new String(clear, StandardCharsets.UTF_8).substring(64);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static void reply(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void replyJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
