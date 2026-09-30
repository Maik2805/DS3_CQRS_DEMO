# Pinned Tool / Library Versions

This note records the concrete, verified versions for the Wallet CQRS + Event Sourcing POC.
No component uses `latest`. All versions below were confirmed against official registries/release
pages on the verification date noted at the bottom. Subsequent tasks MUST use these exact versions.

## Pinned versions

| Component | Pinned version | Notes |
| --- | --- | --- |
| **Axon Framework** | `5.3.0` | Stable release on the 5.x line confirmed to exist in Maven Central. Use the `org.axonframework:axon-framework-bom:5.3.0` BOM to align all Axon module versions. The Axon 5 Spring Boot starter is `org.axonframework.extensions.spring:axon-spring-boot-starter`. |
| **Spring Boot** | `4.1.1` | Latest patch on the 4.1.x line (4.1.0 GA released 2026-06-10; 4.1.1 is the current patch). |
| **Axon Server (Docker image)** | `docker.axoniq.io/axoniq/axonserver:2026.0.6` | Latest available `2026.0.x` patch tag confirmed to exist in the registry. The `2026.0.6-jdk-21` and `2026.0.6-nonroot` variants also exist; the plain `2026.0.6` tag runs on a Java 21 runtime. |
| **Java (JDK)** | `21` (LTS) | Required by Axon Framework 5 (JDK 21 minimum). |
| **Node.js** | `22.x` (LTS "Jod") | Active LTS, compatible with NestJS 11 (which requires Node >= 20.19). Node 22 is used for the Read Service and its Docker base image (`node:22`). |
| **NestJS** | `11.x` | Read Service framework (no Axon Framework). Express v5 is the default HTTP adapter in NestJS 11. |
| **PostgreSQL (Docker image)** | `postgres:16` | Read Model store. Requirement is PostgreSQL 16+; pinned to the `16` major line for the POC. |

## Compatibility notes

- **Axon Framework 5 requires JDK 21.** The Write Service targets Java 21.
- **Axon Framework 5.0.3+ supports Spring Boot 4.** The pinned Axon FW `5.3.0` therefore supports the pinned Spring Boot `4.1.1`.
- **Axon Server 2025.2+ supports Dynamic Consistency Boundaries (DCB).** The pinned Axon Server `2026.0.6` is well beyond that line and supports DCB, which the transfer flow relies on (design §Transfer + DCB; the concrete DCB API is still to be verified in task 5.1 against the pinned Axon FW 5.3.0 docs).
- **Axon Server 2026.0.x images run on a Java 21 runtime** (the base `2026.0.6` tag; `-jdk-21` variants are explicit).
- **NestJS 11 requires Node.js >= 20.19**; Node 22 LTS satisfies this and is the pinned runtime for the Read Service.

## Available adjacent versions (for reference)

- Axon Framework BOM on the 5.x line available at verification time: `5.0.0`–`5.0.5`, `5.1.x`, `5.2.0`–`5.2.3`, `5.3.0`, `5.3.1` (`5.3.2` was not found in Maven Central). Pinned: **5.3.0**.
- Axon Server `2026.0.x` tags available: `2026.0.0` through `2026.0.6` (plus `-jdk-21`, `-nonroot`, `-hardened` variants). Pinned: **2026.0.6** (latest patch).
- Spring Boot 4.x latest patches: `4.0.7` (4.0.x line) and `4.1.1` (4.1.x line, current). Pinned: **4.1.1**.

## Source and date of verification

- **Verification date:** 2026-08 (latest release data observed: Axon Server `2026.0.6` pushed 2026-08-05; Spring Boot 4.1 line current at `4.1.1`).
- **Sources:**
  - Axon Framework versions: Maven Central — [`org.axonframework:axon-framework-bom`](https://central.sonatype.com/artifact/org.axonframework/axon-framework-bom/versions) and [AxonFramework GitHub releases](https://github.com/AxonFramework/AxonFramework/releases).
  - Axon Server image tags: [Docker Hub `axoniq/axonserver` tags](https://hub.docker.com/r/axoniq/axonserver/tags) (registry tag list confirmed `2026.0.0`–`2026.0.6`).
  - Spring Boot versions: [spring-projects/spring-boot GitHub releases](https://github.com/spring-projects/spring-boot/releases) and the [Spring Boot 4.1 Release Notes wiki](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes/Supported-Versions).
  - NestJS 11 / Node compatibility: [NestJS 11 migration guide](https://docs.nestjs.com/migration-guide) (Node >= 20.19 requirement).
  - PostgreSQL: [official `postgres` Docker image](https://hub.docker.com/_/postgres).

Content was rephrased for compliance with licensing restrictions.
