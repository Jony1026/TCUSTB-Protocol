package net.tdteam.ustb.auth.model;

import java.time.Instant;


public record SessionResponse(String accessToken, String account, String realName, Instant expiresAt) {
}
