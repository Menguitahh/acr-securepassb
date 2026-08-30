package com.securepass.modules.invitation.infrastructure.rest;

import java.util.UUID;

public record WorkerQrResponse(UUID jti, String token) {}
