FROM gcr.io/hwyhaul-backend/custom-images/openjdk:21-jdk

# System libraries required by Playwright's headless Chromium.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libnss3 \
        libnspr4 \
        libdbus-1-3 \
        libatk1.0-0 \
        libatk-bridge2.0-0 \
        libcups2 \
        libdrm2 \
        libatspi2.0-0 \
        libx11-6 \
        libxcomposite1 \
        libxdamage1 \
        libxext6 \
        libxfixes3 \
        libxrandr2 \
        libgbm1 \
        libxcb1 \
        libxkbcommon0 \
        libpango-1.0-0 \
        libcairo2 \
        libasound2 \
    && rm -rf /var/lib/apt/lists/*

COPY ./itrade-load-creation-agent-infrastructure/target/itrade-load-creation-agent-infrastructure_*.jar /tmp/itrade-load-creation-agent.jar

# Pre-bake the Chromium binary into the image so no download happens at runtime.
# The jar is a Spring Boot fat jar, so the Playwright CLI is launched via the
# Spring Boot loader (new launch package for Boot 3.2+, old package as fallback).
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN (java -Dloader.main=com.microsoft.playwright.CLI \
        -cp /tmp/itrade-load-creation-agent.jar \
        org.springframework.boot.loader.launch.PropertiesLauncher install chromium \
     || java -Dloader.main=com.microsoft.playwright.CLI \
        -cp /tmp/itrade-load-creation-agent.jar \
        org.springframework.boot.loader.PropertiesLauncher install chromium) \
    && chmod -R a+rX /ms-playwright

EXPOSE 8080

CMD ["java", "-jar", "-Duser.timezone=UTC", "-Denv=${ENV}", "-Dspring.profiles.active=${ENV}", "/tmp/itrade-load-creation-agent.jar"]