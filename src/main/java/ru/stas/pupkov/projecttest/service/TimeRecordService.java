package ru.stas.pupkov.projecttest.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.stas.pupkov.projecttest.config.TimeLoggerProperties;
import ru.stas.pupkov.projecttest.entity.TimeRecord;
import ru.stas.pupkov.projecttest.exception.DatabaseUnavailableException;
import ru.stas.pupkov.projecttest.mapper.TimeRecordMapper;
import ru.stas.pupkov.projecttest.model.StatusResponse;
import ru.stas.pupkov.projecttest.model.TimeRecordResponse;
import ru.stas.pupkov.projecttest.repository.TimeRecordRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class TimeRecordService {

    private final TimeRecordRepository repository;
    private final TimeRecordMapper mapper;
    private final TimeLoggerProperties properties;

    /**
     * Потокобезопасный буфер для хранения временных меток.
     * FIFO порядок гарантирует хронологическую последовательность.
     */
    private final ConcurrentLinkedQueue<LocalDateTime> buffer = new ConcurrentLinkedQueue<>();

    /**
     * Флаг доступности базы данных.
     */
    private final AtomicBoolean dbAvailable = new AtomicBoolean(true);

    /**
     * Счётчик потерянных записей из-за переполнения буфера.
     */
    private final AtomicLong droppedRecords = new AtomicLong(0);

    /**
     * Счётчик успешно записанных записей.
     */
    private final AtomicLong writtenRecords = new AtomicLong(0);


    /**
     * Добавление временной метки в буфер.
     */
    public void addTime(LocalDateTime time) {
        if (buffer.size() >= properties.maxBufferSize()) {
            LocalDateTime dropped = buffer.poll();
            droppedRecords.incrementAndGet();
            log.warn("Переполнение буфера (размер: {}), удалена старейшая запись: {}",
                    properties.maxBufferSize(), dropped);
        }
        buffer.offer(time);
        log.debug("Добавлена временная метка в буфер: {}. Текущий размер: {}", time, buffer.size());
    }

    /**
     * Асинхронная запись буфера в БД.
     */
    @Async("dbWriteExecutor")
    public void flushBufferAsync() {
        if (buffer.isEmpty()) {
            return;
        }
        long startTime = System.currentTimeMillis();
        try {
            int written = flushBuffer();
            long duration = System.currentTimeMillis() - startTime;
            if (written > 0) {
                log.debug("Записано {} записей в БД за {} мс", written, duration);
            }
            //Логируем предупреждение при медленной записи
            if (duration > properties.slowWriteThresholdMs()) {
                log.warn("Обнаружена медленная запись в БД: {} мс. Размер буфера: {}. ", duration, buffer.size());
            }
            //Помечаем БД как доступную после успешной записи
            if (!dbAvailable.get()) {
                dbAvailable.set(true);
                log.info("Соединение с БД восстановлено");
            }
        } catch (Exception e) {
            log.error("Ошибка при записи буфера в БД: {}", e.getMessage());
            dbAvailable.set(false);
        }
    }

    /**
     * Выполнение записи из буфера в БД.
     *
     * @return количество записанных записей
     */
    @Transactional
    public int flushBuffer() {
        List<TimeRecord> batch = new ArrayList<>();
        int count = 0;

        //Извлекаем записи из буфера пачками для оптимизации
        while (!buffer.isEmpty() && count < properties.batchSize()) {
            LocalDateTime time = buffer.poll();
            if (time != null) {
                batch.add(new TimeRecord(time));
                count++;
            }
        }
        if (!batch.isEmpty()) {
            repository.saveAll(batch);
            writtenRecords.addAndGet(batch.size());
            log.debug("Batch-запись: {} записей сохранено в БД", batch.size());
        }
        return batch.size();
    }

    /**
     * Возвращение всех записей времени из БД.
     *
     * @return список записей
     */
    public List<TimeRecordResponse> getAllRecords() {
        if (!dbAvailable.get()) {
            throw new DatabaseUnavailableException("База данных временно недоступна");
        }
        try {
            List<TimeRecord> entities = repository.findAll();
            return mapper.toResponseList(entities);
        } catch (Exception e) {
            log.error("Ошибка при чтении записей из БД: {}", e.getMessage());
            dbAvailable.set(false);
            throw new DatabaseUnavailableException("Ошибка при чтении из базы данных", e);
        }
    }

    /**
     * Возвращение количества записей в БД.
     *
     * @return количество записей
     */
    public long getRecordsCount() {
        if (!dbAvailable.get()) {
            throw new DatabaseUnavailableException("База данных временно недоступна");
        }
        try {
            return repository.count();
        } catch (Exception e) {
            log.error("Ошибка при подсчёте записей в БД: {}", e.getMessage());
            dbAvailable.set(false);
            throw new DatabaseUnavailableException("Ошибка при чтении из базы данных", e);
        }
    }

    /**
     * Возвращение текущего статуса сервиса.
     *
     * @return статус сервиса
     */
    public StatusResponse getStatus() {
        long totalRecords = 0;
        try {
            totalRecords = repository.count();
        } catch (Exception e) {
            log.warn("Не удалось получить количество записей из БД: {}", e.getMessage());
            dbAvailable.set(false);
        }
        StatusResponse status = new StatusResponse();
        status.setBufferSize(buffer.size());
        status.setMaxBufferSize(properties.maxBufferSize());
        status.setDbAvailable(dbAvailable.get());
        status.setTotalRecords(totalRecords);
        status.setDroppedRecords(droppedRecords.get());
        return status;
    }

    public boolean isDatabaseAvailable() {
        return dbAvailable.get();
    }

    public void tryReconnect() {
        try {
            repository.count();
            if (!dbAvailable.get()) {
                dbAvailable.set(true);
                log.info("Соединение с БД восстановлено");
            }
        } catch (Exception e) {
            log.warn("Повторная попытка подключения к БД не удалась: {}", e.getMessage());
        }
    }

    public int getBufferSize() {
        return buffer.size();
    }

    public long getDroppedRecordsCount() {
        return droppedRecords.get();
    }

    public long getWrittenRecordsCount() {
        return writtenRecords.get();
    }

    @PreDestroy
    public void onShutdown() {
        int bufferSize = buffer.size();
        if (bufferSize == 0) {
            log.info("Завершение работы. Буфер пуст, данные не требуют сохранения.");
            return;
        }

        log.info("Завершение работы. Сохраняем {} записей из буфера...", bufferSize);

        int totalSaved = 0;
        while (!buffer.isEmpty()) {
            try {
                totalSaved += flushBuffer();
            } catch (Exception e) {
                log.error("Ошибка при сохранении данных при завершении: {}", e.getMessage());
                break;
            }
        }

        log.info("Завершение работы. Сохранено {} записей. Потеряно в буфере: {}", totalSaved, buffer.size());
    }
}

