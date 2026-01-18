package ru.stas.pupkov.projecttest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.stas.pupkov.projecttest.config.TimeLoggerProperties;
import ru.stas.pupkov.projecttest.entity.TimeRecord;
import ru.stas.pupkov.projecttest.exception.DatabaseUnavailableException;
import ru.stas.pupkov.projecttest.mapper.TimeRecordMapper;
import ru.stas.pupkov.projecttest.model.StatusResponse;
import ru.stas.pupkov.projecttest.model.TimeRecordResponse;
import ru.stas.pupkov.projecttest.repository.TimeRecordRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для TimeRecordService.
 * 
 * <p>Тестируется:
 * <ul>
 *   <li>Добавление времени в буфер</li>
 *   <li>Backpressure при переполнении буфера</li>
 *   <li>Batch-запись в БД</li>
 *   <li>Обработка недоступности БД</li>
 *   <li>Получение статуса сервиса</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimeRecordService Unit Tests")
class TimeRecordServiceTest {

    @Mock
    private TimeRecordRepository repository;

    @Mock
    private TimeRecordMapper mapper;

    private TimeRecordService service;

    private TimeLoggerProperties properties;

    @BeforeEach
    void setUp() {
        // Настройки с небольшим буфером для тестирования
        properties = new TimeLoggerProperties(5, 2, 1000);
        service = new TimeRecordService(repository, mapper, properties);
    }

    @Test
    @DisplayName("Добавление времени в буфер")
    void addTime_shouldAddToBuffer() {
        // Given
        LocalDateTime time = LocalDateTime.now();

        // When
        service.addTime(time);

        // Then
        assertThat(service.getBufferSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("Backpressure: удаление старой записи при переполнении буфера")
    void addTime_shouldDropOldestWhenBufferFull() {
        // Given: заполняем буфер до максимума
        for (int i = 0; i < 5; i++) {
            service.addTime(LocalDateTime.now().minusSeconds(5 - i));
        }
        assertThat(service.getBufferSize()).isEqualTo(5);
        assertThat(service.getDroppedRecordsCount()).isEqualTo(0);

        // When: добавляем ещё одну запись
        service.addTime(LocalDateTime.now());

        // Then: буфер не превышает максимум, старая запись удалена
        assertThat(service.getBufferSize()).isEqualTo(5);
        assertThat(service.getDroppedRecordsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Batch-запись записей в БД")
    void flushBuffer_shouldSaveBatchToDatabase() {
        // Given
        service.addTime(LocalDateTime.now());
        service.addTime(LocalDateTime.now());
        service.addTime(LocalDateTime.now());
        
        when(repository.saveAll(anyList())).thenReturn(List.of());

        // When
        int written = service.flushBuffer();

        // Then
        assertThat(written).isEqualTo(2); // batch size = 2
        assertThat(service.getBufferSize()).isEqualTo(1); // осталась 1 запись
        verify(repository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("flushBuffer не вызывает repository при пустом буфере")
    void flushBuffer_shouldNotSaveWhenBufferEmpty() {
        // Given: пустой буфер

        // When
        int written = service.flushBuffer();

        // Then
        assertThat(written).isEqualTo(0);
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("getAllRecords возвращает записи из БД")
    void getAllRecords_shouldReturnMappedRecords() {
        // Given
        List<TimeRecord> entities = List.of(
                new TimeRecord(1L, LocalDateTime.now()),
                new TimeRecord(2L, LocalDateTime.now())
        );
        TimeRecordResponse response1 = new TimeRecordResponse();
        response1.setId(1L);
        TimeRecordResponse response2 = new TimeRecordResponse();
        response2.setId(2L);
        List<TimeRecordResponse> responses = List.of(response1, response2);
        
        when(repository.findAll()).thenReturn(entities);
        when(mapper.toResponseList(entities)).thenReturn(responses);

        // When
        List<TimeRecordResponse> result = service.getAllRecords();

        // Then
        assertThat(result).hasSize(2);
        verify(repository).findAll();
        verify(mapper).toResponseList(entities);
    }

    @Test
    @DisplayName("getAllRecords выбрасывает исключение при недоступной БД")
    void getAllRecords_shouldThrowWhenDbUnavailable() {
        // Given
        when(repository.findAll()).thenThrow(new RuntimeException("Connection refused"));

        // When/Then
        assertThatThrownBy(() -> service.getAllRecords())
                .isInstanceOf(DatabaseUnavailableException.class);
    }

    @Test
    @DisplayName("getStatus возвращает корректный статус")
    void getStatus_shouldReturnCorrectStatus() {
        // Given
        service.addTime(LocalDateTime.now());
        service.addTime(LocalDateTime.now());
        when(repository.count()).thenReturn(100L);

        // When
        StatusResponse status = service.getStatus();

        // Then
        assertThat(status.getBufferSize()).isEqualTo(2);
        assertThat(status.getMaxBufferSize()).isEqualTo(5);
        assertThat(status.getDbAvailable()).isTrue();
        assertThat(status.getTotalRecords()).isEqualTo(100L);
        assertThat(status.getDroppedRecords()).isEqualTo(0L);
    }

    @Test
    @DisplayName("tryReconnect восстанавливает флаг доступности БД")
    void tryReconnect_shouldRestoreDbAvailability() {
        // Given: симулируем недоступность БД
        when(repository.findAll()).thenThrow(new RuntimeException("Connection refused"));
        try {
            service.getAllRecords();
        } catch (DatabaseUnavailableException ignored) {
        }
        assertThat(service.isDatabaseAvailable()).isFalse();

        // When: БД снова доступна
        when(repository.count()).thenReturn(0L);
        service.tryReconnect();

        // Then
        assertThat(service.isDatabaseAvailable()).isTrue();
    }

    @Test
    @DisplayName("Счётчик записанных записей увеличивается после flush")
    void flushBuffer_shouldIncrementWrittenCounter() {
        // Given
        service.addTime(LocalDateTime.now());
        service.addTime(LocalDateTime.now());
        when(repository.saveAll(anyList())).thenReturn(List.of());

        // When
        service.flushBuffer();

        // Then
        assertThat(service.getWrittenRecordsCount()).isEqualTo(2);
    }
}
