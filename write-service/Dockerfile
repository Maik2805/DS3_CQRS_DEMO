# syntax=docker/dockerfile:1

# --- Stage 1: build with Maven + JDK 21 ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies first for faster rebuilds.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- Stage 2: minimal JRE 21 runtime ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

COPY --from=build /build/target/*.jar app.jar

# Write API (container-internal port; compose maps it to host 8081).
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
