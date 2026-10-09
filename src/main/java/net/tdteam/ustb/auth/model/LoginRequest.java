package net.tdteam.ustb.auth.model;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String challengeId, @NotBlank String account,
                           @NotBlank String password, String captcha) {
}
