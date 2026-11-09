# syntax=docker/dockerfile:1
# 그늘 백엔드 멀티아키텍처 이미지. 멀티스테이지로 빌드 도구와 런타임을 분리한다.

# --- build stage ---
FROM eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

# --- run stage ---
FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
ARG APP_VERSION=unknown
LABEL org.opencontainers.image.source="https://github.com/ghdtjdwn/geuneul" \
      org.opencontainers.image.revision="${APP_VERSION}"
WORKDIR /app
RUN groupadd --system geuneul && useradd --system --gid geuneul --home-dir /app --shell /usr/sbin/nologin geuneul
# bootJar만 복사. `-plain.jar`(라이브러리 jar)는 `*-SNAPSHOT.jar` 글롭에 미매치되어 제외됨.
COPY --from=build --chown=geuneul:geuneul /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
USER geuneul
# 컨테이너 메모리에 맞춘 힙 자동조정 + $PORT는 application.yml의 server.port가 처리.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
