# Time Logger Service

Spring Boot приложение для записи времени в базу данных.

## Описание

Сервис записывает текущее время в PostgreSQL каждую секунду и предоставляет REST API для получения записей.

## Быстрый старт

### Локальный запуск

1. Запустите PostgreSQL:
```bash
docker run -d --name postgres \
  -e POSTGRES_DB=timelogger \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:postgis/postgis:16-3.4-alpine
```

2. Соберите и запустите приложение:
```bash
./gradlew bootRun
```

## Swagger UI

Для локальной разработки доступна интерактивная документация API:

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/v3/api-docs

## REST API

### Endpoints

| Endpoint | Метод | Описание | Авторизация |
|----------|-------|----------|-------------|
| `/api/times` | GET | Записи времени | Basic Auth |
| `/api/times/count` | GET | Количество записей | Basic Auth |

> Для доступа требуются креды: `admin` / `timelogger2026`

### Примеры запросов

```bash
# Получить все записи
curl -u admin:timelogger2026 http://localhost:8080/api/times

# Получить количество записей
curl -u admin:timelogger2026 http://localhost:8080/api/times/count

# Health check (без авторизации)
curl http://localhost:8080/actuator/health
```

## Конфигурация

### Переменные окружения

| Переменная | Описание | Значение по умолчанию                         |
|------------|----------|-----------------------------------------------|
| `SPRING_DATASOURCE_URL` | URL базы данных | `jdbc:postgresql://localhost:5432/timelogger` |
| `SPRING_DATASOURCE_USERNAME` | Пользователь БД | `postgres`                                    |
| `SPRING_DATASOURCE_PASSWORD` | Пароль БД | `postgres`                                    |
| `APP_SECURITY_USERNAME` | Логин для API | `admin`                                       |
| `APP_SECURITY_PASSWORD` | Пароль для API | `timelogger2026`                              |
| `TIMELOGGER_MAX_BUFFER_SIZE` | Макс. размер буфера | `10000`                                       |
| `TIMELOGGER_BATCH_SIZE` | Размер batch-записи | `100`                                         |
| `SPRING_PROFILES_ACTIVE` | Активный профиль | `prod`                                        |

## Тестирование

```bash
# Запуск всех тестов
./gradlew test

# Запуск только unit-тестов
./gradlew test --tests "*Test"

# Запуск интеграционных тестов
./gradlew test --tests "*IntegrationTest"
```
