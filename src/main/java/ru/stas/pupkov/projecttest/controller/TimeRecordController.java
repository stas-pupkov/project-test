package ru.stas.pupkov.projecttest.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ru.stas.pupkov.projecttest.api.TimeRecordsApi;
import ru.stas.pupkov.projecttest.api.StatusApi;
import ru.stas.pupkov.projecttest.model.CountResponse;
import ru.stas.pupkov.projecttest.model.StatusResponse;
import ru.stas.pupkov.projecttest.model.TimeRecordResponse;
import ru.stas.pupkov.projecttest.service.TimeRecordService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
public class TimeRecordController implements TimeRecordsApi, StatusApi {

    private final TimeRecordService service;

    @Override
    public ResponseEntity<List<TimeRecordResponse>> getAllTimeRecords() {
        log.debug("Запрос на получение всех записей времени");
        List<TimeRecordResponse> records = service.getAllRecords();
        log.info("Возвращено {} записей времени", records.size());
        return ResponseEntity.ok(records);
    }

    @Override
    public ResponseEntity<CountResponse> getTimeRecordsCount() {
        log.debug("Запрос на получение количества записей");
        long count = service.getRecordsCount();
        log.debug("Количество записей: {}", count);
        CountResponse response = new CountResponse();
        response.setCount(count);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<StatusResponse> getStatus() {
        log.debug("Запрос статуса сервиса");
        StatusResponse status = service.getStatus();
        log.debug("Статус: буфер={}, БД доступна={}, всего записей={}", 
                  status.getBufferSize(), status.getDbAvailable(), status.getTotalRecords());
        return ResponseEntity.ok(status);
    }
}
