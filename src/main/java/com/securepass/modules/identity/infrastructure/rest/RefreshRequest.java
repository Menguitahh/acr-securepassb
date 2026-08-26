package com.securepass.modules.identity.infrastructure.rest;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {}
