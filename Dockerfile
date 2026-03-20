# ─── Build stage ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q

COPY src/ src/
RUN ./mvnw package -DskipTests -q

# ─── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=build /app/target/broker-mcp-*.jar app.jar

# Default to HTTP (streamable) mode. Set SPRING_PROFILES_ACTIVE= (empty) to run in
# stdio mode instead (e.g. for Claude Desktop stdio-docker config).
ENV SPRING_PROFILES_ACTIVE=http
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
