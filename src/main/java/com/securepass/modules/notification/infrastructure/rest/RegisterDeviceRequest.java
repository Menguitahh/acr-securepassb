package com.securepass.modules.notification.infrastructure.rest;

import com.securepass.modules.notification.domain.Device;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
        @NotBlank @Size(min = 10, max = 200) String expoPushToken,
        @NotNull Device.Platform platform
) {}
