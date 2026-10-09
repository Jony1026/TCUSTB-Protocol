package net.tdteam.ustb.auth.model;

import java.time.Instant;

/**
 * 学校教务 SSO 会话检测结果。
 *
 * @author itsjony01
 * @date 2026-10-04
 */
public record SsoStatusResponse(String status, boolean authenticated, Instant checkedAt) {
}
