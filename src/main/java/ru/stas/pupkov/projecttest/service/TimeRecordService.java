package ru.stas.pupkov.projecttest.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.stas.pupkov.projecttest.config.TimeLoggerProperties;
import ru.stas.pupkov.projecttest.entity.TimeRecord;
import ru.stas.pupkov.projecttest.exception.DatabaseUnavailableException;
import ru.stas.pupkov.projecttest.mapper.TimeRecordMapper;
import ru.stas.pupkov.projecttest.model.SliceTimeRecordResponse;
import ru.stas.pupkov.projecttest.model.TimeRecordResponse;
import ru.stas.pupkov.projecttest.repository.TimeRecordRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
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
    private final ConcurrentLinkedDeque<LocalDateTime> buffer = new ConcurrentLinkedDeque<>();

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
     * Новые записи добавляются в конец (FIFO).
     */
    public void addTime(LocalDateTime time) {
        if (buffer.size() >= properties.maxBufferSize()) {
            LocalDateTime dropped = buffer.pollFirst();
            droppedRecords.incrementAndGet();
            log.warn("Переполнение буфера (размер: {}), удалена старейшая запись: {}",
                    properties.maxBufferSize(), dropped);
        }
        buffer.addLast(time);
        log.debug("Добавлена временная метка в буфер: {}. Текущий размер: {}", time, buffer.size());
    }

    /**
     * Асинхронная запись буфера в БД.
     * Выполняется в отдельном потоке, чтобы не блокировать планировщик.
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
            if (duration > properties.slowWriteThresholdMs()) {
                log.warn("Обнаружена медленная запись в БД: {} мс. Размер буфера: {}", duration, buffer.size());
            }
            if (written > 0 && !dbAvailable.get()) {
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
    public int flushBuffer() {
        List<LocalDateTime> extracted = new ArrayList<>();
        //Извлекаем записи из начала буфера пачками
        while (!buffer.isEmpty() && extracted.size() < properties.batchSize()) {
            LocalDateTime time = buffer.pollFirst();
            if (time != null) {
                extracted.add(time);
            }
        }
        if (extracted.isEmpty()) {
            return 0;
        }
        List<TimeRecord> batch = extracted.stream().map(TimeRecord::new).toList();
        try {
            repository.saveAll(batch);
            writtenRecords.addAndGet(batch.size());
            log.debug("Batch-запись: {} записей сохранено в БД", batch.size());
            return batch.size();
        } catch (Exception e) {
            //Возвращаем записи обратно в буфер при ошибке.
            //Добавляем в начало в обратном порядке, чтобы сохранить хронологическую последовательность.
            List<LocalDateTime> reversed = new ArrayList<>(extracted);
            Collections.reverse(reversed);
            reversed.forEach(buffer::addFirst);
            log.warn("Ошибка записи в БД, {} записей возвращено в буфер: {}", extracted.size(), e.getMessage());
            throw e;
        }
    }

    /**
     * Возвращение среза записей времени из БД.
     *
     * @param page номер страницы (начиная с 0)
     * @param size размер страницы
     * @return срез записей с метаданными пагинации
     */
    public SliceTimeRecordResponse getRecordsPaginated(int page, int size) {
        if (!dbAvailable.get()) {
            throw new DatabaseUnavailableException("База данных временно недоступна");
        }
        try {
            Pageable pageable = PageRequest.of(page, size);
            Slice<TimeRecord> slice = repository.findAllBy(pageable);
            List<TimeRecordResponse> content = mapper.toResponseList(slice.getContent());
            SliceTimeRecordResponse response = new SliceTimeRecordResponse()
                    .content(content)
                    .page(slice.getNumber())
                    .size(slice.getSize())
                    .numberOfElements(slice.getNumberOfElements())
                    .first(slice.isFirst())
                    .last(slice.isLast())
                    .hasNext(slice.hasNext())
                    .hasPrevious(slice.hasPrevious());
            log.debug("Возвращён срез: страница={}, записей={}, hasNext={}", page, content.size(), slice.hasNext());
            return response;
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

