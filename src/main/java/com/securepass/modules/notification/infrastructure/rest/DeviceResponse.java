package com.securepass.modules.notification.infrastructure.rest;

import com.securepass.modules.notification.domain.Device;

import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(
        UUID id,
        Device.Platform platform,
        Instant lastSeenAt
) {
    public static DeviceResponse from(Device d) {
        return new DeviceResponse(d.id(), d.platform(), d.lastSeenAt());
    }
}
