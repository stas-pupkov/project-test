package ru.stas.pupkov.projecttest.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.stas.pupkov.projecttest.entity.TimeRecord;

@Repository
public interface TimeRecordRepository extends JpaRepository<TimeRecord, Long> {
}
