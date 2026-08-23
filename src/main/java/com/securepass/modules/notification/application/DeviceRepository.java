package com.securepass.modules.notification.application;

import com.securepass.modules.notification.domain.Device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository {

    /**
     * Upsert por `expo_push_token` (UNIQUE en la tabla): si el token ya existe,
     * reasigna al usuario actual y actualiza `last_seen_at`. Un mismo dispositivo
     * puede pasar de un residente a otro (cuenta compartida en familia); un
     * mismo residente puede tener multiples dispositivos.
     */
    Device upsertByToken(Device device);

    List<Device> findByUserId(UUID userId);

    /**
     * Borra un token concreto. Idempotente: si no existia devuelve `false`.
     * Se usa al recibir `DeviceNotRegistered` del servicio Expo.
     */
    boolean deleteByToken(String expoPushToken);

    Optional<Device> findByToken(String expoPushToken);
}
