package ru.stas.pupkov.projecttest.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ru.stas.pupkov.projecttest.api.TimeRecordsApi;
import ru.stas.pupkov.projecttest.model.CountResponse;
import ru.stas.pupkov.projecttest.model.SliceTimeRecordResponse;
import ru.stas.pupkov.projecttest.service.TimeRecordService;

@Slf4j
@RestController
@RequiredArgsConstructor
public class TimeRecordController implements TimeRecordsApi {

    private final TimeRecordService service;

    @Override
    public ResponseEntity<SliceTimeRecordResponse> getAllTimeRecords(Integer page, Integer size) {
        int pageNum = page != null ? page : 0;
        int pageSize = size != null ? size : 20;
        
        log.debug("Запрос на получение записей времени: страница={}, размер={}", pageNum, pageSize);
        SliceTimeRecordResponse response = service.getRecordsPaginated(pageNum, pageSize);
        log.info("Возвращён срез: страница={}, записей={}, hasNext={}", 
                 response.getPage(), response.getContent().size(), response.getHasNext());
        return ResponseEntity.ok(response);
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
}
