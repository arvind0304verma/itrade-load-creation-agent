FROM gcr.io/hwyhaul-backend/custom-images/openjdk:21-jdk

# System libraries required by Playwright's headless Chromium. The base image is
# not guaranteed to be Debian/Ubuntu, so detect the package manager and install
# the distro-appropriate package set. /etc/os-release is printed for diagnostics.
RUN set -eux; \
    cat /etc/os-release 2>/dev/null || true; \
    if command -v apt-get >/dev/null 2>&1; then \
        apt-get update && apt-get install -y --no-install-recommends \
            libnss3 libnspr4 libdbus-1-3 libatk1.0-0 libatk-bridge2.0-0 libcups2 \
            libdrm2 libatspi2.0-0 libx11-6 libxcomposite1 libxdamage1 libxext6 \
            libxfixes3 libxrandr2 libgbm1 libxcb1 libxkbcommon0 libpango-1.0-0 \
            libcairo2 libasound2 \
        && rm -rf /var/lib/apt/lists/*; \
    elif command -v microdnf >/dev/null 2>&1; then \
        microdnf install -y \
            nss nspr dbus-libs atk at-spi2-atk cups-libs libdrm at-spi2-core \
            libX11 libXcomposite libXdamage libXext libXfixes libXrandr mesa-libgbm \
            libxcb libxkbcommon pango cairo alsa-lib \
        && microdnf clean all; \
    elif command -v dnf >/dev/null 2>&1; then \
        dnf install -y \
            nss nspr dbus-libs atk at-spi2-atk cups-libs libdrm at-spi2-core \
            libX11 libXcomposite libXdamage libXext libXfixes libXrandr mesa-libgbm \
            libxcb libxkbcommon pango cairo alsa-lib \
        && dnf clean all; \
    elif command -v yum >/dev/null 2>&1; then \
        yum install -y \
            nss nspr dbus-libs atk at-spi2-atk cups-libs libdrm at-spi2-core \
            libX11 libXcomposite libXdamage libXext libXfixes libXrandr mesa-libgbm \
            libxcb libxkbcommon pango cairo alsa-lib \
        && yum clean all; \
    elif command -v apk >/dev/null 2>&1; then \
        echo "ERROR: Alpine/musl base detected. Playwright's bundled Chromium needs glibc;" \
             "install the system chromium package and set PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH instead." >&2; \
        exit 1; \
    else \
        echo "ERROR: no supported package manager (apt-get/microdnf/dnf/yum) found in base image." >&2; \
        exit 1; \
    fi

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