# syntax=docker/dockerfile:1
#
# Multi-stage build for the itrade-load-creation-agent.
#
# This image contains TWO jars:
#   /app/spring-boot-agent.jar  -> the main Spring Boot web app (listens on 8080)
#   /app/java-mcp-server.jar    -> stdio MCP server, spawned as a subprocess by the
#                                  agent (see McpProcessConfig#buildCommand -> "java -jar <MCP_JAR_PATH>")
#
# The runtime base is the Microsoft Playwright Java image so headless Chromium and
# all its system dependencies are present (both modules use Playwright 1.44.0).
#
# Build context MUST be the repository root (see .dockerignore), e.g.:
#   docker build -f ./devops/Dockerfile -t <image>:<tag> .

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy POMs first so dependency resolution can be cached independently of source changes.
COPY java-mcp-server/pom.xml java-mcp-server/pom.xml
COPY spring-boot-agent/pom.xml spring-boot-agent/pom.xml

# Copy sources and build both modules into executable fat jars.
COPY java-mcp-server/src java-mcp-server/src
COPY spring-boot-agent/src spring-boot-agent/src

RUN mvn -B -f java-mcp-server/pom.xml clean package -DskipTests \
 && mvn -B -f spring-boot-agent/pom.xml clean package -DskipTests

# ---------- runtime stage ----------
FROM mcr.microsoft.com/playwright/java:v1.44.0-jammy
WORKDIR /app

COPY --from=build /workspace/spring-boot-agent/target/spring-boot-agent.jar /app/spring-boot-agent.jar
COPY --from=build /workspace/java-mcp-server/target/java-mcp-server-*.jar /app/java-mcp-server.jar

# Where the agent looks for the MCP jar it launches as a subprocess.
ENV MCP_JAR_PATH=/app/java-mcp-server.jar \
    AGENT_OMSR_HEADLESS=true \
    AGENT_OMSR_DEBUG_DIRECTORY=/tmp/playwright-debug/omsr

EXPOSE 8080

# All environment-specific configuration (URLs, Mongo URI, credentials) is supplied
# via environment variables at deploy time — see docs/deployment-configuration.md.
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/spring-boot-agent.jar"]
