package com.securepass.modules.notification.application;

import com.securepass.modules.notification.domain.Device;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * WBS-43: el cliente registra su `expo_push_token` para poder recibir
 * pushes de ingreso. El mismo aparato puede rotar de usuario (cuenta
 * familiar compartida); el mismo residente puede tener varios devices.
 */
@Service
public class RegisterDeviceUseCase {

    private final DeviceRepository devices;

    public RegisterDeviceUseCase(DeviceRepository devices) {
        this.devices = devices;
    }

    @Transactional
    public Device register(UUID userId, String expoPushToken, Device.Platform platform) {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        Instant now = Instant.now();
        Device d = new Device(UUID.randomUUID(), userId, expoPushToken, platform, now, now);
        return devices.upsertByToken(d);
    }
}
