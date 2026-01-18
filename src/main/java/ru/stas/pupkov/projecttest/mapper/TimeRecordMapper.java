package ru.stas.pupkov.projecttest.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import ru.stas.pupkov.projecttest.entity.TimeRecord;
import ru.stas.pupkov.projecttest.model.TimeRecordResponse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface TimeRecordMapper {

    @Mapping(source = "recordedAt", target = "recordedAt", qualifiedByName = "localToOffset")
    TimeRecordResponse toResponse(TimeRecord entity);

    List<TimeRecordResponse> toResponseList(List<TimeRecord> entities);

    @Named("localToOffset")
    default OffsetDateTime localToOffset(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return localDateTime.atOffset(ZoneOffset.UTC);
    }
}
