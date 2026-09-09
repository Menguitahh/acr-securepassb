# Push local (Expo)

Como levantar el backend con envío real a Expo Push Service para probar
push en dispositivos físicos con dev builds. Por default el backend está
en modo `logging`: no manda nada, solo loguea el payload.

## Variables de entorno

Agregar al `.env` (o al bloque de env vars con el que arranca Spring
Boot):

```
SECUREPASS_PUSH_PROVIDER=expo
SECUREPASS_EXPO_ACCESS_TOKEN=
SECUREPASS_EXPO_PUSH_URL=https://exp.host/--/api/v2/push/send
```

- `SECUREPASS_PUSH_PROVIDER=expo` — obligatorio. Activa `ExpoPushSender`
  vía `@ConditionalOnProperty`. Sin esto se carga `LoggingPushSender` y
  no sale nada de la máquina.
- `SECUREPASS_EXPO_ACCESS_TOKEN` — opcional. Solo hace falta si el
  proyecto Expo tiene "Enhanced Push Security" activada. Dejar vacío
  para arrancar.
- `SECUREPASS_EXPO_PUSH_URL` — opcional. Default es el endpoint público
  de Expo.

Credenciales FCM (Android) y APNs (iOS) **no** van en el backend: se
cargan en el proyecto Expo con `eas credentials` desde el repo mobile.

## Verificar que emite

1. Registrar el device desde la app: `POST /api/v1/devices` con el
   `expoPushToken` del dev build.
2. Disparar un push desde el backend: `POST /api/v1/stays/{id}/notify-resident`
   con un JWT de GUARD del barrio de esa stay.
3. Response `200` = enviado a Expo. En los logs de la app:
   - `push.expo.request-failed` = error de red al servicio Expo.
   - `push.expo.ticket-error` = Expo rechazó el token (ver `error` en el
     log).
   - `push.expo.token-cleaned` = Expo reportó `DeviceNotRegistered` y se
     borró el registro.
   - Ausencia de estos logs = ticket `ok`.

## Sanity check independiente

Verificar Expo sin pasar por el backend:

```
curl -X POST https://exp.host/--/api/v2/push/send \
     -H "content-type: application/json" \
     -d '[{"to":"ExponentPushToken[XXXX]","title":"t","body":"b"}]'
```

Response con `"status":"ok"` y un `id` = Expo aceptó el mensaje.

## Rollback

Sacar `SECUREPASS_PUSH_PROVIDER=expo` (o setearlo a `logging`) y
reiniciar. Vuelve a `LoggingPushSender` sin salida a red.
