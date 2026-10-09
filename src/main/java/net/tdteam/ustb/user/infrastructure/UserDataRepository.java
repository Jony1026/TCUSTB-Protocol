package net.tdteam.ustb.user.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import net.tdteam.ustb.academic.model.PersonalScheduleResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQLite 用户数据仓库，保存微信用户、会话、教务绑定与个人课表快照。
 *
 * @author itsjony01
 * @date 2026-10-06
 */
@Repository
public class UserDataRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String databasePath;

    public UserDataRepository(JdbcTemplate jdbc, ObjectMapper mapper,
                              @Value("${app.database.path:data/tcustb.sqlite}") String databasePath) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.databasePath = databasePath;
    }

    /** 初始化轻量数据库结构，所有时间统一保存为 UTC ISO-8601。@author itsjony01 @date 2026-10-06 */
    @PostConstruct
    public void initialize() {
        createDatabaseDirectory();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS wechat_users (
                    open_id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL UNIQUE,
                    nickname TEXT NOT NULL,
                    avatar_content_type TEXT,
                    avatar_data BLOB,
                    avatar_updated_at TEXT,
                    profile_completed INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        addColumnIfMissing("wechat_users", "avatar_content_type", "TEXT");
        addColumnIfMissing("wechat_users", "avatar_data", "BLOB");
        addColumnIfMissing("wechat_users", "avatar_updated_at", "TEXT");
        addColumnIfMissing("wechat_users", "profile_completed", "INTEGER NOT NULL DEFAULT 0");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS wechat_sessions (
                    token_hash TEXT PRIMARY KEY,
                    open_id TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (open_id) REFERENCES wechat_users(open_id) ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS academic_bindings (
                    open_id TEXT PRIMARY KEY,
                    account TEXT NOT NULL,
                    real_name TEXT,
                    bound_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (open_id) REFERENCES wechat_users(open_id) ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS personal_schedule_snapshots (
                    open_id TEXT NOT NULL,
                    week_start TEXT NOT NULL,
                    requested_date TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    saved_at TEXT NOT NULL,
                    PRIMARY KEY (open_id, week_start),
                    FOREIGN KEY (open_id) REFERENCES wechat_users(open_id) ON DELETE CASCADE
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_wechat_sessions_expires_at ON wechat_sessions(expires_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_schedule_saved_at ON personal_schedule_snapshots(open_id, saved_at)");
    }

    /** 在首次建立 SQLite 连接前创建数据库父目录。@author itsjony01 @date 2026-10-06 */
    private void createDatabaseDirectory() {
        Path path = Path.of(databasePath).toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create SQLite database directory: " + parent, exception);
        }
    }

    /** 为已有 SQLite 数据库补充新字段，升级时保留原用户数据。@author itsjony01 @date 2026-10-06 */
    private void addColumnIfMissing(String table, String column, String definition) {
        boolean exists = jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(row -> column.equalsIgnoreCase(String.valueOf(row.get("name"))));
        if (!exists) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    /** 微信登录时创建用户，已存在用户只更新时间并保留昵称。@author itsjony01 @date 2026-10-06 */
    public WechatUser upsertWechatUser(String openId, String userId, String defaultNickname, Instant now) {
        jdbc.update("""
                INSERT INTO wechat_users(open_id, user_id, nickname, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(open_id) DO UPDATE SET user_id = excluded.user_id, updated_at = excluded.updated_at
                """, openId, userId, defaultNickname, now.toString(), now.toString());
        return findWechatUser(openId).orElseThrow();
    }

    public Optional<WechatUser> findWechatUser(String openId) {
        List<WechatUser> users = jdbc.query("SELECT open_id, user_id, nickname, avatar_updated_at, profile_completed FROM wechat_users WHERE open_id = ?",
                (result, row) -> new WechatUser(result.getString("open_id"), result.getString("user_id"),
                        result.getString("nickname"), nullableInstant(result.getString("avatar_updated_at")),
                        result.getInt("profile_completed") == 1), openId);
        return users.stream().findFirst();
    }

    public void updateNickname(String openId, String nickname, Instant now) {
        jdbc.update("UPDATE wechat_users SET nickname = ?, updated_at = ? WHERE open_id = ?",
                nickname, now.toString(), openId);
    }

    /** 保存昵称和资料完善状态，已完成后不会被后续登录重置。@author itsjony01 @date 2026-10-06 */
    public void updateProfile(String openId, String nickname, boolean profileCompleted, Instant now) {
        jdbc.update("""
                UPDATE wechat_users SET nickname = ?,
                    profile_completed = CASE WHEN ? THEN 1 ELSE profile_completed END, updated_at = ?
                WHERE open_id = ?
                """, nickname, profileCompleted, now.toString(), openId);
    }

    /** 保存用户主动选择的微信头像，头像跟随微信 openId 持久化。@author itsjony01 @date 2026-10-06 */
    public void saveWechatAvatar(String openId, String contentType, byte[] data, Instant now) {
        jdbc.update("""
                UPDATE wechat_users SET avatar_content_type = ?, avatar_data = ?, avatar_updated_at = ?,
                    profile_completed = 1, updated_at = ?
                WHERE open_id = ?
                """, contentType, data, now.toString(), now.toString(), openId);
    }

    public Optional<WechatAvatar> findWechatAvatar(String userId) {
        List<WechatAvatar> avatars = jdbc.query("""
                        SELECT avatar_content_type, avatar_data, avatar_updated_at FROM wechat_users
                        WHERE user_id = ? AND avatar_data IS NOT NULL
                        """, (result, row) -> new WechatAvatar(result.getString("avatar_content_type"),
                        result.getBytes("avatar_data"), Instant.parse(result.getString("avatar_updated_at"))), userId);
        return avatars.stream().findFirst();
    }

    /** 仅保存令牌摘要，数据库泄露时不能直接冒用登录令牌。@author itsjony01 @date 2026-10-06 */
    public void saveWechatSession(String accessToken, String openId, Instant expiresAt, Instant now) {
        jdbc.update("INSERT INTO wechat_sessions(token_hash, open_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
                tokenHash(accessToken), openId, expiresAt.toString(), now.toString());
    }

    public Optional<WechatSession> findWechatSession(String accessToken, Instant now) {
        if (accessToken == null || accessToken.isBlank()) return Optional.empty();
        List<WechatSession> sessions = jdbc.query("""
                        SELECT s.open_id, u.user_id, u.nickname, s.expires_at, u.avatar_updated_at, u.profile_completed
                        FROM wechat_sessions s JOIN wechat_users u ON u.open_id = s.open_id
                        WHERE s.token_hash = ? AND s.expires_at > ?
                        """, (result, row) -> new WechatSession(result.getString("open_id"),
                        result.getString("user_id"), result.getString("nickname"),
                        Instant.parse(result.getString("expires_at")),
                        nullableInstant(result.getString("avatar_updated_at")),
                        result.getInt("profile_completed") == 1), tokenHash(accessToken), now.toString());
        return sessions.stream().findFirst();
    }

    public void deleteWechatSession(String accessToken) {
        if (accessToken != null && !accessToken.isBlank()) {
            jdbc.update("DELETE FROM wechat_sessions WHERE token_hash = ?", tokenHash(accessToken));
        }
    }

    public void purgeExpiredWechatSessions(Instant now) {
        jdbc.update("DELETE FROM wechat_sessions WHERE expires_at <= ?", now.toString());
    }

    /** 教务绑定只保存学号和姓名，不保存统一认证密码。@author itsjony01 @date 2026-10-06 */
    public void saveAcademicBinding(String openId, String account, String realName, Instant now) {
        jdbc.update("""
                INSERT INTO academic_bindings(open_id, account, real_name, bound_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(open_id) DO UPDATE SET account = excluded.account,
                    real_name = excluded.real_name, updated_at = excluded.updated_at
                """, openId, account, realName, now.toString(), now.toString());
    }

    public Optional<AcademicBinding> findAcademicBinding(String openId) {
        List<AcademicBinding> bindings = jdbc.query("""
                        SELECT account, real_name, bound_at, updated_at FROM academic_bindings WHERE open_id = ?
                        """, (result, row) -> new AcademicBinding(result.getString("account"),
                        result.getString("real_name"), Instant.parse(result.getString("bound_at")),
                        Instant.parse(result.getString("updated_at"))), openId);
        return bindings.stream().findFirst();
    }

    /** 每个教学周只保留最新完整快照，刷新成功后原子覆盖旧数据。@author itsjony01 @date 2026-10-06 */
    public void savePersonalSchedule(String openId, LocalDate weekStart, String requestedDate,
                                     PersonalScheduleResponse response, Instant savedAt) {
        try {
            jdbc.update("""
                    INSERT INTO personal_schedule_snapshots(open_id, week_start, requested_date, payload_json, saved_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(open_id, week_start) DO UPDATE SET requested_date = excluded.requested_date,
                        payload_json = excluded.payload_json, saved_at = excluded.saved_at
                    """, openId, weekStart.toString(), requestedDate, mapper.writeValueAsString(response), savedAt.toString());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Personal schedule could not be serialized", exception);
        }
    }

    public Optional<ScheduleSnapshot> findPersonalSchedule(String openId, LocalDate weekStart) {
        List<ScheduleSnapshot> snapshots = jdbc.query("""
                        SELECT payload_json, saved_at FROM personal_schedule_snapshots
                        WHERE open_id = ? AND week_start = ?
                        """, (result, row) -> new ScheduleSnapshot(readSchedule(result.getString("payload_json")),
                        Instant.parse(result.getString("saved_at"))), openId, weekStart.toString());
        return snapshots.stream().findFirst();
    }

    public Optional<Instant> latestScheduleSavedAt(String openId) {
        List<String> values = jdbc.query("""
                        SELECT saved_at FROM personal_schedule_snapshots WHERE open_id = ?
                        ORDER BY saved_at DESC LIMIT 1
                        """, (result, row) -> result.getString("saved_at"), openId);
        return values.stream().findFirst().map(Instant::parse);
    }

    private PersonalScheduleResponse readSchedule(String json) {
        try {
            return mapper.readValue(json, PersonalScheduleResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored personal schedule is invalid", exception);
        }
    }

    private static String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Instant nullableInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    public record WechatUser(String openId, String userId, String nickname, Instant avatarUpdatedAt,
                             boolean profileCompleted) {
    }

    public record WechatSession(String openId, String userId, String nickname, Instant expiresAt,
                                Instant avatarUpdatedAt, boolean profileCompleted) {
    }

    public record WechatAvatar(String contentType, byte[] data, Instant updatedAt) {
    }

    public record AcademicBinding(String account, String realName, Instant boundAt, Instant updatedAt) {
    }

    public record ScheduleSnapshot(PersonalScheduleResponse response, Instant savedAt) {
    }
}
