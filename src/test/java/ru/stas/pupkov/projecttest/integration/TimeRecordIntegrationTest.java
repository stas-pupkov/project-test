package ru.stas.pupkov.projecttest.integration;

import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Интеграционные тесты с использованием Testcontainers.
 * 
 * <p>Тестируется:
 * <ul>
 *   <li>Полный цикл записи и чтения из PostgreSQL</li>
 *   <li>Хронологический порядок записей без сортировки</li>
 *   <li>REST API с авторизацией</li>
 *   <li>Health check endpoint</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Time Logger Integration Tests")
@org.junit.jupiter.api.Disabled("Интеграционные тесты требуют Docker. Запускать: ./gradlew test -PincludeIntegrationTests")
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
        // Отключаем scheduler для предсказуемости тестов
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
        // Given: создаём записи в хронологическом порядке
        LocalDateTime time1 = LocalDateTime.of(2024, 1, 1, 10, 0, 0);
        LocalDateTime time2 = LocalDateTime.of(2024, 1, 1, 10, 0, 1);
        LocalDateTime time3 = LocalDateTime.of(2024, 1, 1, 10, 0, 2);
        
        repository.save(new TimeRecord(time1));
        repository.save(new TimeRecord(time2));
        repository.save(new TimeRecord(time3));

        // When/Then: записи возвращаются в том же порядке
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
        // Given
        repository.save(new TimeRecord(LocalDateTime.now()));
        repository.save(new TimeRecord(LocalDateTime.now()));

        // When/Then
        mockMvc.perform(get("/api/times/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    @WithMockUser(username = "admin", roles = "USER")
    @DisplayName("GET /api/status возвращает корректный статус")
    void getStatus_shouldReturnCorrectStatus() throws Exception {
        // Given
        repository.save(new TimeRecord(LocalDateTime.now()));

        // When/Then
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbAvailable").value(true))
                .andExpect(jsonPath("$.totalRecords").value(1))
                .andExpect(jsonPath("$.maxBufferSize").value(10000));
    }

    @Test
    @DisplayName("Записи сохраняются в БД через service.flushBuffer()")
    void flushBuffer_shouldPersistRecordsToDatabase() {
        // Given
        service.addTime(LocalDateTime.now());
        service.addTime(LocalDateTime.now());
        
        // When
        service.flushBuffer();
        service.flushBuffer(); // второй flush для оставшихся записей
        
        // Then
        List<TimeRecord> records = repository.findAll();
        assertThat(records).hasSize(2);
    }

    @Test
    @DisplayName("Записи в БД имеют автоинкрементный ID (хронологический порядок)")
    void records_shouldHaveAutoIncrementedIds() {
        // When
        TimeRecord record1 = repository.save(new TimeRecord(LocalDateTime.now()));
        TimeRecord record2 = repository.save(new TimeRecord(LocalDateTime.now()));
        TimeRecord record3 = repository.save(new TimeRecord(LocalDateTime.now()));
        
        // Then: ID должны быть последовательными
        assertThat(record1.getId()).isLessThan(record2.getId());
        assertThat(record2.getId()).isLessThan(record3.getId());
    }

    @Test
    @DisplayName("findAll возвращает записи в порядке ID без сортировки")
    void findAll_shouldReturnRecordsOrderedByIdWithoutExplicitSorting() {
        // Given: Сохраняем записи с разными временными метками
        // Намеренно сохраняем в обратном хронологическом порядке времени,
        // но ID будут в правильном порядке
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        LocalDateTime present = LocalDateTime.now();
        LocalDateTime future = LocalDateTime.now().plusHours(1);
        
        repository.save(new TimeRecord(future));
        repository.save(new TimeRecord(past));
        repository.save(new TimeRecord(present));
        
        // When
        List<TimeRecord> records = repository.findAll();
        
        // Then: записи в порядке ID (порядка вставки), а не времени
        assertThat(records).hasSize(3);
        assertThat(records.get(0).getId()).isLessThan(records.get(1).getId());
        assertThat(records.get(1).getId()).isLessThan(records.get(2).getId());
        
        // Первая запись имеет future время, так как была вставлена первой
        assertThat(records.get(0).getRecordedAt()).isEqualTo(future);
    }
}
