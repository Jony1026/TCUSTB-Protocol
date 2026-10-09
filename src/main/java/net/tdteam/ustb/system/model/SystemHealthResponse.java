package net.tdteam.ustb.system.model;

import java.time.Instant;

/**
 * 田园小纸箱源站健康状态。
 *
 * @author itsjony01
 * @date 2026-10-04
 */
public record SystemHealthResponse(String service, String status, Instant checkedAt) {
}
