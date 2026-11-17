# syntax=docker/dockerfile:1
# 그늘 백엔드 멀티아키텍처 이미지. 멀티스테이지로 빌드 도구와 런타임을 분리한다.

# --- build stage ---
FROM eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07 AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

# --- run stage ---
FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
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
