# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Bước 1: build bằng Maven + JDK 21
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy pom.xml trước để tận dụng cache: chỉ khi pom đổi mới phải tải lại dependency.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:go-offline

# Copy source sau, để sửa code không làm mất cache dependency ở layer trên.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

# ---------------------------------------------------------------------------
# Bước 2: image chạy, chỉ chứa JRE và file jar
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-jammy

LABEL org.opencontainers.image.title="savings-platform" \
      org.opencontainers.image.description="Nền tảng tiết kiệm: ví, gói gửi tiết kiệm, lãi, thanh toán"

ENV TZ=Asia/Ho_Chi_Minh \
    JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

# Chạy bằng user thường, không dùng root.
RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --create-home --home-dir /app app

WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar /app/app.jar

USER app
EXPOSE 8080

# Dùng sh -c để biến JAVA_OPTS được thay giá trị lúc chạy. exec để java nhận
# signal trực tiếp từ Docker (SIGTERM) và tắt máy đúng cách.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
