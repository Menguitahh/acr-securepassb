# Securepass — Modelo funcional v2

> Este documento reemplaza al spec v1 como fuente de verdad del **funcionamiento**.
> El spec v1 sigue valiendo para arquitectura, stack, seguridad del token y estándares de código.
> Escrito después de la primera prueba en dispositivo, cuando quedó claro que el sistema
> había crecido por parches y los recorridos no cerraban.

---

## 1. Los cuatro actores

| Actor | Quién es | Qué hace |
|---|---|---|
| **Dueño del barrio** | Administra el country | Da de alta lotes y titulares, ve todo, audita |
| **Guardia** | Opera la garita | Registra ingresos y egresos, ve quién está adentro y cuánto le queda |
| **Residente** | Vive en un lote | Titular o familiar. Invita gente y habilita trabajadores |
| **Trabajador** | Presta servicio en una o varias casas | Entra con su QR en los días y horarios habilitados |

El **invitado** no es un actor: no tiene cuenta. Recibe un QR y muestra su DNI la primera vez.

---

## 2. Modelo de identidad

Tres reglas que ordenan todo lo demás:

**R1 — Una persona, un usuario.** El DNI identifica a la persona en todo el sistema. Un trabajador que sirve en tres casas tiene un solo usuario y un solo QR.

**R2 — Los permisos son la unidad, no las cuentas.** Un usuario puede ser residente en un lote y trabajador en otro. Lo que define qué puede hacer es el conjunto de permisos vigentes, no un rol único.

**R3 — Solo mayores de 16 tienen cuenta.** Los menores figuran en el padrón del lote como personas, sin acceso a la app.

---

## 3. Recorridos completos

### 3.1 Alta de un residente titular

```
El dueño del barrio da de alta el lote (código catastral)
        ↓
Carga al titular: nombre, DNI, email
        ↓
El sistema envía un email con un enlace de activación (válido 7 días)
        ↓
El titular abre el enlace y define su contraseña
        ↓
Entra a la app. Ve su lote, su QR personal y su familia (vacía)
```

**Qué pasa si el enlace vence:** el dueño del barrio lo reenvía. El titular no puede pedirlo solo, porque todavía no tiene cuenta.

### 3.2 El titular arma su familia

```
El titular entra a "Mi familia"
        ↓
"Agregar familiar" → nombre, DNI, edad, parentesco
        ↓
    ┌─── Si tiene 16 o más: además pide email
    │         ↓
    │    Se crea el usuario y se le envía el enlace de activación
    │         ↓
    │    El familiar define su contraseña y entra
    │
    └─── Si tiene menos de 16: queda en el padrón, sin cuenta
              ↓
         Puede entrar con el QR del titular o acompañándolo
```

**Lo que puede el familiar con cuenta:** crear invitaciones, ver su QR personal, ver el historial del lote.
**Lo que no puede:** agregar o quitar familiares, habilitar trabajadores, quitarle el acceso a nadie.

**Misma interfaz para los dos.** El familiar ve la sección "Mi familia" en modo lectura: quiénes son, sin botones de gestión. No hay dos apps distintas.

### 3.3 El residente invita a alguien

```
"Nueva invitación" → nombre del invitado, DNI, fecha y hora
        ↓
Se genera el QR y se comparte por WhatsApp
        ↓
El invitado llega a la garita y muestra el QR
        ↓
    ┌─── Primera vez de esa persona en el barrio:
    │    el guardia escanea el QR, después el DNI
    │         ↓
    │    Coinciden → entra, y la persona queda registrada
    │    No coinciden → no entra, queda el intento en la bitácora
    │
    └─── Ya vino antes: alcanza el QR
              ↓
         Entra directo
        ↓
El residente recibe el aviso de que su invitado llegó
        ↓
Al irse, el invitado muestra el QR y el guardia registra el egreso
```

**El DNI se verifica una vez por persona, no por invitación.** Si María López vino en marzo y vuelve en agosto con una invitación nueva, ya está verificada: alcanza el QR.

Esto es un **cambio respecto de lo construido**, donde el enrolamiento era por invitación.

### 3.4 El residente habilita un trabajador

```
"Mis trabajadores" → "Habilitar trabajador"
        ↓
Nombre, DNI, tipo de servicio (jardinero, empleada, plomero...)
        ↓
    ┌─── El DNI ya existe en el sistema:
    │    se le agrega un permiso nuevo para este lote
    │    (no se crea otra cuenta ni otro QR)
    │
    └─── El DNI es nuevo:
         se crea el usuario, se pide email, se envía activación
        ↓
El residente define días y horarios: "lunes y jueves, 8 a 14"
        ↓
El trabajador entra a su app y ve: su nombre, su QR,
y la lista de casas donde trabaja con sus horarios
```

**Qué ve el trabajador y nada más:** su nombre, su rol, su QR, y sus horarios por casa. No ve invitaciones, ni historial del barrio, ni nada de los residentes.

### 3.5 El trabajador entra al barrio

```
Muestra su QR en la garita
        ↓
El sistema busca todos sus permisos vigentes en este momento
        ↓
    ┌─── Ningún permiso lo habilita ahora:
    │    NO ENTRA. Queda registrado el intento fuera de horario.
    │
    ├─── Un solo permiso lo habilita:
    │    entra, se registra el ingreso a ese lote
    │
    └─── Varios permisos lo habilitan (trabaja en dos casas el mismo día):
         el guardia ve los lotes habilitados y toca el correcto
        ↓
El residente de ese lote recibe el aviso
        ↓
Al irse, muestra el QR y se registra el egreso
```

**Primera vez:** igual que el invitado, se verifica el DNI una vez. Después alcanza el QR.

### 3.6 El guardia opera la garita

```
Abre turno (elige garita)
        ↓
Pantalla principal: cámara lista para escanear
        ↓
Desde ahí llega a:
   · Presentes — quién está adentro, desde qué hora, cuánto le queda
   · Registro — todos los ingresos y egresos del turno
   · Manual — para el delivery o quien llega sin QR
        ↓
20 minutos antes de que venza una permanencia, recibe un aviso
        ↓
Desde el aviso, notifica al residente que invitó
        ↓
El residente extiende el pase o pide que la visita se retire
        ↓
Cierra turno con nota de entrega
```

### 3.7 El delivery y el que llega sin QR

No hay pase ni cuenta. El guardia llama al residente por teléfono, y si autoriza, usa **ingreso manual**: nombre, documento y lote destino. Queda registrado igual que todo lo demás.

---

## 4. Qué ve cada uno al abrir la app

### Residente titular
```
Inicio
  · Mi QR personal
  · Invitaciones activas
  · [Nueva invitación]

Mi familia
  · Los miembros del lote
  · [Agregar familiar]         ← solo titular
  · Quitar acceso a un miembro ← solo titular

Mis trabajadores               ← solo titular
  · Quiénes están habilitados y con qué horarios
  · [Habilitar trabajador]

Historial
  · Todos los ingresos y egresos del lote

Perfil
  · Nombre, DNI, lote, rol
  · Cerrar sesión
```

### Residente familiar
Lo mismo, con dos diferencias: "Mi familia" es solo lectura, y no existe "Mis trabajadores".

### Trabajador
```
Inicio
  · Su nombre y tipo de servicio
  · Su QR, grande
  · Dónde trabaja y en qué horarios

Perfil
  · Nombre, DNI
  · Cerrar sesión
```

Nada más. Sin invitaciones, sin historial, sin familia.

### Guardia
```
Escáner (pantalla principal)
  · Cámara a pantalla completa
  · Barra superior sólida: Presentes · Registro · Manual · Turno

Presentes
  · Quién está adentro, desde cuándo, cuánto le queda
  · Los que están por vencer, resaltados

Registro
  · Ingresos y egresos del turno, con hora y resultado

Turno
  · Abrir, cerrar, nota de entrega
```

### Dueño del barrio
Sin app móvil. Su interfaz es el dashboard web, que sigue diferido.

---

## 5. Cambios respecto de lo construido

### 5.1 Lo que sirve tal como está

El motor completo del backend: firma Ed25519, anti-replay, validación offline, sincronización, estadías, bitácora inmutable, aislamiento entre barrios. Toda esa capa está probada y no se toca.

Del frontend: el escáner, la validación offline, la cola de eventos, la pantalla de resultado, las invitaciones.

### 5.2 Lo que hay que cambiar

| Qué | Cómo está | Cómo tiene que quedar |
|---|---|---|
| Enrolamiento | Por invitación: la misma persona se re-verifica en cada pase nuevo | **Por persona**: se verifica una vez y queda para siempre |
| Alta de familiares | Exige que el usuario ya exista; no hay forma de crearlo | El titular **crea** la cuenta y el sistema envía el enlace de activación |
| Roles | Un rol por usuario | **Permisos por lote**: la misma persona puede ser residente en uno y trabajador en otro |
| Navegación del guardia | Botones flotando sobre la cámara, ilegibles | Barra superior sólida |

### 5.3 Lo que falta entero

- **Activación por email.** Hoy no existe: las cuentas se crean con contraseña puesta a mano. Es lo que sostiene el alta de titulares, familiares y trabajadores.
- **Trabajadores.** El actor completo: alta, permisos por lote con días y horarios, QR, validación por franja, y su app propia.
- **Padrón de personas del lote.** Los menores de 16 que figuran sin cuenta.
- **Registro del turno para el guardia.** La bitácora existe en el backend; falta la pantalla.

### 5.4 Lo que sobra

**El QR de invitación con validez recurrente.** Se diseñó para el jardinero, y ahora el jardinero tiene su propio mecanismo, que es mejor: un usuario, permisos por casa, horarios que el residente controla. Mantener las dos formas de hacer lo mismo es la clase de duplicación que corrompe un sistema.

Las invitaciones quedan en dos tipos: **única** (una visita) y **evento** (varias personas en una ventana).

---

## 6. Reglas que no se negocian

| # | Regla |
|---|---|
| N1 | El DNI identifica a la persona. Una persona, un usuario, un QR. |
| N2 | La verificación de identidad ocurre una vez por persona, no por invitación. |
| N3 | Un trabajador fuera de su horario no entra. El intento queda registrado. |
| N4 | Solo el titular gestiona familia y trabajadores. El familiar solo invita. |
| N5 | Menores de 16 no tienen cuenta. Figuran en el padrón del lote. |
| N6 | Todo ingreso y egreso queda registrado, sin excepción, incluido el manual. |
| N7 | El trabajador ve su información y nada más del barrio. |
| N8 | Nadie define su contraseña por otro: siempre enlace de activación. |

---

## 7. Cómo verificar que el sistema funciona

Estos seis recorridos, hechos con el teléfono en la mano. Si los seis andan, el sistema está.

1. El dueño da de alta un lote y un titular → llega el email → el titular activa y entra
2. El titular agrega a su esposa (18) y a su hija (12) → la esposa recibe email y entra, la hija figura sin cuenta
3. La esposa crea una invitación → la comparte → en garita se escanea QR y DNI → entra → el titular recibe el aviso
4. La misma persona vuelve con otra invitación → alcanza el QR
5. El titular habilita un jardinero martes y jueves de 8 a 14 → el jardinero entra el martes a las 9 → intenta el miércoles y no entra
6. El guardia ve a los tres en Presentes, recibe el aviso de vencimiento, avisa al residente, y el residente extiende

---

## 8. Fuera de alcance

Sin cambios respecto del spec v1, más lo siguiente:

- Reconocimiento de patentes
- Dashboard web del dueño del barrio (diferido, no cancelado)
- Push remoto: requiere build custom, no funciona en Expo Go
- Cuentas para menores de 16
- Pagos, expensas, reservas de amenities
