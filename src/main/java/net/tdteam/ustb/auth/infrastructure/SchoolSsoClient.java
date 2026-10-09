package net.tdteam.ustb.auth.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.tdteam.ustb.common.error.ProtocolException;

/**
 * 北京科技大学天津学院SSO认证客户端（连接至教务）
 *
 * @author itsjony01
 * @date 2026-09-30
 */
public class SchoolSsoClient {
    private static final Logger LOG = LoggerFactory.getLogger(SchoolSsoClient.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
    private static final Duration ACADEMIC_BUDGET = Duration.ofSeconds(35);
    private final URI authOrigin;
    private final URI schoolOrigin;
    private final URI loginUri;
    private final URI mainUri;
    private final URI profileUri;
    private final URI schoolLoginUri;
    private final HttpClient client;
    private final Duration academicBudget;
    private String salt;
    private String execution;
    private String lt;
    private boolean captchaRequired;
    private String captchaType = "none";

    public SchoolSsoClient() {
        //jw开头的这个网站我没有登录成功过，但是我看贝壳小盒子用到了这个，那我也创造一个session吧~
        this(URI.create("https://authserver.tjustb.cn"), URI.create("https://jw.tjustb.cn"),
                URI.create("http://jw.tjustb.cn/jsxsd/"));
    }

    //test111
    SchoolSsoClient(URI authOrigin, URI schoolOrigin) {
        this(authOrigin, schoolOrigin, schoolOrigin.resolve("/jsxsd/"));
    }

    //避免验证慢响应及确保重定向不会无限等待
    SchoolSsoClient(URI authOrigin, URI schoolOrigin, Duration academicBudget) {
        this(authOrigin, schoolOrigin, schoolOrigin.resolve("/jsxsd/"), academicBudget);
    }

    private SchoolSsoClient(URI authOrigin, URI schoolOrigin, URI serviceUri) {
        this(authOrigin, schoolOrigin, serviceUri, ACADEMIC_BUDGET);
    }

    private SchoolSsoClient(URI authOrigin, URI schoolOrigin, URI serviceUri, Duration academicBudget) {
        this.academicBudget = academicBudget;
        this.authOrigin = authOrigin;
        this.schoolOrigin = schoolOrigin;

        this.loginUri = authOrigin.resolve("/authserver/login");
        this.schoolLoginUri = authOrigin.resolve("/authserver/login?service=" + encode(serviceUri.toString()));
        this.mainUri = schoolOrigin.resolve("/jsxsd/framework/xsMain.jsp");
        //xsxx 学生xx jsxx 我猜是教授的东西 因为我访问不了jskb只能访问xskb 这命名神了 到底是外包的还是学校自己人写的史山代码啊。
        this.profileUri = schoolOrigin.resolve("/jsxsd/grxx/xsxx");
        this.client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(8)).build();
    }

    public void prepare() {
        prepare(null);
    }

    public void prepare(String account) {
        HttpResponse<byte[]> response = follow(get(loginUri), 10);
        if (response.statusCode() != 200 || !samePage(response.uri(), loginUri)) {
            throw upstream();
        }
        Document page = document(response);
        Element passwordForm = page.selectFirst("#pwdFromId");
        if (passwordForm == null) throw upstream();
        //盐
        salt = value(passwordForm, "#pwdEncryptSalt");
        execution = value(passwordForm, "input[name=execution]");
        lt = value(passwordForm, "input[name=lt]");
        if (salt.isBlank() || execution.isBlank()) {
            throw upstream();
        }
        String captchaSwitch = scriptValue(page, "captchaSwitch");
        boolean required = "0".equals(scriptValue(page, "_badCredentialsCount"));
        if (!required && account != null && !account.isBlank()) {
            //神了 只有多次请求才需要人机验证
            HttpResponse<byte[]> policy = follow(get(authOrigin.resolve(
                    "/authserver/checkNeedCaptcha.htl?username=" + encode(account))), 3);
            if (policy.statusCode() != 200) throw upstream();
            try {
                JsonNode needed = new ObjectMapper().readTree(policy.body()).path("isNeed");
                if (!needed.isBoolean()) throw upstream();
                required = needed.booleanValue();
            } catch (IOException exception) {
                throw upstream();
            }
        }
        captchaRequired = required && !"0".equals(captchaSwitch);
        captchaType = !captchaRequired ? "none" : "1".equals(captchaSwitch) ? "image" : "slider";
    }

    public boolean captchaRequired() {
        return captchaRequired;
    }

    public String captchaType() {
        return captchaType;
    }

    public byte[] captcha() {
        if (!"image".equals(captchaType)) {
            throw new ProtocolException(HttpStatus.CONFLICT, "Image captcha is not requested by school");
        }
        HttpResponse<byte[]> response = follow(get(authOrigin.resolve("/authserver/getCaptcha.htl")), 3);
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (response.statusCode() != 200 || !type.toLowerCase().startsWith("image/") || response.body().length > 1_000_000) {
            throw upstream();
        }
        return response.body();
    }

    public void login(String account, String password, String captcha) {
        if (salt == null || execution == null) {
            throw new IllegalStateException("Prepare the challenge before logging in");
        }
        if ("slider".equals(captchaType)) {
            throw new ProtocolException(HttpStatus.UNPROCESSABLE_ENTITY, "SCHOOL_VERIFICATION_REQUIRED",
                    "School requires interactive verification");
        }
        if (captchaRequired && (captcha == null || captcha.isBlank())) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "School captcha required");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", account);
        form.put("password", encrypt(password, salt));
        form.put("captcha", captcha == null ? "" : captcha);
        form.put("_eventId", "submit");
        //我想知道学校的程序员怎么想的，这个命名规范看的我要吐了。
        form.put("cllt", "userNameLogin");
        form.put("dllt", "generalLogin");
        form.put("lt", lt);
        form.put("execution", execution);
        String body = form.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        salt = null;
        execution = null;
        HttpRequest request = HttpRequest.newBuilder(loginUri).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", authOrigin.toString()).header("Referer", loginUri.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> authentication = send(request);
        if (authentication.statusCode() < 300 || authentication.statusCode() >= 400) {
            boolean successPage = authentication.statusCode() == 200
                    && document(authentication).selectFirst("#pwdFromId, input[name=password]") == null;
            if (!successPage) validateLoginResponse(authentication);
        }
        establishSchoolSession();
    }


    //建立与教务的会话
    private void establishSchoolSession() {
        //登录
        HttpResponse<byte[]> result = follow(get(schoolLoginUri), 10);
        if (samePage(result.uri(), loginUri)) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_SESSION_UNVERIFIED",
                    "School SSO session was not established");
        }
        //只能访问 /jsxsd/ 在登录之前
        if (!sameOrigin(result.uri(), schoolOrigin)
                || !result.uri().getPath().startsWith("/jsxsd/")) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_REDIRECT_INVALID",
                    "School did not redirect to the teaching system");
        }
        HttpResponse<byte[]> verified = follow(get(mainUri), 10);
        if (verified.statusCode() != 200 || !samePage(verified.uri(), mainUri)
                || !authenticatedPage(verified)) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_SESSION_UNVERIFIED",
                    "School session could not be verified");
        }
    }


    //获取真实姓名
    public String realName(String account) {
        HttpResponse<byte[]> response = follow(get(profileUri), 10);
        if (response.statusCode() != 200 || !samePage(response.uri(), profileUri)) return null;
        Document page = document(response);
        if (!account.equals(profileValue(page, "学号"))) return null;
        String name = profileValue(page, "姓名");
        return name != null && name.matches("[\\p{L}·•\\s]{2,40}") ? name : null;
    }

    /**
     * 实际访问学校教务主页检测当前 Cookie，而不是只判断本地工具箱令牌。
     *
     * @author itsjony01
     * @date 2026-10-04
     */
    public void verifySession() {
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        HttpResponse<byte[]> response = follow(get(mainUri), 5, deadline);
        if (samePage(response.uri(), loginUri) || response.statusCode() == 401 || response.statusCode() == 403
                || (response.statusCode() == 200 && samePage(response.uri(), mainUri)
                        && !authenticatedPage(response))) {
            throw new ProtocolException(HttpStatus.UNAUTHORIZED, "SCHOOL_SESSION_UNVERIFIED",
                    "School SSO session expired");
        }
        if (response.statusCode() == 429 || response.statusCode() == 503) {
            throw new ProtocolException(HttpStatus.SERVICE_UNAVAILABLE, "SCHOOL_BUSY", "School service is busy");
        }
        if (response.statusCode() != 200 || !samePage(response.uri(), mainUri)) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_SSO_CHECK_UNAVAILABLE",
                    "School SSO status could not be checked");
        }
    }

    /**
     * 使用现有教务 Cookie 按年级提交班级课表查询，拒绝认证页和异常回跳。
     * @author itsjony01
     * @date 2026-10-01
     */
    public Document classDirectoryPage(int grade) {
        return classDirectoryPage(grade, "");
    }

    /**
     * 可指定该年级入学学期，补齐当前学期无课表的在校班级。
     * @author itsjony01
     * @date 2026-10-01
     */
    public Document classDirectoryPage(int grade, String semester) {
        return classTablePage(grade, semester, "");
    }

    /**
     * 复用学校查询页面的表单：目录才允许班级为空，课表提交 skbj 精确缩小查询范围。
     * @author itsjony01
     * @date 2026-10-01
     */
    private Document classTablePage(int grade, String semester, String className) {
        URI uri = schoolOrigin.resolve("/jsxsd/kbcx/kbxx_xzb_ifr");
        Map<String, String> form = new LinkedHashMap<>();
        for (String key : new String[] {"xnxqh", "kbjcmsid", "skyx", "sknj", "skzy", "skbjid",
                "skbj", "zc1", "zc2", "skxq1", "skxq2"}) {
            form.put(key, "");
        }
        form.put("sknj", Integer.toString(grade));
        form.put("xnxqh", semester);
        form.put("skbj", className);
        String body = form.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", schoolOrigin.resolve("/jsxsd/kbcx/kbxx_xzb").toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return academicPage(request);
    }

    /** 学期和节次选项直接读取学校查询页面。@author itsjony01 @date 2026-10-01 */
    public Document classScheduleOptionsPage() {
        URI uri = schoolOrigin.resolve("/jsxsd/kbcx/kbxx_xzb");
        return academicPage(get(uri));
    }

    /** 学校教师课表筛选项。@author itsjony01 @date 2026-10-01 */
    public Document teacherScheduleOptionsPage() {
        return academicPage(get(schoolOrigin.resolve("/jsxsd/kbcx/kbxx_teacher")));
    }

    /** 学校教师课表；姓名为空时必须按学校院系筛选，避免全校课表请求超时。@author itsjony01 @date 2026-10-01 */
    public Document teacherSchedulePage(String semester, String timeModel, String teacherName) {
        return teacherSchedulePage(semester, timeModel, teacherName, "");
    }

    public Document teacherSchedulePage(String semester, String timeModel, String teacherName, String college) {
        URI uri = schoolOrigin.resolve("/jsxsd/kbcx/kbxx_teacher_ifr");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("xnxqh", semester);
        form.put("kbjcmsid", timeModel);
        form.put("skyx", college);
        form.put("jszc", "");
        form.put("skbjid", "");
        form.put("skjs", teacherName);
        for (String key : new String[] {"zc1", "zc2", "skxq1", "skxq2", "jc1", "jc2"}) form.put(key, "");
        String body = form.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", schoolOrigin.resolve("/jsxsd/kbcx/kbxx_teacher").toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return academicPage(request);
    }

    /**
     * 查询当前登录学生指定日期所在周的个人课表，学校原生接口使用 rq 参数。
     * @author itsjony01
     * @date 2026-10-01
     */
    public Document personalSchedulePage(String date) {
        URI uri = schoolOrigin.resolve("/jsxsd/framework/main_index_loadkb.jsp");
        String body = "rq=" + encode(date);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", schoolOrigin.resolve("/jsxsd/framework/xsMain_new.jsp?t1=1").toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return academicPage(request);
    }

    /**
     * 仅只读教务查询允许一次短暂故障重试；登录、限流、超时和解析错误不自动重试。
     * 重定向与重试共用时间预算，避免前端已超时后服务端仍长时间重复请求。
     * @author itsjony01
     * @date 2026-10-01
     */
    private Document academicPage(HttpRequest request) {
        long deadline = System.nanoTime() + academicBudget.toNanos();
        for (int attempt = 1; attempt <= 2; attempt++) {
            long started = System.nanoTime();
            boolean retry;
            try {
                HttpResponse<byte[]> response = follow(request, 5, deadline);
                LOG.info("School academic response: path={}, attempt={}, status={}, elapsedMs={}, bytes={}",
                        request.uri().getPath(), attempt, response.statusCode(),
                        Duration.ofNanos(System.nanoTime() - started).toMillis(), response.body().length);
                retry = attempt == 1 && (response.statusCode() == 502 || response.statusCode() == 503
                        || response.statusCode() == 504) && response.headers().firstValue("Retry-After").isEmpty()
                        && deadline - System.nanoTime() > Duration.ofSeconds(5).toNanos();
                if (!retry) return verifiedAcademicPage(response, request.uri());
            } catch (ProtocolException exception) {
                LOG.warn("School academic failure: path={}, attempt={}, code={}, elapsedMs={}",
                        request.uri().getPath(), attempt, exception.code(),
                        Duration.ofNanos(System.nanoTime() - started).toMillis());
                retry = attempt == 1 && "SCHOOL_TRANSPORT_FAILED".equals(exception.code())
                        && deadline - System.nanoTime() > Duration.ofSeconds(5).toNanos();
                if (!retry) throw exception;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_REQUEST_INTERRUPTED", "School request interrupted");
            }
        }
        throw new IllegalStateException("Academic request attempts exhausted");
    }

    private Document verifiedAcademicPage(HttpResponse<byte[]> response, URI uri) {
        if (samePage(response.uri(), loginUri) || response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ProtocolException(HttpStatus.UNAUTHORIZED, "SCHOOL_SESSION_UNVERIFIED",
                    "School session expired");
        }
        if (response.statusCode() == 429 || response.statusCode() == 503) {
            throw new ProtocolException(HttpStatus.SERVICE_UNAVAILABLE, "SCHOOL_BUSY", "School service is busy");
        }
        if (response.statusCode() != 200 || !samePage(response.uri(), uri)) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_CLASS_DIRECTORY_UNAVAILABLE",
                    "School class directory could not be loaded");
        }
        Document page = document(response);
        if (page.selectFirst("#pwdFromId, #loginFromId") != null
                || page.select("script").stream().anyMatch(script -> script.data().contains("/authserver/login"))) {
            throw new ProtocolException(HttpStatus.UNAUTHORIZED, "SCHOOL_SESSION_UNVERIFIED",
                    "School session expired");
        }
        return page;
    }

    /**
     * 使用 kbxx_xzb 查询页面的班级筛选参数，提交到实际课表 iframe 接口。
     * 学校按名称筛选后仍校验返回班级，不将相近名称或旧课表作为兜底。
     * @author itsjony01
     * @date 2026-10-01
     */
    public Document classSchedulePage(int grade, String semester, String className) {
        if (className == null || className.isBlank()) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "CLASS_NOT_FOUND", "Class name is required");
        }
        return classTablePage(grade, semester, className);
    }

    /**
     * Reads adjacent table cells or inline field values without matching across unrelated HTML.
     * @author itsjony01
     * @date 2026-10-01
     */
    private static String profileValue(Document page, String label) {
        for (Element cell : page.select("td, th")) {
            String text = cell.text().trim();
            if (text.matches(Pattern.quote(label) + "[：:]?")) {
                Element value = cell.nextElementSibling();
                if (value != null && (value.tagName().equals("td") || value.tagName().equals("th"))) {
                    Element input = value.selectFirst("input[value]");
                    return (input == null ? value.text() : input.val()).trim();
                }
            }
            if (text.startsWith(label + "：") || text.startsWith(label + ":")) {
                String inline = text.substring(label.length() + 1).trim();
                if (!inline.isBlank()) return inline;
            }
        }
        return null;
    }

    /**
     * School login errors can arrive as HTTP 200 or 401; preserve their distinct causes.
     * @author itsjony01
     * @date 2026-10-01
     */
    private void validateLoginResponse(HttpResponse<byte[]> response) {
        if (!samePage(response.uri(), loginUri)) return;
        int status = response.statusCode();
        if (status >= 500) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_LOGIN_UNAVAILABLE",
                    "School login endpoint returned a server error");
        }
        if (status != 200 && status != 401 && status != 403) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_LOGIN_RESPONSE_INVALID",
                    "Unexpected school login response");
        }
        Document page = document(response);
        String text = page.text();
        if (text.contains("图形动态码错误") || text.contains("验证码错误") || text.contains("滑块验证")) {
            throw new ProtocolException(HttpStatus.UNPROCESSABLE_ENTITY, "SCHOOL_VERIFICATION_REQUIRED",
                    "School requires verification");
        }
        if (text.contains("登录凭证不可用")) {
            throw new ProtocolException(HttpStatus.BAD_REQUEST, "SCHOOL_FORM_EXPIRED",
                    "School login form expired");
        }
        if (text.contains("该账号非常用账号或用户名密码有误") || text.contains("您提供的用户名或者密码有误")
                || text.contains("用户名或密码错误") || text.contains("用户名或密码有误")) {
            throw new ProtocolException(HttpStatus.UNAUTHORIZED, "SCHOOL_CREDENTIALS_REJECTED",
                    "School rejected the account or password");
        }
        throw new ProtocolException(HttpStatus.UNAUTHORIZED, "SCHOOL_LOGIN_REJECTED",
                "School rejected the login request");
    }

    static String encrypt(String password, String salt) {
        try {
            byte[] iv = randomChars(16).getBytes(StandardCharsets.US_ASCII);
            byte[] key = salt.trim().getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] data = (randomChars(64) + password).getBytes(StandardCharsets.UTF_8);
            return Base64.getEncoder().encodeToString(cipher.doFinal(data));
        } catch (Exception exception) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "Unsupported school password encryption");
        }
    }

    private HttpRequest get(URI uri) {
        uri = schoolRequestUri(uri);
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build();
    }

    /**
     * 仅将学校教务路径的 HTTP 回跳升级为 HTTPS，保留票据和 URL 会话参数。
     * @author itsjony01
     * @date 2026-10-01
     */
    URI schoolRequestUri(URI uri) {
        if ("http".equalsIgnoreCase(uri.getScheme()) && "https".equalsIgnoreCase(schoolOrigin.getScheme())
                && schoolOrigin.getHost().equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 80)
                && uri.normalize().getPath().startsWith("/jsxsd/")) {
            uri = schoolOrigin.resolve(uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        }
        if (!allowed(uri)) {
            throw redirectFailure("SCHOOL_REDIRECT_BLOCKED", "School redirected outside the allowed origins");
        }
        return uri;
    }

    /**
     * 只记录固定原因码，日志与响应均不包含回跳 URL、票据或学校 Cookie。
     * @author itsjony01
     * @date 2026-10-01
     */
    private ProtocolException redirectFailure(String code, String message) {
        LOG.warn("School redirect failed: code={}", code);
        return new ProtocolException(HttpStatus.BAD_GATEWAY, code, message);
    }

    private HttpResponse<byte[]> follow(HttpRequest request, int limit) {
        return follow(request, limit, null);
    }

    /** 为只读教务链路限制总等待时间，其余认证链路保持原来的单次超时。@author itsjony01 @date 2026-10-01 */
    private HttpResponse<byte[]> follow(HttpRequest request, int limit, Long deadline) {
        HttpRequest current = request;
        for (int i = 0; i < limit; i++) {
            if (deadline != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw schoolTimeout();
                Duration timeout = Duration.ofNanos(Math.min(remaining,
                        current.timeout().orElse(Duration.ofSeconds(30)).toNanos()));
                current = HttpRequest.newBuilder(current, (name, value) -> true).timeout(timeout).build();
            }
            URI requested = current.uri();
            HttpResponse<byte[]> response = send(current);
            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return response;
            }
            String location = response.headers().firstValue("Location").filter(value -> !value.isBlank())
                    .orElseThrow(() -> redirectFailure("SCHOOL_REDIRECT_INVALID", "School redirect has no location"));
            URI next;
            try {
                next = requested.resolve(location);
            } catch (IllegalArgumentException exception) {
                throw redirectFailure("SCHOOL_REDIRECT_INVALID", "School redirect location is invalid");
            }
            current = get(next);
        }
        throw redirectFailure("SCHOOL_REDIRECT_LIMIT", "School redirect limit exceeded");
    }

  
    private HttpResponse<byte[]> send(HttpRequest request) {
        if (!allowed(request.uri())) {
            throw redirectFailure("SCHOOL_REDIRECT_BLOCKED", "School redirected outside the allowed origins");
        }
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            LOG.debug("School request: method={}, scheme={}, host={}, status={}", request.method(),
                    request.uri().getScheme(), request.uri().getHost(), response.statusCode());
            return response;
        } catch (HttpTimeoutException exception) {
            throw schoolTimeout();
        } catch (IOException exception) {
            LOG.warn("School transport failed: method={}, host={}, cause={}", request.method(),
                    request.uri().getHost(), exception.getClass().getSimpleName());
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_TRANSPORT_FAILED", "Connection to school failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "SCHOOL_REQUEST_INTERRUPTED", "School request interrupted");
        }
    }

    private static ProtocolException schoolTimeout() {
        return new ProtocolException(HttpStatus.GATEWAY_TIMEOUT, "SCHOOL_TIMEOUT", "School request timed out");
    }

    private boolean allowed(URI uri) {
        return sameOrigin(uri, authOrigin) || (sameOrigin(uri, schoolOrigin)
                && uri.normalize().getPath().startsWith("/jsxsd/"));
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost() != null && first.getHost().equalsIgnoreCase(second.getHost())
                && first.getPort() == second.getPort() && first.getUserInfo() == null;
    }

    private static boolean samePage(URI first, URI second) {
        return sameOrigin(first, second) && first.getPath().replaceAll("(?i);jsessionid=[^/;]*", "")
                .equals(second.getPath().replaceAll("(?i);jsessionid=[^/;]*", ""));
    }

    private static Document document(HttpResponse<byte[]> response) {
        try {
            return Jsoup.parse(new ByteArrayInputStream(response.body()), null, response.uri().toString());
        } catch (IOException exception) {
            throw new ProtocolException(HttpStatus.BAD_GATEWAY, "School returned an invalid page");
        }
    }

    private static String value(Element page, String selector) {
        Element element = page.selectFirst(selector);
        return element == null ? "" : element.val();
    }

   //太复杂了 懒得整了（
    private static String scriptValue(Document page, String name) {
        var matcher = Pattern.compile("var\\s+" + Pattern.quote(name) + "\\s*=\\s*[\"']([^\"']*)[\"']")
                .matcher(page.select("script").stream().map(Element::data).collect(java.util.stream.Collectors.joining("\n")));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean authenticatedPage(HttpResponse<byte[]> response) {
        Document page = document(response);
        // Anonymous xsMain.jsp answers HTTP 200 with a JavaScript SSO redirect.
        return page.selectFirst("#loginFromId") == null
                && !page.html().toLowerCase().contains("/authserver/login")
                && page.title() != null && !page.title().isBlank();
    }

    private static String randomChars(int size) {
        StringBuilder text = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            text.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return text.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private ProtocolException upstream() {
        return new ProtocolException(HttpStatus.BAD_GATEWAY, "School authentication service is unavailable");
    }
}
