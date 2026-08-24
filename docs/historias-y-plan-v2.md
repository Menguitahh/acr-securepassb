# Securepass — Historias de usuario y plan de trabajo v2

> Complementa a `modelo-funcional-v2.md`. Las historias están agrupadas por
> **recorrido completo**, no por capa técnica. Esa es la diferencia con el plan v1:
> cada épica termina en algo que una persona puede hacer de punta a punta.
>
> Regla de cierre: una épica no está terminada hasta que su recorrido funciona
> en el teléfono. No alcanza con que pasen los tests.

---

## Cómo leer esto

**Numeración.** Las historias v2 arrancan en US-50 para no colisionar con las v1.
Los issues arrancan en WBS-100.

**Prioridad.** P0 bloquea el recorrido. P1 lo completa. P2 lo mejora.

**Orden.** Las épicas van en orden de dependencia. La A sostiene a todas las demás.

---

## Épica A — Activación de cuentas por email

Nada de lo demás funciona sin esto. Hoy las cuentas se crean con contraseña puesta a mano, y los tres recorridos de alta (titular, familiar, trabajador) dependen de un enlace de activación que no existe.

### Historias

| ID | Historia | Pri | Criterios de aceptación |
|---|---|---|---|
| US-50 | Como sistema necesito enviar emails transaccionales. | P0 | Proveedor configurado por variable de entorno; en desarrollo los emails se escriben a log en vez de enviarse; fallo de envío no rompe la operación que lo disparó. |
| US-51 | Como usuario nuevo recibo un email con un enlace para activar mi cuenta. | P0 | Enlace válido 7 días, un solo uso; contiene un token aleatorio de 256 bits, no el id del usuario; el email dice quién lo invitó y a qué barrio. |
| US-52 | Como usuario nuevo defino mi contraseña desde ese enlace. | P0 | Mínimo 8 caracteres; al confirmar, la cuenta queda activa y entra directo; el token se consume y no sirve más. |
| US-53 | Como usuario quiero recuperar mi contraseña si la olvido. | P1 | Mismo mecanismo de token; el email se envía siempre, exista o no la cuenta, para no revelar quién está registrado. |
| US-54 | Como quien dio de alta a alguien quiero reenviar la activación si venció. | P1 | Reenviar invalida el token anterior; visible en la pantalla desde donde se creó la cuenta. |

### Issues

| # | Issue | Repo | Rama | Criterios |
|---|---|---|---|---|
| 100 | Infraestructura de email | `securepassb` | `feature/notification-email-sender` | Puerto `EmailSender` + adapter real detrás de flag + `LoggingEmailSender` para dev; plantillas en recursos; envío asíncrono con timeout, fuera de la ruta del request. |
| 101 | Tokens de activación | `securepassb` | `feature/auth-activation-tokens` | Migración: tabla `activation_tokens` (hash del token, user_id, propósito, expira, consumido); 256 bits de entropía; un solo uso garantizado por índice. |
| 102 | Endpoints de activación | `securepassb` | `feature/auth-activation-endpoints` | `GET /activation/{token}` valida y devuelve datos del usuario; `POST /activation/{token}` define contraseña y activa; 410 si vencido o consumido. |
| 103 | Recuperación de contraseña | `securepassb` | `feature/auth-password-reset` | `POST /auth/forgot-password` y `POST /auth/reset-password`; respuesta idéntica exista o no el email; rate limit 3 por hora por email. |
| 104 | Pantalla de activación | `securepassf` | `feature/auth-activation-screen` | Deep link `securepass://activar/{token}` y web fallback; formulario de contraseña con confirmación; mensajes claros de token vencido; entra directo al activar. |
| 105 | Pantalla de recuperación | `securepassf` | `feature/auth-password-reset-screen` | Enlace desde el login; mensaje neutro tras enviar. |

**Recorrido verificable:** el dueño crea un usuario → llega el email → el enlace abre la app → se define la contraseña → entra.

---

## Épica B — Permisos por lote

Cambio de fondo: el rol deja de estar en el usuario y pasa a estar en la relación usuario-lote. Es lo que permite que el jardinero tenga un usuario y trabaje en tres casas, y que una persona sea residente en un lote y trabajador en otro.

### Historias

| ID | Historia | Pri | Criterios de aceptación |
|---|---|---|---|
| US-55 | Como sistema debo resolver qué puede hacer alguien según sus permisos vigentes, no según un rol fijo. | P0 | Un usuario puede tener varios permisos en lotes distintos; el JWT lleva la lista; revocar uno no afecta a los demás. |
| US-56 | Como sistema debo identificar a una persona por su DNI en todo el barrio. | P0 | El DNI es único por barrio; dar de alta un DNI existente reusa el usuario en vez de duplicarlo. |

### Issues

| # | Issue | Repo | Rama | Criterios |
|---|---|---|---|---|
| 106 | Migración de roles a permisos | `securepassb` | `refactor/identity-lot-permissions` | Migración que convierte `memberships` + `family_members` en `lot_permissions` (user, lot, tipo, atributos); los datos existentes se preservan; sin pérdida. |
| 107 | DNI único por barrio | `securepassb` | `feature/identity-document-uniqueness` | Índice único (barrio, documento); alta con DNI existente devuelve el usuario en vez de 409; tests del caso jardinero en tres casas. |
| 108 | Autorización por permiso | `securepassb` | `refactor/auth-permission-checks` | `@PreAuthorize` evalúa permisos sobre el lote del recurso, no rol global; el test de IDOR sigue verde; sin regresiones. |
| 109 | Adaptación del cliente | `securepassf` | `refactor/auth-permission-model` | El estado de sesión guarda la lista de permisos; el ruteo por rol pasa a ser por permiso; una persona con dos permisos elige contexto al entrar. |

**Recorrido verificable:** un mismo DNI dado de alta como residente en un lote y trabajador en otro tiene un solo usuario y ve las dos cosas.

---

## Épica C — Familia

### Historias

| ID | Historia | Pri | Criterios de aceptación |
|---|---|---|---|
| US-57 | Como titular quiero agregar a un familiar mayor de 16 y que reciba su cuenta. | P0 | Nombre, DNI, edad, parentesco y email; se crea el usuario y se envía activación; si el DNI ya existe, se le suma el permiso. |
| US-58 | Como titular quiero registrar a un menor de 16 sin cuenta. | P0 | Queda en el padrón del lote; no se pide email; no se crea usuario; la app explica por qué. |
| US-59 | Como titular quiero quitarle el acceso a un familiar. | P1 | Confirmación explícita; sus invitaciones activas se revocan; el usuario sigue existiendo por si es residente en otro lote. |
| US-60 | Como familiar quiero ver quiénes componen mi unidad familiar. | P1 | Misma pantalla que el titular, sin botones de gestión. |
| US-61 | Como familiar quiero crear invitaciones igual que el titular. | P0 | Sin diferencias en el flujo de invitación. |

### Issues

| # | Issue | Repo | Rama | Criterios |
|---|---|---|---|---|
| 110 | Alta de familiar con cuenta | `securepassb` | `feature/family-member-invite` | Crea usuario si el DNI es nuevo, reusa si existe; dispara activación; solo el titular; valida edad ≥ 16 para pedir email. |
| 111 | Padrón de menores | `securepassb` | `feature/family-minor-registry` | Tabla de personas del lote sin usuario; edad obligatoria; no genera credenciales. |
| 112 | Baja de familiar | `securepassb` | `feature/family-member-revoke` | Revoca el permiso, no el usuario; cascada sobre invitaciones activas de ese lote. |
| 113 | Pantalla de familia | `securepassf` | `feature/family-management-screen` | Alta con validación de edad que cambia el formulario; lista con parentesco; modo lectura para familiares; baja con confirmación. |

**Recorrido verificable:** el titular agrega a su esposa (18) y a su hija (12) → la esposa recibe email, activa y crea una invitación → la hija figura en la lista sin cuenta.

---

## Épica D — Trabajadores

El actor que no existe hoy en ninguna forma.

### Historias

| ID | Historia | Pri | Criterios de aceptación |
|---|---|---|---|
| US-62 | Como titular quiero habilitar a un trabajador con sus días y horarios. | P0 | Nombre, DNI, tipo de servicio, días de la semana y franja horaria; si el DNI existe se le suma el permiso sin crear otro usuario ni otro QR. |
| US-63 | Como titular quiero ver y editar los horarios de mis trabajadores. | P1 | Lista con nombre, servicio y horarios; edición sin recrear el permiso. |
| US-64 | Como titular quiero quitarle el acceso a un trabajador. | P0 | Efecto inmediato online, tras la próxima sincronización offline; no afecta sus permisos en otros lotes. |
| US-65 | Como trabajador quiero ver mi QR y dónde trabajo. | P0 | Pantalla única: nombre, servicio, QR grande, y la lista de casas con horarios. Nada más del barrio. |
| US-66 | Como trabajador entro al barrio mostrando mi QR. | P0 | El sistema evalúa todos los permisos vigentes en ese momento; primera vez también DNI. |
| US-67 | Como guardia veo a qué lote va el trabajador cuando tiene varios habilitados. | P0 | Si más de un permiso lo habilita, la app muestra los lotes y el guardia toca el correcto; si es uno solo, entra directo. |
| US-68 | Como sistema rechazo al trabajador fuera de su horario. | P0 | Denegado con motivo `OUT_OF_SCHEDULE`; el intento queda en la bitácora; funciona offline. |

### Issues

| # | Issue | Repo | Rama | Criterios |
|---|---|---|---|---|
| 114 | Dominio de trabajadores | `securepassb` | `feature/worker-domain` | Permiso tipo `WORKER` con servicio, días y franja; reuso de usuario por DNI; tests del caso multi-lote. |
| 115 | API de gestión | `securepassb` | `feature/worker-management-api` | Alta, listado, edición y baja; solo el titular del lote; dispara activación si el usuario es nuevo. |
| 116 | QR permanente del trabajador | `securepassb` | `feature/worker-qr-token` | Token sin invitación asociada, ligado al usuario; formato SP1; no consume cupo; se invalida al perder todos los permisos. |
| 117 | Validación por franja multi-lote | `securepassb` | `feature/worker-schedule-validation` | Resuelve todos los permisos vigentes; devuelve la lista de lotes habilitados; `OUT_OF_SCHEDULE` si ninguno; zona horaria del barrio. |
| 118 | Validación offline del trabajador | `securepassf` | `feature/worker-offline-validation` | Los permisos vigentes se cachean en SQLite; `validateOffline` evalúa la franja localmente; tests de dentro y fuera de horario. |
| 119 | Selección de lote en garita | `securepassf` | `feature/guard-worker-lot-picker` | Pantalla con los lotes habilitados cuando hay más de uno; toque único; sin lista larga. |
| 120 | App del trabajador | `securepassf` | `feature/worker-app-screens` | Home con QR y horarios; perfil; ruteo por permiso; sin acceso a nada más. |
| 121 | Pantalla de trabajadores del titular | `securepassf` | `feature/worker-management-screen` | Alta con selector de días y franja; lista; edición; baja con confirmación. |

**Recorrido verificable:** el titular habilita un jardinero martes y jueves de 8 a 14 → el jardinero activa su cuenta y ve su QR → entra el martes a las 9 → no entra el miércoles.

---

## Épica E — Verificación de identidad por persona

Cambio respecto de lo construido: hoy el enrolamiento es por invitación y hay que re-verificar en cada pase nuevo.

### Historias

| ID | Historia | Pri | Criterios de aceptación |
|---|---|---|---|
| US-69 | Como persona verificada una vez no vuelvo a mostrar el documento. | P0 | La verificación se guarda contra el DNI, no contra la invitación; vale para invitaciones futuras y para permisos de trabajador. |
| US-70 | Como guardia veo si la persona ya está verificada antes de escanear el DNI. | P1 | El resultado del QR indica si requiere verificación; sin adelantar el nombre cuando todavía no está verificada. |

### Issues

| # | Issue | Repo | Rama | Criterios |
|---|---|---|---|---|
| 122 | Migrar enrolamiento a persona | `securepassb` | `refactor/access-person-verification` | Migración de `guest_enrollments` (por invitación) a verificación por documento y barrio; los datos existentes se conservan; una persona verificada no vuelve a requerirlo. |
| 123 | Caché de verificados offline | `securepassf` | `refactor/scanner-person-verification` | El caché pasa a indexarse por documento; `validateOffline` consulta por persona. |

**Recorrido verificable:** una persona entra con una invitación y verifica su DNI → recibe otra invitación distinta → entra solo con el QR.

---

## Épica F — Garita usable

Los bugs que impiden usar la app hoy, más lo que falta para que el guardia tenga una herramienta y no una cámara.

### Historias

| ID | Historia | Pri | Criterios de aceptación |
|---|---|---|---|
| US-71 | Como guardia quiero navegar entre escáner, presentes, registro y turno. | P0 | Barra superior sólida, legible sobre cualquier fondo de cámara; toques de 48 dp; el escáner sigue siendo la pantalla principal. |
| US-72 | Como guardia quiero ver el registro de ingresos y egresos de mi turno. | P0 | Lista con hora, nombre, lote, dirección y resultado; los denegados con su motivo. |
| US-73 | Como guardia quiero que la app no se trabe al navegar. | P0 | Sin crashes al montar o desmontar pantallas; el selector de fecha del turno no rompe la navegación. |

### Issues

| # | Issue | Repo | Rama | Criterios |
|---|---|---|---|---|
| 124 | Fix del selector de fecha | `securepassf` | `fix/shift-datetimepicker-crash` | Resolver `Cannot read property 'dismiss' of undefined` al desmontar; verificado navegando en dispositivo. |
| 125 | Barra de navegación del guardia | `securepassf` | `feature/guard-navigation-bar` | Barra sólida sobre la cámara; cuatro destinos; contraste verificado sobre fondo claro y oscuro. |
| 126 | Registro del turno | `securepassf` | `feature/guard-shift-log-screen` | Consume `GET /access-logs` filtrado por turno; agrupado por hora; denegados destacados. |

**Recorrido verificable:** el guardia abre turno, escanea, va a presentes, vuelve, va al registro, cierra turno. Sin crashes.

---

## Épica G — Limpieza

| ID | Historia | Pri | Criterios |
|---|---|---|---|
| US-74 | Como equipo quiero eliminar el pase recurrente de invitación. | P1 | Los trabajadores lo reemplazan; migración de los recurrentes existentes a permisos de trabajador o revocación; el tipo desaparece de la API y de la app. |

| # | Issue | Repo | Rama | Criterios |
|---|---|---|---|---|
| 127 | Eliminar invitación recurrente | ambos | `refactor/invitation-remove-recurring` | Sale el tipo `RECURRING` del dominio, la API y la UI; los campos de franja salen del payload del QR; migración de los existentes; spec actualizado. |

---

## Orden de ejecución

```
A. Activación (100-105)          ← sostiene todo lo demás
        ↓
B. Permisos por lote (106-109)   ← cambio estructural, antes de construir encima
        ↓
   ┌────┴────┐
   ↓         ↓
C. Familia   D. Trabajadores     ← se pueden hacer en paralelo
(110-113)    (114-121)
   └────┬────┘
        ↓
E. Verificación por persona (122-123)
        ↓
F. Garita usable (124-126)       ← se puede adelantar: no depende de nada
        ↓
G. Limpieza (127)                ← al final, cuando D esté probado
```

**La F se puede adelantar.** Son bugs que impiden usar lo que ya existe, y no dependen de ninguna otra épica. Si querés poder probar mientras se construye el resto, empezá por ahí.

**La B es la más riesgosa.** Toca el modelo de identidad de un sistema que ya funciona. Requiere migración con preservación de datos y va a romper cosas. Hacela cuando puedas dedicarle atención, no en una tanda larga sin supervisión.

---

## Reglas de trabajo para esta ronda

Las mismas de siempre, más tres que salen de lo que pasó:

1. **Una épica no cierra hasta que su recorrido funciona en el teléfono.** No alcanza con tests verdes. El agente no puede verificarlo: lo verificás vos, y hasta entonces la épica sigue abierta.
2. **Cuando el agente diga que algo está listo, tiene que decir explícitamente qué no pudo verificar en dispositivo.**
3. **Un smoke test que monte el árbol de componentes real.** Los 82 tests pasaban con la app crasheando al arrancar. Un solo test que renderice el layout raíz atrapa esa clase entera de bug.
