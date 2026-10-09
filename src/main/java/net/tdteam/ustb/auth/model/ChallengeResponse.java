package net.tdteam.ustb.auth.model;

import java.time.Instant;

public record ChallengeResponse(String challengeId, Instant expiresAt, boolean captchaRequired, String captchaType) {
}
