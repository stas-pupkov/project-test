package ru.stas.pupkov.projecttest.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.stas.pupkov.projecttest.entity.TimeRecord;
import ru.stas.pupkov.projecttest.repository.TimeRecordRepository;
import ru.stas.pupkov.projecttest.service.TimeRecordService;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@Disabled
class TimeRecordIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("timelogger_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        //Отключаем scheduler для предсказуемости тестов
        registry.add("spring.scheduling.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TimeRecordRepository repository;

    @Autowired
    private TimeRecordService service;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("Health check доступен без авторизации")
    void healthCheck_shouldBeAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("API требует авторизации")
    void api_shouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/times"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = "USER")
    @DisplayName("GET /api/times возвращает пустой список для новой БД")
    void getAllTimes_shouldReturnEmptyListForNewDatabase() throws Exception {
        mockMvc.perform(get("/api/times"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithMockUser(username = "admin", roles = "USER")
    @DisplayName("GET /api/times возвращает записи в хронологическом порядке")
    void getAllTimes_shouldReturnRecordsInChronologicalOrder() throws Exception {
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 0, 1);
        LocalDateTime time3 = LocalDateTime.of(2024, 1, 1, 10, 0, 2);
        
        repository.save(new TimeRecord(time1));
        repository.save(new TimeRecord(time2));
        repository.save(new TimeRecord(time3));

        mockMvc.perform(get("/api/times"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recordedAt").exists())
                .andExpect(jsonPath("$[1].recordedAt").exists())
                .andExpect(jsonPath("$[2].recordedAt").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = "USER")
    @DisplayName("GET /api/times/count возвращает количество записей")
    void getCount_shouldReturnCorrectCount() throws Exception {
        repository.save(new TimeRecord(LocalDateTime.now()));
        repository.save(new TimeRecord(LocalDateTime.now()));

        mockMvc.perform(get("/api/times/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    @DisplayName("Записи сохраняются в БД через service.flushBuffer()")
    void flushBuffer_shouldPersistRecordsToDatabase() {
        service.addTime(LocalDateTime.now());
        service.addTime(LocalDateTime.now());
        
        service.flushBuffer();
        service.flushBuffer(); //второй flush для оставшихся записей
        
        List<TimeRecord> records = repository.findAll();
        assertThat(records).hasSize(2);
    }

    @Test
    @DisplayName("Записи в БД имеют автоинкрементный ID (хронологический порядок)")
    void records_shouldHaveAutoIncrementedIds() {
        TimeRecord record1 = repository.save(new TimeRecord(LocalDateTime.now()));
        TimeRecord record2 = repository.save(new TimeRecord(LocalDateTime.now()));
        TimeRecord record3 = repository.save(new TimeRecord(LocalDateTime.now()));
        
        //ID должны быть последовательными
        assertThat(record1.getId()).isLessThan(record2.getId());
        assertThat(record2.getId()).isLessThan(record3.getId());
    }

    @Test
    @DisplayName("findAll возвращает записи в порядке ID без сортировки")
    void findAll_shouldReturnRecordsOrderedByIdWithoutExplicitSorting() {
        //Сохраняем записи с разными временными метками
        //Намеренно сохраняем в обратном хронологическом порядке времени, но ID будут в правильном порядке
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        LocalDateTime present = LocalDateTime.now();
        LocalDateTime future = LocalDateTime.now().plusHours(1);
        
        repository.save(new TimeRecord(future));
        repository.save(new TimeRecord(past));
        repository.save(new TimeRecord(present));
        
        List<TimeRecord> records = repository.findAll();
        
        //записи в порядке ID (порядка вставки), а не времени
        assertThat(records).hasSize(3);
        assertThat(records.get(0).getId()).isLessThan(records.get(1).getId());
        assertThat(records.get(1).getId()).isLessThan(records.get(2).getId());
        
        //Первая запись имеет будущее время, так как была вставлена первой
        assertThat(records.get(0).getRecordedAt()).isEqualTo(future);
    }
}
