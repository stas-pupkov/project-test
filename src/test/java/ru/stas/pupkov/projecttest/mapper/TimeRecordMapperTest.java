package ru.stas.pupkov.projecttest.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import ru.stas.pupkov.projecttest.entity.TimeRecord;
import ru.stas.pupkov.projecttest.model.TimeRecordResponse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeRecordMapperTest {

    private final TimeRecordMapper mapper = Mappers.getMapper(TimeRecordMapper.class);

    @Test
    @DisplayName("toResponse корректно маппит Entity в DTO")
    void toResponse_shouldMapEntityToDto() {
        LocalDateTime now = LocalDateTime.now();
        TimeRecord entity = TimeRecord.builder()
                .id(1L)
                .recordedAt(now)
                .build();

        TimeRecordResponse response = mapper.toResponse(entity);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
        //LocalDateTime конвертируется в OffsetDateTime с UTC offset
        OffsetDateTime expectedTime = now.atOffset(ZoneOffset.UTC);
        assertThat(response.getRecordedAt()).isEqualTo(expectedTime);
    }

    @Test
    @DisplayName("toResponse возвращает null для null Entity")
    void toResponse_shouldReturnNullForNullEntity() {
        TimeRecordResponse response = mapper.toResponse(null);

        assertThat(response).isNull();
    }

    @Test
    @DisplayName("toResponseList корректно маппит список Entity в список DTO")
    void toResponseList_shouldMapEntityListToDtoList() {
        LocalDateTime time1 = LocalDateTime.now().minusSeconds(2);
        LocalDateTime time2 = LocalDateTime.now().minusSeconds(1);
        LocalDateTime time3 = LocalDateTime.now();
        List<TimeRecord> entities = List.of(
                TimeRecord.builder().id(1L).recordedAt(time1).build(),
                TimeRecord.builder().id(2L).recordedAt(time2).build(),
                TimeRecord.builder().id(3L).recordedAt(time3).build());

        List<TimeRecordResponse> responses = mapper.toResponseList(entities);

        assertThat(responses).hasSize(3);
        assertThat(responses.get(0).getId()).isEqualTo(1L);
        assertThat(responses.get(0).getRecordedAt()).isEqualTo(time1.atOffset(ZoneOffset.UTC));
        assertThat(responses.get(1).getId()).isEqualTo(2L);
        assertThat(responses.get(2).getId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("toResponseList возвращает null для null списка")
    void toResponseList_shouldReturnNullForNullList() {
        List<TimeRecordResponse> responses = mapper.toResponseList(null);

        assertThat(responses).isNull();
    }

    @Test
    @DisplayName("toResponseList возвращает пустой список для пустого входного списка")
    void toResponseList_shouldReturnEmptyListForEmptyInput() {
        List<TimeRecordResponse> responses = mapper.toResponseList(List.of());

        assertThat(responses).isEmpty();
    }
    
    @Test
    @DisplayName("localToOffset корректно преобразует LocalDateTime в OffsetDateTime")
    void localToOffset_shouldConvertCorrectly() {
        LocalDateTime localDateTime = LocalDateTime.of(2024, 1, 17, 12, 0, 0);
        
        OffsetDateTime result = mapper.localToOffset(localDateTime);
        
        assertThat(result).isNotNull();
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(result.toLocalDateTime()).isEqualTo(localDateTime);
    }
    
    @Test
    @DisplayName("localToOffset возвращает null для null")
    void localToOffset_shouldReturnNullForNull() {
        OffsetDateTime result = mapper.localToOffset(null);
        
        assertThat(result).isNull();
    }
}
