# Stage 1: Build
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# Копируем файлы Gradle
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Даём права на выполнение
RUN chmod +x gradlew

# Скачиваем зависимости (кэшируется при неизменных build.gradle)
RUN ./gradlew dependencies --no-daemon || true

# Копируем исходный код
COPY src src

# Собираем приложение
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Создаём пользователя для безопасности
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

# Копируем собранный JAR
COPY --from=builder /app/build/libs/*.jar app.jar

# Меняем владельца
RUN chown -R appuser:appgroup /app

# Переключаемся на непривилегированного пользователя
USER appuser

# Открываем порт
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# Точка входа
ENTRYPOINT ["java", "-jar", "app.jar"]
