# Time Logger Service

Production-ready Spring Boot приложение для записи времени в базу данных.

## Описание

Сервис записывает текущее время в PostgreSQL каждую секунду и предоставляет REST API для получения записей. Реализована отказоустойчивость при недоступности БД с буферизацией данных.

## Технологический стек

| Компонент | Технология |
|-----------|------------|
| Framework | Spring Boot 4.0.1 |
| База данных | PostgreSQL 16 |
| ORM | Spring Data JPA |
| Миграции | Liquibase |
| API спецификация | OpenAPI 3.0.3 (API-first) |
| Генерация кода | openapi-generator |
| Маппинг DTO | MapStruct 1.5.x |
| Безопасность | Spring Security (Basic Auth) |
| Логирование | Logback + JSON (prod) |
| Контейнеризация | Docker + docker-compose |
| Тестирование | JUnit 5, Testcontainers |

## Архитектура

```
┌─────────────────────────────────────────────────────────┐
│                  Spring Boot Application                 │
│  ┌─────────────┐   ┌─────────────┐   ┌───────────────┐  │
│  │  Scheduler  │──▶│   Buffer    │──▶│    Service    │  │
│  │ (1 sec)     │   │ (FIFO Queue)│   │ (batch write) │  │
│  └─────────────┘   └─────────────┘   └───────┬───────┘  │
│                                              │          │
│  ┌─────────────┐                    ┌────────▼────────┐ │
│  │ Controller  │◀───────────────────│   Repository    │ │
│  │ (REST API)  │                    │   (JPA)         │ │
│  └─────────────┘                    └────────┬────────┘ │
└──────────────────────────────────────────────┼──────────┘
                                               │
                                      ┌────────▼────────┐
                                      │   PostgreSQL    │
                                      └─────────────────┘
```

## Быстрый старт

### Запуск через Docker Compose

```bash
# Запуск приложения и БД
docker-compose up -d

# Проверка статуса
docker-compose ps

# Просмотр логов
docker-compose logs -f app
```

### Запуск локально

1. Запустите PostgreSQL:
```bash
docker run -d --name postgres \
  -e POSTGRES_DB=timelogger \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine
```

2. Соберите и запустите приложение:
```bash
./gradlew bootRun
```

## REST API

### Endpoints

| Endpoint | Метод | Описание | Авторизация |
|----------|-------|----------|-------------|
| `/api/times` | GET | Все записи времени | Basic Auth |
| `/api/times/count` | GET | Количество записей | Basic Auth |
| `/api/status` | GET | Статус сервиса | Basic Auth |
| `/actuator/health` | GET | Health check | Публичный |
| `/swagger-ui.html` | GET | Документация API | Basic Auth |

### Примеры запросов

```bash
# Получить все записи
curl -u admin:timelogger2024 http://localhost:8080/api/times

# Получить количество записей
curl -u admin:timelogger2024 http://localhost:8080/api/times/count

# Получить статус сервиса
curl -u admin:timelogger2024 http://localhost:8080/api/status

# Health check (без авторизации)
curl http://localhost:8080/actuator/health
```

### Пример ответа GET /api/times

```json
[
  {
    "id": 1,
    "recordedAt": "2024-01-17T12:00:00"
  },
  {
    "id": 2,
    "recordedAt": "2024-01-17T12:00:01"
  }
]
```

### Пример ответа GET /api/status

```json
{
  "bufferSize": 0,
  "maxBufferSize": 10000,
  "dbAvailable": true,
  "totalRecords": 1000,
  "droppedRecords": 0
}
```

## Конфигурация

### Переменные окружения

| Переменная | Описание | Значение по умолчанию |
|------------|----------|----------------------|
| `SPRING_DATASOURCE_URL` | URL базы данных | `jdbc:postgresql://localhost:5432/timelogger` |
| `SPRING_DATASOURCE_USERNAME` | Пользователь БД | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | Пароль БД | `postgres` |
| `APP_SECURITY_USERNAME` | Логин для API | `admin` |
| `APP_SECURITY_PASSWORD` | Пароль для API | `timelogger2024` |
| `TIMELOGGER_MAX_BUFFER_SIZE` | Макс. размер буфера | `10000` |
| `TIMELOGGER_BATCH_SIZE` | Размер batch-записи | `100` |
| `SPRING_PROFILES_ACTIVE` | Активный профиль | `prod` |

## Ключевые архитектурные решения

### 1. Буферизация данных

- Используется `ConcurrentLinkedQueue<LocalDateTime>` для потокобезопасного хранения
- FIFO порядок гарантирует хронологическую последовательность
- Записи сначала попадают в буфер, затем batch-записываются в БД

### 2. Обработка недоступности БД

- При ошибке подключения данные остаются в буфере
- Повторные попытки подключения каждые 5 секунд
- Логирование состояния соединения
- После восстановления — запись всех накопленных данных

### 3. Защита от медленной БД

| Механизм | Описание |
|----------|----------|
| Асинхронная запись | `@Async` — scheduler не блокируется |
| Batch-запись | Один SQL INSERT вместо N |
| Backpressure | Ограничение буфера, старые записи отбрасываются |
| Таймауты | HikariCP connection-timeout=5000ms |
| Мониторинг | WARNING при записи > 1 секунды |

### 4. Хронологический порядок без сортировки

- `SERIAL` (IDENTITY) primary key — автоинкремент при INSERT
- FIFO извлечение из очереди
- `findAll()` возвращает записи в порядке id

### 5. Graceful Shutdown

- При остановке приложения все данные из буфера сохраняются в БД
- Таймаут 30 секунд на завершение

## Мониторинг

### Actuator Endpoints

```bash
# Health check
curl http://localhost:8080/actuator/health

# Метрики
curl -u admin:timelogger2024 http://localhost:8080/actuator/metrics

# Кастомные метрики
curl -u admin:timelogger2024 http://localhost:8080/actuator/metrics/timelogger.buffer.size
```

### Кастомные метрики

| Метрика | Описание |
|---------|----------|
| `timelogger.buffer.size` | Текущий размер буфера |
| `timelogger.db.available` | Статус БД (1/0) |
| `timelogger.records.written` | Счётчик записанных записей |
| `timelogger.records.dropped` | Потерянные записи (backpressure) |
| `timelogger.db.write.duration` | Время записи batch |
| `timelogger.db.write.slow.count` | Количество медленных записей |

## Тестирование

```bash
# Запуск всех тестов
./gradlew test

# Запуск только unit-тестов
./gradlew test --tests "*Test"

# Запуск интеграционных тестов
./gradlew test --tests "*IntegrationTest"
```

## Структура проекта

```
src/main/java/ru/stas/pupkov/projecttest/
├── ProjectTestApplication.java     # Главный класс
├── config/
│   ├── SecurityConfig.java         # Basic Auth
│   ├── AsyncConfig.java            # Async executor
│   └── TimeLoggerProperties.java   # @ConfigurationProperties
├── entity/
│   └── TimeRecord.java             # JPA Entity
├── repository/
│   └── TimeRecordRepository.java   # Spring Data JPA
├── mapper/
│   └── TimeRecordMapper.java       # MapStruct
├── service/
│   └── TimeRecordService.java      # Бизнес-логика
├── scheduler/
│   └── TimeRecordScheduler.java    # Scheduled tasks
├── metrics/
│   └── TimeRecordMetrics.java      # Micrometer метрики
├── exception/
│   ├── DatabaseUnavailableException.java
│   └── GlobalExceptionHandler.java # @ControllerAdvice
└── controller/
    └── TimeRecordController.java   # REST API

src/main/resources/
├── api/
│   └── api.yaml                    # OpenAPI 3.0.3
├── application.properties
├── logback-spring.xml
└── db/changelog/                   # Liquibase миграции
```

## Лицензия

MIT License
