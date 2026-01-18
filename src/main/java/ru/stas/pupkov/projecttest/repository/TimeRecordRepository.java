package ru.stas.pupkov.projecttest.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.stas.pupkov.projecttest.entity.TimeRecord;

@Repository
public interface TimeRecordRepository extends JpaRepository<TimeRecord, Long> {
    
    Slice<TimeRecord> findAllBy(Pageable pageable);

}
