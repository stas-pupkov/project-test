package ru.stas.pupkov.projecttest.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.stas.pupkov.projecttest.service.TimeRecordService;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class TimeRecordScheduler {

    private final TimeRecordService service;

    @Scheduled(fixedRate = 1000)
    public void addCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        service.addTime(now);
        log.debug("Добавлена временная метка в буфер: {}", now);
    }

    @Scheduled(fixedRate = 1000)
    public void flushBuffer() {
        service.flushBufferAsync();
    }


    @Scheduled(fixedRate = 5000)
    public void checkDatabaseConnection() {
        if (!service.isDatabaseAvailable()) {
            log.warn("Соединение с БД недоступно. Повторная попытка подключения...");
            service.tryReconnect();
            
            if (service.isDatabaseAvailable()) {
                log.info("Соединение с БД восстановлено. Записей в буфере: {}", service.getBufferSize());
            }
        }
    }
}
