package ru.stas.pupkov.projecttest.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import ru.stas.pupkov.projecttest.service.TimeRecordService;

import java.util.concurrent.TimeUnit;

/**
 * Кастомные метрики для мониторинга сервиса Time Logger.
 * 
 * <p>Регистрирует следующие метрики в Micrometer:
 * <ul>
 *   <li>timelogger.buffer.size - текущий размер буфера</li>
 *   <li>timelogger.buffer.max - максимальный размер буфера</li>
 *   <li>timelogger.db.available - статус БД (1/0)</li>
 *   <li>timelogger.records.written - счётчик записанных записей</li>
 *   <li>timelogger.records.dropped - счётчик потерянных записей</li>
 *   <li>timelogger.db.write.duration - время записи в БД</li>
 * </ul>
 */
@Component
public class TimeRecordMetrics {

    private final Timer writeTimer;
    private final Counter slowWriteCounter;

    /**
     * Создаёт и регистрирует все метрики.
     *
     * @param registry    реестр метрик Micrometer
     * @param service     сервис записи времени
     */
    public TimeRecordMetrics(MeterRegistry registry, TimeRecordService service) {
        // Gauge: текущий размер буфера
        Gauge.builder("timelogger.buffer.size", service, TimeRecordService::getBufferSize)
                .description("Текущий размер буфера записей")
                .register(registry);

        // Gauge: статус БД (1 = доступна, 0 = недоступна)
        Gauge.builder("timelogger.db.available", service, 
                      s -> s.isDatabaseAvailable() ? 1.0 : 0.0)
                .description("Статус доступности базы данных")
                .register(registry);

        // Gauge: количество записанных записей
        Gauge.builder("timelogger.records.written", service, 
                      s -> (double) s.getWrittenRecordsCount())
                .description("Общее количество записанных записей")
                .register(registry);

        // Gauge: количество потерянных записей
        Gauge.builder("timelogger.records.dropped", service, 
                      s -> (double) s.getDroppedRecordsCount())
                .description("Количество потерянных записей из-за переполнения буфера")
                .register(registry);

        // Timer: время записи в БД
        this.writeTimer = Timer.builder("timelogger.db.write.duration")
                .description("Время записи пачки записей в БД")
                .register(registry);

        // Counter: количество медленных записей
        this.slowWriteCounter = Counter.builder("timelogger.db.write.slow.count")
                .description("Количество медленных записей в БД (>1 сек)")
                .register(registry);
    }

    /**
     * Записывает время выполнения операции записи в БД.
     *
     * @param durationMs    длительность в миллисекундах
     */
    public void recordWriteDuration(long durationMs) {
        writeTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Увеличивает счётчик медленных записей.
     */
    public void incrementSlowWrites() {
        slowWriteCounter.increment();
    }
}
