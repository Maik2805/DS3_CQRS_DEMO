# POC Académica — CQRS + Event Sourcing para una Wallet Virtual

## 1. Objetivo

Construir una POC académica, ejecutable completamente con **Docker Compose**, que demuestre de forma práctica:

- CQRS: separación estricta entre Write Side y Read Side.
- Event Sourcing: el estado del Write Side se reconstruye desde eventos.
- Event Store: uso de **Axon Server** como almacenamiento y distribución de eventos.
- Proyección: construcción de un Read Model independiente a partir de los eventos.
- Consistencia eventual: el Read Model se actualiza después de que el Write Side persiste el evento.
- Independencia tecnológica: Write Side en **Java** y Read Side en **TypeScript**.
- Separación física de persistencia: EventStoreDB de Axon Server para escritura/eventos y **PostgreSQL separado** para lecturas.
- Reconstruibilidad: demostrar que el Read Model puede eliminarse y reconstruirse completamente a partir de la fuente de eventos.
- Operación mediante HTTP/REST; **no se requiere interfaz gráfica**.

La POC debe ser pequeña, didáctica y fácil de ejecutar localmente. No se debe introducir Kafka, Redis, Elasticsearch, Kubernetes, API Gateway ni otros componentes salvo que sean estrictamente necesarios.

---

## 2. Arquitectura obligatoria

Implementar los siguientes componentes:

```text
                         CLIENT / POSTMAN
                                |
                +---------------+---------------+
                |                               |
             COMMAND                          QUERY
                |                               |
                v                               v
      +---------------------+         +---------------------+
      | WRITE SERVICE       |         | READ SERVICE        |
      | Java + Spring Boot  |         | TypeScript + NestJS |
      | + Axon Framework   |         |                     |
      +----------+----------+         +----------+----------+
                 |                               |
                 v                               v
      +---------------------+         +---------------------+
      | Wallet Domain       |         | Query Handlers       |
      | Event Sourced       |         | Read Model           |
      +----------+----------+         +----------+----------+
                 |                               |
                 | Domain Events                 |
                 v                               |
      +----------------------------------------------------+
      |                     AXON SERVER                     |
      |                     Event Store                     |
      |                Persistent Event Streams             |
      +-------------------------+--------------------------+
                                |
                         Event subscription
                                |
                                v
                     +------------------------+
                     | READ PROJECTION        |
                     | NestJS                 |
                     +-----------+------------+
                                 |
                                 v
                     +------------------------+
                     | PostgreSQL              |
                     | READ MODEL              |
                     +------------------------+
```

### 2.1 Tecnologías

#### Write Side

- Java 21.
- Spring Boot 4.x.
- Axon Framework 5.x.
- Maven.
- Axon Server 2026.0.x.

Como referencia para reproducibilidad de esta POC, utilizar inicialmente:

- Axon Framework: **5.3.2**.
- Axon Server: **2026.0.2** o el último parche disponible de la línea 2026.0 si el agente lo verifica al implementar.
- Spring Boot: **4.1.1**.

Axon Framework 5 requiere JDK 21 como mínimo. Axon Framework 5.0.3+ soporta Spring Boot 4. Axon Server 2025.2+ soporta Dynamic Consistency Boundaries (DCB), y la línea 2026.0 es la línea vigente para esta POC.

#### Read Side

- TypeScript.
- Node.js LTS compatible con NestJS 11.
- NestJS 11.
- PostgreSQL 16 o superior.
- ORM ligero: preferentemente **Drizzle ORM**, o SQL parametrizado directo si simplifica la POC.

#### Infraestructura

- Docker.
- Docker Compose.
- Un solo archivo `docker-compose.yml`/`compose.yml` en la raíz.

No requerir interfaz web.

---

## 3. Principios arquitectónicos que deben cumplirse

1. **El Write Side no debe leer PostgreSQL.**
2. **El Read Side no debe leer el Event Store para resolver queries de negocio.**
3. El Event Store de Axon Server es la fuente de verdad de los eventos de dominio.
4. PostgreSQL contiene exclusivamente modelos materializados para consultas.
5. El Read Side recibe eventos y actualiza sus propias tablas.
6. Los DTOs de respuesta del Read Side no deben reutilizar clases Java del Write Side.
7. Los modelos de dominio del Write Side no deben depender de entidades del Read Side.
8. Las reglas de negocio de la wallet viven en el Write Side.
9. Una Query no debe producir efectos de escritura en el dominio.
10. Un Command expresa intención de modificar el sistema.
11. La POC debe hacer visible la consistencia eventual y el replay de proyecciones.

---

## 4. Dominio de la Wallet

El bounded context de la POC será `Wallet`.

### 4.1 Entidad / Aggregate

Implementar una entidad event-sourced `Wallet` con al menos:

```text
walletId
ownerId
currency
balance
```

Usar `BigDecimal` en Java para dinero. No usar `double`/`float`.

La moneda de la POC puede fijarse en `COP` para simplificar.

La entidad debe reconstruir su estado mediante replay de eventos.

### 4.2 Reglas de negocio

Implementar como mínimo:

#### Creación

- `walletId` es obligatorio y único.
- `ownerId` es obligatorio.
- `currency` es obligatoria.
- Una wallet no puede crearse dos veces con el mismo identificador.
- El saldo inicial debe ser `0.00`.

#### Depósito

- `amount > 0`.
- Solo aceptar moneda compatible con la wallet.
- Genera `MoneyDeposited`.

#### Retiro

- `amount > 0`.
- El saldo debe ser suficiente.
- No permitir saldo negativo.
- Genera `MoneyWithdrawn`.

#### Transferencia

- `sourceWalletId` y `targetWalletId` son obligatorios.
- No pueden ser iguales.
- `amount > 0`.
- La wallet origen debe tener fondos suficientes.
- La moneda de ambas wallets debe coincidir.
- La operación debe generar un único hecho de negocio `MoneyTransferred` que identifique origen, destino, monto y `transferId`.
- Debe evitarse procesar dos veces la misma transferencia.

La transferencia debe modelarse de manera consistente con Axon Framework 5. Como la operación afecta a dos wallets, **usar Dynamic Consistency Boundary (DCB)** de Axon Framework 5 para mostrar una consistencia dinámica entre las dos entidades dentro del bounded context. Axon Server 2025.2+ soporta DCB.

Si el agente determina que la API concreta de DCB en la versión elegida requiere una configuración adicional, mantener el objetivo funcional: una sola transferencia no puede dejar que el Write Side valide un saldo incorrecto por concurrencia.

---

## 5. Commands

Definir estos Commands:

### `CreateWallet`

```json
{
  "walletId": "wallet-001",
  "ownerId": "owner-001",
  "currency": "COP"
}
```

### `DepositMoney`

```json
{
  "walletId": "wallet-001",
  "amount": 100000.00,
  "currency": "COP"
}
```

### `WithdrawMoney`

```json
{
  "walletId": "wallet-001",
  "amount": 25000.00,
  "currency": "COP"
}
```

### `TransferMoney`

```json
{
  "transferId": "transfer-001",
  "sourceWalletId": "wallet-001",
  "targetWalletId": "wallet-002",
  "amount": 50000.00,
  "currency": "COP"
}
```

`transferId` debe ser obligatorio para permitir idempotencia demostrable.

---

## 6. Domain Events

Definir estos eventos de dominio:

### `WalletCreated`

```json
{
  "eventType": "WalletCreated",
  "eventVersion": 1,
  "walletId": "wallet-001",
  "ownerId": "owner-001",
  "currency": "COP"
}
```

### `MoneyDeposited`

```json
{
  "eventType": "MoneyDeposited",
  "eventVersion": 1,
  "eventId": "...",
  "walletId": "wallet-001",
  "amount": 100000.00,
  "currency": "COP",
  "occurredAt": "2026-09-28T20:00:00Z"
}
```

### `MoneyWithdrawn`

```json
{
  "eventType": "MoneyWithdrawn",
  "eventVersion": 1,
  "eventId": "...",
  "walletId": "wallet-001",
  "amount": 25000.00,
  "currency": "COP",
  "occurredAt": "2026-09-28T20:05:00Z"
}
```

### `MoneyTransferred`

```json
{
  "eventType": "MoneyTransferred",
  "eventVersion": 1,
  "eventId": "...",
  "transferId": "transfer-001",
  "sourceWalletId": "wallet-001",
  "targetWalletId": "wallet-002",
  "amount": 50000.00,
  "currency": "COP",
  "occurredAt": "2026-09-28T20:10:00Z"
}
```

### Reglas de eventos

- Los eventos son hechos ocurridos; usar nombres en pasado.
- Los eventos deben ser inmutables.
- No enviar un objeto interno complejo del Aggregate como payload.
- Mantener `eventVersion` para enseñar evolución de contratos.
- Incluir `eventId` y `occurredAt` en eventos mutables de negocio.
- Los nombres de eventos deben ser estables y no depender de nombres internos de clases Java.
- El Read Side debe deserializar el contrato JSON de forma independiente.

---

## 7. Write API

Implementar una API REST mínima.

Base URL dentro de Compose:

```text
http://write-service:8080
```

Desde el host:

```text
http://localhost:8081
```

### Endpoints

#### Crear wallet

```http
POST /api/wallets
Content-Type: application/json
```

Body:

```json
{
  "walletId": "wallet-001",
  "ownerId": "owner-001",
  "currency": "COP"
}
```

Respuesta esperada:

```http
201 Created
```

#### Depositar

```http
POST /api/wallets/{walletId}/deposits
Content-Type: application/json
```

Body:

```json
{
  "amount": 100000.00,
  "currency": "COP"
}
```

#### Retirar

```http
POST /api/wallets/{walletId}/withdrawals
Content-Type: application/json
```

Body:

```json
{
  "amount": 25000.00,
  "currency": "COP"
}
```

#### Transferir

```http
POST /api/transfers
Content-Type: application/json
Idempotency-Key: transfer-001
```

Body:

```json
{
  "transferId": "transfer-001",
  "sourceWalletId": "wallet-001",
  "targetWalletId": "wallet-002",
  "amount": 50000.00,
  "currency": "COP"
}
```

El servicio debe devolver errores HTTP claros para reglas de dominio:

- `400` para payload inválido.
- `404` para wallet inexistente.
- `409` para conflicto de negocio/concurrencia o duplicidad de la operación.
- `422` puede utilizarse para violaciones semánticas de negocio si el agente considera que mejora la API, pero debe documentarse y usarse de forma consistente.

---

## 8. Event Store con Axon Server

Usar Axon Server como Event Store del Write Side.

Configurar un contexto `wallet` para aislar los eventos de la POC. Si la configuración elegida usa DCB, el contexto debe ser DCB.

Ejemplo conceptual de Docker Compose:

```yaml
services:
  axon-server:
    image: docker.axoniq.io/axoniq/axonserver:2026.0.2
    ports:
      - "8024:8024"
      - "8124:8124"
    environment:
      axoniq_axonserver_hostname: axon-server
      axoniq_axonserver_autocluster_first: axon-server
      axoniq_axonserver_autocluster_contexts: _admin
    volumes:
      - axon-data:/axonserver/data
      - axon-events:/axonserver/events
```

El agente debe verificar la imagen/tag disponible antes de finalizar la implementación y fijar una versión concreta, evitando `latest`.

Axon Server expone la UI administrativa en `http://localhost:8024`; no se necesita construir una UI propia.

### Persistencia

Los volúmenes de Axon Server son obligatorios para que el reinicio del stack no elimine la fuente de eventos.

---

## 9. Integración Axon Server → Read Side

El Read Side está implementado en NestJS y **no usa Axon Framework**.

La integración obligatoria será:

```text
Axon Server
     |
     | persistent event stream / HTTP integration
     v
NestJS Event Handler
     |
     v
Projection
     |
     v
PostgreSQL
```

Axon Server admite integración con aplicaciones que no usan Axon Framework mediante HTTP(s) o RSocket. Los event handlers externos deben registrarse en Axon Server y están respaldados por persistent streams.

Para esta POC, preferir **HTTP + JSON `Wrapped`** por facilidad de inspección y compatibilidad con TypeScript.

El Read Side deberá exponer un endpoint interno para recepción de eventos.

Ejemplo conceptual:

```http
POST /internal/events/axon
Content-Type: application/json
```

El agente debe implementar el adaptador según el formato exacto entregado por la versión de Axon Server seleccionada. No inventar un formato propio entre Axon Server y NestJS.

### Registro del event handler

El nombre del handler puede ser:

```text
wallet-read-projection
```

Configurar:

- filtro: todos los eventos del bounded context `wallet` que pertenezcan a la proyección.
- inicio inicial: `TAIL`, para permitir que el Read Model se construya desde el historial existente durante una primera ejecución.
- procesamiento secuencial inicialmente: 1 segmento.

El mecanismo de registro puede automatizarse por script si la API de administración de la versión seleccionada lo permite de forma estable. Como alternativa de POC, documentar claramente el registro inicial mediante Axon Server UI y proporcionar un comando/script reproducible para repetirlo.

---

## 10. Read Model PostgreSQL

Crear una base de datos independiente:

```text
Host desde Compose: postgres-read
Port: 5432
Database: wallet_read
User: wallet_read
```

El Write Side **no** debe tener credenciales ni dependencia de esta base.

### Tabla `wallet_balance`

```sql
CREATE TABLE wallet_balance (
    wallet_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(19,2) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
```

### Tabla `wallet_transaction`

```sql
CREATE TABLE wallet_transaction (
    transaction_id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    event_id UUID NOT NULL UNIQUE
);
```

### Tabla `wallet_transfer`

Opcional pero recomendada para demostración:

```sql
CREATE TABLE wallet_transfer (
    transfer_id UUID PRIMARY KEY,
    source_wallet_id UUID NOT NULL,
    target_wallet_id UUID NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    event_id UUID NOT NULL UNIQUE
);
```

La estructura es un **Read Model**, no una réplica relacional del Aggregate.

---

## 11. Projections

Implementar una proyección explícita en NestJS.

### `WalletCreated`

Crear:

```text
wallet_balance
```

con:

```text
balance = 0.00
```

### `MoneyDeposited`

Actualizar:

```text
wallet_balance.balance += amount
```

Insertar:

```text
wallet_transaction(type = DEPOSIT)
```

### `MoneyWithdrawn`

Actualizar:

```text
wallet_balance.balance -= amount
```

Insertar:

```text
wallet_transaction(type = WITHDRAWAL)
```

### `MoneyTransferred`

Actualizar ambas wallets:

```text
source balance -= amount
target balance += amount
```

Insertar registro de transferencia y, preferentemente, dos movimientos derivados en `wallet_transaction`:

```text
TRANSFER_OUT
TRANSFER_IN
```

### Atomicidad de la proyección

Cada evento recibido debe producir una operación transaccional en PostgreSQL siempre que sea posible.

Para `MoneyTransferred`, ambos balances deben actualizarse en la misma transacción SQL.

---

## 12. Idempotencia del Read Side

El consumidor debe ser idempotente.

Si Axon Server vuelve a entregar un evento, no se puede duplicar el movimiento.

La protección mínima es:

- `event_id UNIQUE`.
- Transacción PostgreSQL.
- Si el `event_id` ya existe, considerar el evento procesado y responder exitosamente sin volver a modificar el saldo.

No asumir que un consumidor distribuido recibe cada evento exactamente una sola vez.

---

## 13. Read API

Desde el host:

```text
http://localhost:8082
```

### Obtener saldo

```http
GET /api/wallets/{walletId}/balance
```

Respuesta:

```json
{
  "walletId": "wallet-001",
  "currency": "COP",
  "balance": 125000.00,
  "updatedAt": "2026-09-28T20:10:00Z"
}
```

### Obtener detalle de wallet

```http
GET /api/wallets/{walletId}
```

### Obtener movimientos

```http
GET /api/wallets/{walletId}/transactions
```

Permitir como mínimo:

- `limit`.
- `offset`.
- orden descendente por `occurred_at`.

### Obtener transferencias

```http
GET /api/wallets/{walletId}/transfers
```

El Read API solo consulta PostgreSQL.

---

## 14. Endpoint para reconstrucción del Read Model

Esta es una parte obligatoria de la POC.

Implementar un endpoint administrativo protegido por una condición simple de POC, por ejemplo:

```http
POST /admin/projection/rebuild
```

El propósito es demostrar:

```text
Read Model = Proyección derivada de Event Store
```

El proceso esperado es:

```text
1. detener o pausar la aplicación de la proyección
2. borrar las tablas del Read Model
3. iniciar una nueva proyección desde TAIL
4. consumir nuevamente todos los eventos históricos
5. reconstruir PostgreSQL
```

No se deben reconstruir datos desde PostgreSQL ni desde una copia auxiliar.

### Forma preferida

La solución debe aprovechar el mecanismo de persistent stream/replay de Axon Server y no implementar un segundo event log en NestJS.

### Rebuild manual alternativo

Además del endpoint, documentar comandos Docker equivalentes para limpiar exclusivamente PostgreSQL:

```bash
docker compose exec postgres-read psql -U wallet_read -d wallet_read
```

y ejecutar un `TRUNCATE` de las tablas de proyección.

Después, reiniciar el Read Side con la posición de stream configurada para replay.

La demostración debe ser reproducible sin modificar los eventos del Event Store.

---

## 15. Demostración obligatoria del Replay

La README debe incluir una secuencia exacta que pueda ejecutar un profesor/estudiante.

### Paso 1 — Crear dos wallets

```http
POST /api/wallets
```

Crear:

```text
wallet-001
wallet-002
```

### Paso 2 — Depositar

```text
wallet-001 + 100000
wallet-002 + 20000
```

### Paso 3 — Retirar

```text
wallet-001 - 25000
```

### Paso 4 — Transferir

```text
wallet-001 -> wallet-002
50000
```

Estado esperado:

```text
wallet-001 = 25000
wallet-002 = 70000
```

### Paso 5 — Consultar Read Model

```http
GET /api/wallets/wallet-001/balance
GET /api/wallets/wallet-002/balance
```

### Paso 6 — Eliminar datos del Read Model

Ejecutar `POST /admin/projection/rebuild` o el procedimiento documentado equivalente.

Durante una pausa del procesamiento, PostgreSQL debe quedar vacío para las tablas de proyección.

### Paso 7 — Reproducir eventos

Reiniciar/recrear el consumidor de proyección desde `TAIL`.

### Paso 8 — Volver a consultar

Los resultados deben volver a ser:

```text
wallet-001 = 25000
wallet-002 = 70000
```

El Read Model debe quedar nuevamente reconstruido **sin ejecutar ningún Command adicional**.

Esto constituye la demostración principal de Event Sourcing + Projection Replay.

---

## 16. Demostración de consistencia eventual

La POC debe permitir observar que:

```text
Command -> EventStoreDB
```

puede completarse antes de:

```text
Event -> Projection -> PostgreSQL
```

Para hacer esto visible, proporcionar una configuración:

```text
READ_PROJECTION_DELAY_MS=0
```

Por defecto `0`.

Para la demo se puede configurar:

```text
READ_PROJECTION_DELAY_MS=3000
```

Con este valor, después de un depósito puede observarse temporalmente:

```text
Write Side / Event Store = actualizado
Read Model = todavía no actualizado
```

Después de la entrega del evento:

```text
Read Model = actualizado
```

No utilizar el Read Model para validar retiros o transferencias.

---

## 17. Concurrencia e invariantes

El Write Side debe probar al menos una situación de concurrencia.

Ejemplo:

```text
Saldo = 100000

Request A: Withdraw 80000
Request B: Withdraw 80000
```

El sistema no debe terminar con saldo negativo.

El diseño debe apoyarse en las garantías de consistencia/concurrencia de Axon y del modelo event-sourced.

No resolver esto mediante un `SELECT balance` en PostgreSQL.

Para `TransferMoney`, el objetivo es evitar que dos operaciones concurrentes utilicen un saldo obsoleto.

---

## 18. Idempotencia de Commands

Implementar idempotencia para `TransferMoney` mediante `transferId`/`Idempotency-Key`.

Una segunda solicitud con el mismo `transferId` no debe volver a transferir dinero.

Demostrar:

```text
Transfer T1: 50000 -> procesada
Transfer T1: 50000 -> no vuelve a mover saldo
```

La solución debe explicar claramente dónde se verifica la duplicidad.

---

## 19. Manejo de errores

Write Side:

- Wallet inexistente.
- Wallet duplicada.
- Monto <= 0.
- Moneda incorrecta.
- Fondos insuficientes.
- Transferencia hacia la misma wallet.
- Transferencia duplicada.
- Conflicto de concurrencia.

Read Side:

- evento desconocido: registrar error de forma explícita.
- evento duplicado: ignorarlo de forma idempotente.
- fallo de PostgreSQL: no confirmar exitosamente el procesamiento si eso ocasionaría pérdida lógica del evento.

No ocultar excepciones críticas con un `catch` genérico que simplemente devuelva `200 OK`.

---

## 20. Contrato de integración entre Write y Read

Crear una carpeta versionada para contratos, por ejemplo:

```text
contracts/events/
```

Debe contener JSON Schema o documentación equivalente para:

```text
WalletCreated v1
MoneyDeposited v1
MoneyWithdrawn v1
MoneyTransferred v1
```

La intención es demostrar que Java y TypeScript comparten un **contrato de eventos**, no clases.

No compartir un módulo compilado Java con el Read Side.

---

## 21. Estructura del repositorio

Proponer una estructura como:

```text
wallet-cqrs-poc/
│
├── write-service/
│   ├── src/main/java/com/example/wallet/
│   │   ├── domain/
│   │   │   ├── wallet/
│   │   │   │   ├── Wallet.java
│   │   │   │   ├── commands/
│   │   │   │   └── events/
│   │   │   └── transfer/
│   │   ├── application/
│   │   ├── infrastructure/
│   │   └── api/
│   ├── src/test/
│   └── pom.xml
│
├── read-service/
│   ├── src/
│   │   ├── api/
│   │   ├── projections/
│   │   ├── queries/
│   │   ├── events/
│   │   └── database/
│   ├── test/
│   └── package.json
│
├── contracts/
│   └── events/
│       ├── wallet-created.v1.schema.json
│       ├── money-deposited.v1.schema.json
│       ├── money-withdrawn.v1.schema.json
│       └── money-transferred.v1.schema.json
│
├── infrastructure/
│   ├── postgres/
│   │   └── init.sql
│   └── axon/
│       └── ...
│
├── compose.yml
├── .env.example
├── README.md
└── Makefile (opcional)
```

La implementación puede simplificar la estructura siempre que mantenga separación clara de responsabilidades.

---

## 22. Docker Compose

El `compose.yml` debe arrancar como mínimo:

```text
axon-server
postgres-read
write-service
read-service
```

Debe permitir:

```bash
docker compose up --build
```

y levantar toda la POC.

### Puertos sugeridos

```text
Write API       localhost:8081
Read API        localhost:8082
Axon Server UI  localhost:8024
Axon Server gRPC localhost:8124
PostgreSQL Read localhost:5433
```

No es necesario exponer PostgreSQL al host, pero hacerlo facilita la demo académica.

### Healthchecks

Configurar healthchecks para:

- Axon Server.
- PostgreSQL.
- Write Service.
- Read Service.

Usar `depends_on` con condiciones apropiadas cuando sea soportado por la implementación elegida, sin asumir que `depends_on` por sí solo significa "servicio listo".

---

## 23. Configuración mediante variables de entorno

Crear `.env.example`.

Ejemplos:

```text
AXON_SERVER_HOST=axon-server
AXON_SERVER_PORT=8124
AXON_SERVER_CONTEXT=wallet

READ_DB_HOST=postgres-read
READ_DB_PORT=5432
READ_DB_NAME=wallet_read
READ_DB_USER=wallet_read
READ_DB_PASSWORD=wallet_read_password

READ_PROJECTION_DELAY_MS=0
```

No hardcodear credenciales en múltiples archivos.

Para una POC local se permiten credenciales simples, pero deben declararse en `.env.example`.

---

## 24. Observabilidad mínima

No implementar un stack de observabilidad completo.

Sí implementar:

- logs estructurados o fácilmente legibles.
- `eventId` en logs.
- `walletId` cuando aplique.
- `transferId` cuando aplique.
- `correlationId` opcional pero recomendado.
- endpoint `/actuator/health` en Write Side.
- endpoint `/health` en Read Side.

El Read Side debe mostrar en logs algo como:

```text
Received event MoneyDeposited eventId=... walletId=wallet-001 amount=100000
Projection applied eventId=...
```

Esto facilita la demostración en clase.

---

## 25. Testing obligatorio

### Write Side

Tests unitarios para:

- crear wallet.
- depositar.
- retirar con fondos.
- rechazar retiro sin fondos.
- rechazar monto <= 0.
- transferencia válida.
- transferencia hacia sí misma.
- transferencia sin fondos.
- doble transferencia con mismo `transferId`.
- reconstrucción del estado a partir de eventos.

### Read Side

Tests para:

- `WalletCreated` crea la proyección.
- `MoneyDeposited` incrementa saldo.
- `MoneyWithdrawn` decrementa saldo.
- `MoneyTransferred` actualiza ambas wallets.
- evento duplicado no duplica movimientos.
- reconstrucción desde cero produce el mismo estado.

### Integration/E2E

Proporcionar al menos un test E2E que:

1. cree wallets.
2. deposite.
3. retire.
4. transfiera.
5. consulte el Read Model.
6. elimine/reconstruya el Read Model.
7. consulte nuevamente y verifique igualdad.

---

## 26. README obligatorio

La raíz del proyecto debe contener un `README.md` que explique:

1. Qué es CQRS.
2. Qué es Event Sourcing.
3. Por qué Axon Server es el Event Store.
4. Por qué PostgreSQL es el Read Model.
5. Por qué el Read Side está en TypeScript y el Write Side en Java.
6. Cómo arrancar Docker Compose.
7. Cómo crear wallets.
8. Cómo depositar.
9. Cómo retirar.
10. Cómo transferir.
11. Cómo consultar el saldo.
12. Cómo demostrar consistencia eventual.
13. Cómo borrar el Read Model.
14. Cómo ejecutar el replay.
15. Cómo demostrar que PostgreSQL se reconstruye solo desde eventos.
16. Limitaciones de la POC.
17. Qué componentes deberían agregarse para producción.

Incluir un diagrama Mermaid de la arquitectura.

---

## 27. Definition of Done

La implementación se considera terminada únicamente cuando todos estos puntos se cumplen:

- [ ] `docker compose up --build` levanta la solución sin pasos manuales complejos.
- [ ] Write Side está implementado en Java/Spring Boot/Axon Framework 5.
- [ ] Read Side está implementado en TypeScript/NestJS.
- [ ] Event Store es Axon Server.
- [ ] Read Model es PostgreSQL separado.
- [ ] Existen `CreateWallet`, `DepositMoney`, `WithdrawMoney` y `TransferMoney`.
- [ ] Existen `WalletCreated`, `MoneyDeposited`, `MoneyWithdrawn` y `MoneyTransferred`.
- [ ] El estado de Wallet del Write Side se reconstruye mediante eventos.
- [ ] El Read Side recibe eventos desde Axon Server sin depender de Axon Framework.
- [ ] El Read Side actualiza PostgreSQL mediante projections.
- [ ] Las Queries consultan exclusivamente PostgreSQL.
- [ ] El Write Side nunca consulta PostgreSQL para resolver reglas de negocio.
- [ ] Existe idempotencia de proyección mediante `eventId`.
- [ ] Existe idempotencia de transferencia mediante `transferId`.
- [ ] Se evita saldo negativo.
- [ ] Transferencia consistente entre dos wallets.
- [ ] Existe mecanismo de replay/rebuild.
- [ ] Es posible borrar las tablas del Read Model y reconstruirlas desde Axon Server.
- [ ] La demo de replay está documentada y reproducible.
- [ ] Existe al menos una prueba de concurrencia/invariante.
- [ ] Existe una demostración opcional de consistencia eventual usando `READ_PROJECTION_DELAY_MS`.
- [ ] No existe UI propia.
- [ ] Toda la funcionalidad principal puede demostrarse usando HTTP.

---

## 28. Restricciones de alcance

No implementar en esta POC:

- autenticación/autorización real.
- múltiples monedas con conversión.
- KYC.
- conciliación bancaria.
- pagos externos.
- web frontend.
- notificaciones externas.
- Kafka.
- Redis.
- Elasticsearch/OpenSearch.
- Kubernetes.
- alta disponibilidad de Axon Server.
- multi-región.
- observabilidad avanzada.

El objetivo es enseñar CQRS + Event Sourcing, no construir una wallet financiera lista para producción.

---

## 29. Criterios académicos que la implementación debe hacer evidentes

La solución debe permitir explicar y demostrar estas afirmaciones:

### CQRS

```text
Commands -> Write Model
Queries  -> Read Model
```

### Event Sourcing

```text
Current State = replay(Domain Events)
```

### Projection

```text
Domain Events -> Projection -> Read Model
```

### Eventual Consistency

```text
Event committed != Read Model immediately updated
```

### Rebuildability

```text
Delete Read Model
       +
Replay Event Store
       =
Reconstructed Read Model
```

### Separación tecnológica

```text
Java/Spring/Axon       TypeScript/NestJS
       |                      |
       v                      v
Axon Server            PostgreSQL
```

---

## 30. Referencias técnicas a verificar durante la implementación

La implementación debe verificar la documentación oficial de las versiones realmente utilizadas, especialmente para el mecanismo de integración HTTP del event handler externo y la API de DCB.

- Axon Framework: https://docs.axoniq.io/axon-framework-reference/
- Axon Server 2026.0: https://docs.axoniq.io/axon-server-reference/v2026.0/
- Axon Server integrations: https://docs.axoniq.io/axon-server-reference/v2025.0/axon-server/administration/integration/
- Axon Framework Spring Boot: https://docs.axoniq.io/axon-framework-reference/development/spring-boot-integration/
- Dynamic Consistency Boundary: https://docs.axoniq.io/axon-framework-reference/development/events/event-store-internals/
- PostgreSQL: https://www.postgresql.org/docs/
- NestJS: https://docs.nestjs.com/

---

## 31. Instrucción final para el agente de desarrollo

Implementa la POC completa siguiendo este documento como especificación funcional y técnica.

Prioridades, en este orden:

1. Correctitud del dominio y Event Sourcing.
2. Separación CQRS real entre Write y Read.
3. Persistencia de eventos en Axon Server.
4. Entrega de eventos a NestJS mediante integración compatible con Axon Server.
5. Projection idempotente hacia PostgreSQL.
6. Replay/rebuild verificable.
7. Docker Compose reproducible.
8. Tests y README para demostración académica.
9. Simplicidad operacional.

No sustituir la arquitectura por un CRUD tradicional con dos APIs.
No guardar el saldo del Write Model como fuente primaria en PostgreSQL.
No consultar Axon Server desde los endpoints de Query para calcular el saldo.
No introducir tecnologías adicionales sin justificar por qué son necesarias.

El resultado final debe permitir a un estudiante observar el siguiente flujo de extremo a extremo:

```text
HTTP Command
    -> Java
    -> Wallet / Domain Rules
    -> Domain Event
    -> Axon Server Event Store
    -> Event Stream
    -> NestJS Projection
    -> PostgreSQL Read Model
    -> HTTP Query
```

Y finalmente demostrar:

```text
PostgreSQL Read Model eliminado
          |
          v
Replay de eventos en Axon Server
          |
          v
NestJS Projection
          |
          v
PostgreSQL reconstruido
```
