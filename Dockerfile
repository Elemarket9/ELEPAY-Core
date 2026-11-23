# syntax=docker/dockerfile:1
# Multi-stage build: web assets, then the Spring Boot service with the assets embedded, then a JRE-only runtime.

# TODO(supply-chain): pin base images by digest (e.g. node:22-alpine@sha256:...) once the intended
# digests are verified. Do NOT guess a digest — a wrong digest breaks the build. Keep the current tags
# until the digests are confirmed from the registry.
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
ARG VITE_OIDC_ISSUER=""
ARG VITE_OIDC_CLIENT_ID=""
ENV VITE_OIDC_ISSUER=$VITE_OIDC_ISSUER VITE_OIDC_CLIENT_ID=$VITE_OIDC_CLIENT_ID
RUN npm run build

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -B -q -DskipTests package && cp target/elepay-core-*.jar /app.jar

# TODO(supply-chain): pin this base image by digest (eclipse-temurin:25-jre@sha256:...) once verified.
# Do NOT guess a digest; a wrong digest breaks the build. Keep the current tag until confirmed.
FROM eclipse-temurin:25-jre
# curl is used only by the container HEALTHCHECK below.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system elepay && useradd --system --gid elepay --uid 10001 --no-create-home elepay
WORKDIR /app
COPY --from=build /app.jar /app/elepay.jar
USER 10001
EXPOSE 8080 9090
ENV SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Liveness/readiness via the management health endpoint (management.server.port default 9090, bound to
# loopback). Override the port with ELEPAY_MANAGEMENT_PORT at build/runtime if changed.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:9090/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/elepay.jar"]
