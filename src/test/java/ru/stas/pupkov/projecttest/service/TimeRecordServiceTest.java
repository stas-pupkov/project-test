package ru.stas.pupkov.projecttest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import ru.stas.pupkov.projecttest.config.TimeLoggerProperties;
import ru.stas.pupkov.projecttest.entity.TimeRecord;
import ru.stas.pupkov.projecttest.exception.DatabaseUnavailableException;
import ru.stas.pupkov.projecttest.mapper.TimeRecordMapper;
import ru.stas.pupkov.projecttest.model.SliceTimeRecordResponse;
import ru.stas.pupkov.projecttest.model.TimeRecordResponse;
import ru.stas.pupkov.projecttest.repository.TimeRecordRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeRecordServiceTest {

    @Mock
    private TimeRecordRepository repository;

    @Mock
    private TimeRecordMapper mapper;

    private TimeRecordService service;

    @BeforeEach
    void setUp() {
        //Настройки с небольшим буфером для тестирования
        TimeLoggerProperties properties = new TimeLoggerProperties(5, 2, 1000);
        service = new TimeRecordService(repository, mapper, properties);
    }

    @Test
    @DisplayName("Добавление времени в буфер")
    void addTime_shouldAddToBuffer() {
        LocalDateTime time = LocalDateTime.now();

        service.addTime(time);

        assertThat(service.getBufferSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("Backpressure: удаление старой записи при переполнении буфера")
    void addTime_shouldDropOldestWhenBufferFull() {
        //Заполняем буфер до максимума
        for (int i = 0; i < 5; i++) {
            service.addTime(LocalDateTime.now().minusSeconds(5 - i));
        }
        assertThat(service.getBufferSize()).isEqualTo(5);
        assertThat(service.getDroppedRecordsCount()).isEqualTo(0);

        //Добавляем ещё одну запись
        service.addTime(LocalDateTime.now());

        //Буфер не превышает максимум, старая запись удалена
        assertThat(service.getBufferSize()).isEqualTo(5);
        assertThat(service.getDroppedRecordsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Batch-запись записей в БД")
    void flushBuffer_shouldSaveBatchToDatabase() {
        service.addTime(LocalDateTime.now());
        service.addTime(LocalDateTime.now());
        service.addTime(LocalDateTime.now());
        when(repository.saveAll(anyList())).thenReturn(List.of());

        int written = service.flushBuffer();

        assertThat(written).isEqualTo(2); // batch size для теста = 2
        assertThat(service.getBufferSize()).isEqualTo(1); // осталась 1 запись
        verify(repository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("flushBuffer не вызывает repository при пустом буфере")
    void flushBuffer_shouldNotSaveWhenBufferEmpty() {
        int written = service.flushBuffer();

        assertThat(written).isEqualTo(0);
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("getRecordsPaginated возвращает срез записей из БД (Slice без COUNT)")
    void getRecordsPaginated_shouldReturnSlicedRecords() {
        List<TimeRecord> entities = List.of(
                new TimeRecord(1L, LocalDateTime.now()),
                new TimeRecord(2L, LocalDateTime.now()));
        TimeRecordResponse response1 = new TimeRecordResponse();
        response1.setId(1L);
        TimeRecordResponse response2 = new TimeRecordResponse();
        response2.setId(2L);
        List<TimeRecordResponse> responses = List.of(response1, response2);
        
        Slice<TimeRecord> slice = new SliceImpl<>(
                entities, 
                PageRequest.of(0, 20), 
                true  // hasNext
        );
        when(repository.findAllBy(any(Pageable.class))).thenReturn(slice);
        when(mapper.toResponseList(entities)).thenReturn(responses);

        SliceTimeRecordResponse result = service.getRecordsPaginated(0, 20);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getPage()).isEqualTo(0);
        assertThat(result.getNumberOfElements()).isEqualTo(2);
        assertThat(result.getFirst()).isTrue();
        assertThat(result.getLast()).isFalse();  // hasNext=true, значит не последняя
        assertThat(result.getHasNext()).isTrue();
        assertThat(result.getHasPrevious()).isFalse();
        verify(repository).findAllBy(any(Pageable.class));
        verify(mapper).toResponseList(entities);
    }

    @Test
    @DisplayName("getRecordsPaginated выбрасывает исключение при недоступной БД")
    void getRecordsPaginated_shouldThrowWhenDbUnavailable() {
        when(repository.findAllBy(any(Pageable.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> service.getRecordsPaginated(0, 20))
                .isInstanceOf(DatabaseUnavailableException.class);
    }

    @Test
    @DisplayName("tryReconnect восстанавливает флаг доступности БД")
    void tryReconnect_shouldRestoreDbAvailability() {
        //Симулируем недоступность БД
        when(repository.findAllBy(any(Pageable.class))).thenThrow(new RuntimeException("Connection refused"));
        try {
            service.getRecordsPaginated(0, 20);
        } catch (DatabaseUnavailableException ignored) {
        }
        assertThat(service.isDatabaseAvailable()).isFalse();

        //БД снова доступна
        when(repository.count()).thenReturn(0L);
        service.tryReconnect();

        assertThat(service.isDatabaseAvailable()).isTrue();
    }

    @Test
    @DisplayName("Счётчик записанных записей увеличивается после flush")
    void flushBuffer_shouldIncrementWrittenCounter() {
        service.addTime(LocalDateTime.now());
        service.addTime(LocalDateTime.now());
        when(repository.saveAll(anyList())).thenReturn(List.of());

        service.flushBuffer();

        assertThat(service.getWrittenRecordsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("При ошибке записи записи возвращаются в буфер")
    void flushBuffer_shouldReturnRecordsToBufferOnError() {
        service.addTime(LocalDateTime.now());
        service.addTime(LocalDateTime.now());
        when(repository.saveAll(anyList())).thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> service.flushBuffer())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB error");

        //Записи должны вернуться в буфер
        assertThat(service.getBufferSize()).isEqualTo(2);
        //Счётчик записанных не должен измениться
        assertThat(service.getWrittenRecordsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("При ошибке записи хронологический порядок сохраняется")
    void flushBuffer_shouldPreserveChronologicalOrderOnError() {
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 0, 1);
        LocalDateTime time3 = LocalDateTime.of(2024, 1, 1, 10, 0, 2);

        service.addTime(time1);
        service.addTime(time2);
        service.addTime(time3);
        assertThat(service.getBufferSize()).isEqualTo(3);

        //Первый flush падает, затем два успешных
        when(repository.saveAll(anyList()))
                .thenThrow(new RuntimeException("DB error"))
                .thenReturn(List.of())
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.flushBuffer()).isInstanceOf(RuntimeException.class);

        //Все 3 записи должны быть в буфере
        assertThat(service.getBufferSize()).isEqualTo(3);

        //Теперь БД доступна, записываем
        int written1 = service.flushBuffer();
        assertThat(written1).isEqualTo(2); // batch size в тесте = 2

        int written2 = service.flushBuffer();
        assertThat(written2).isEqualTo(1); // оставшаяся 1 запись

        //В буффере ничего не осталось, 3 записи в БД
        assertThat(service.getBufferSize()).isEqualTo(0);
        assertThat(service.getWrittenRecordsCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Записи возвращаются в начало буфера, а не в конец")
    void flushBuffer_shouldAddRecordsToFrontOfBufferOnError() {
        //Используем properties с batchSize=2, maxBufferSize=5
        LocalDateTime oldTime1 = LocalDateTime.of(2024, 1, 1, 10, 0, 0);
        LocalDateTime oldTime2 = LocalDateTime.of(2024, 1, 1, 10, 0, 1);
        LocalDateTime newTime = LocalDateTime.of(2024, 1, 1, 10, 0, 2);

        service.addTime(oldTime1);
        service.addTime(oldTime2);

        //Первый flush падает, затем успешный
        when(repository.saveAll(anyList()))
                .thenThrow(new RuntimeException("DB error"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.flushBuffer()).isInstanceOf(RuntimeException.class);

        //Добавляем новую запись ПОСЛЕ ошибки
        service.addTime(newTime);

        //Буфер должен содержать: [oldTime1, oldTime2, newTime]
        assertThat(service.getBufferSize()).isEqualTo(3);

        //Теперь flush должен забрать старые записи первыми
        int written = service.flushBuffer();

        assertThat(written).isEqualTo(2); // batch size для теста = 2, забраны oldTime1 и oldTime2
        assertThat(service.getBufferSize()).isEqualTo(1); // осталась newTime
    }

    @Test
    @DisplayName("Многократные ошибки не нарушают порядок записей")
    void flushBuffer_shouldPreserveOrderAfterMultipleErrors() {
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 0, 1);

        service.addTime(time1);
        service.addTime(time2);

        //Три ошибки, затем успех
        when(repository.saveAll(anyList()))
                .thenThrow(new RuntimeException("Error 1"))
                .thenThrow(new RuntimeException("Error 2"))
                .thenThrow(new RuntimeException("Error 3"))
                .thenReturn(List.of());

        //Первая ошибка
        assertThatThrownBy(() -> service.flushBuffer()).isInstanceOf(RuntimeException.class);
        assertThat(service.getBufferSize()).isEqualTo(2);

        //Вторая ошибка
        assertThatThrownBy(() -> service.flushBuffer()).isInstanceOf(RuntimeException.class);
        assertThat(service.getBufferSize()).isEqualTo(2);

        //Третья ошибка
        assertThatThrownBy(() -> service.flushBuffer()).isInstanceOf(RuntimeException.class);
        assertThat(service.getBufferSize()).isEqualTo(2);

        //Успешная запись
        int written = service.flushBuffer();

        assertThat(written).isEqualTo(2);
        assertThat(service.getBufferSize()).isEqualTo(0);
        assertThat(service.getWrittenRecordsCount()).isEqualTo(2);
    }
}
