# syntax=docker/dockerfile:1
# Multi-stage build: web assets, then the Spring Boot service with the assets embedded, then a JRE-only runtime.

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

FROM eclipse-temurin:25-jre
RUN groupadd --system elepay && useradd --system --gid elepay --uid 10001 --no-create-home elepay
WORKDIR /app
COPY --from=build /app.jar /app/elepay.jar
USER 10001
EXPOSE 8080 9090
ENV SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/elepay.jar"]
