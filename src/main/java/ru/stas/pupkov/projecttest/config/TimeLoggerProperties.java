package ru.stas.pupkov.projecttest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурационные свойства сервиса Time Logger.
 *
 * @param maxBufferSize         максимальный размер буфера записей
 * @param batchSize             размер пачки для batch-записи в БД
 * @param slowWriteThresholdMs  порог для логирования медленных записей (мс)
 */
@ConfigurationProperties(prefix = "timelogger")
public record TimeLoggerProperties(int maxBufferSize, int batchSize, int slowWriteThresholdMs) {

    public TimeLoggerProperties {
        if (maxBufferSize <= 0) {
            maxBufferSize = 10000;
        }
        if (batchSize <= 0) {
            batchSize = 100;
        }
        if (slowWriteThresholdMs <= 0) {
            slowWriteThresholdMs = 1000;
        }
    }
}
