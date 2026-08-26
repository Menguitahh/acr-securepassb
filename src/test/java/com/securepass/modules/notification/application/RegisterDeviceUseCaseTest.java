package com.securepass.modules.notification.application;

import com.securepass.modules.notification.domain.Device;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegisterDeviceUseCaseTest {

    @Test
    void upsertea() {
        DeviceRepository devices = mock(DeviceRepository.class);
        when(devices.upsertByToken(any())).thenAnswer(inv -> inv.getArgument(0));
        RegisterDeviceUseCase useCase = new RegisterDeviceUseCase(devices);

        useCase.register(UUID.randomUUID(), "ExponentPushToken[xxxxx]", Device.Platform.IOS);

        verify(devices).upsertByToken(any(Device.class));
    }

    @Test
    void tokenInvalidoRechazado() {
        DeviceRepository devices = mock(DeviceRepository.class);
        RegisterDeviceUseCase useCase = new RegisterDeviceUseCase(devices);

        assertThatThrownBy(() -> useCase.register(UUID.randomUUID(), "x", Device.Platform.ANDROID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.register(UUID.randomUUID(), "  ", Device.Platform.ANDROID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userIdRequerido() {
        DeviceRepository devices = mock(DeviceRepository.class);
        RegisterDeviceUseCase useCase = new RegisterDeviceUseCase(devices);

        assertThatThrownBy(() -> useCase.register(null, "ExponentPushToken[xxxxx]", Device.Platform.IOS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
