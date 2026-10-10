package com.securepass.modules.notification.infrastructure.rest;

import com.securepass.modules.notification.application.RegisterDeviceUseCase;
import com.securepass.modules.notification.domain.Device;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * WBS-43: el cliente (mobile) registra su `expo_push_token` para que el
 * backend pueda enviarle el push de ingreso (US-25). Cualquier usuario
 * autenticado puede registrar su propio device.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final RegisterDeviceUseCase useCase;
    private final AccessGuard guard;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DeviceResponse> register(@Valid @RequestBody RegisterDeviceRequest request) {
        CurrentUser actor = guard.require();
        Device d = useCase.register(actor.userId(), request.expoPushToken(), request.platform());
        return ResponseEntity.created(URI.create("/api/v1/devices/" + d.id()))
                .body(DeviceResponse.from(d));
    }
}
