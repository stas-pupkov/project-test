package ru.stas.pupkov.projecttest.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import ru.stas.pupkov.projecttest.model.ErrorResponse;

import java.time.OffsetDateTime;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(DatabaseUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDbUnavailable(DatabaseUnavailableException e) {
        log.error("База данных недоступна: {}", e.getMessage());
        ErrorResponse error = new ErrorResponse();
        error.setCode("DB_UNAVAILABLE");
        error.setMessage("База данных временно недоступна. Повторите запрос позже.");
        error.setTimestamp(OffsetDateTime.now());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException e) {
        log.warn("Ошибка аутентификации: {}", e.getMessage());
        ErrorResponse error = new ErrorResponse();
        error.setCode("UNAUTHORIZED");
        error.setMessage("Требуется авторизация");
        error.setTimestamp(OffsetDateTime.now());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        log.warn("Доступ запрещён: {}", e.getMessage());
        ErrorResponse error = new ErrorResponse();
        error.setCode("ACCESS_DENIED");
        error.setMessage("Доступ запрещён");
        error.setTimestamp(OffsetDateTime.now());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception e) {
        log.error("Непредвиденная ошибка: {}", e.getMessage(), e);
        ErrorResponse error = new ErrorResponse();
        error.setCode("INTERNAL_ERROR");
        error.setMessage("Внутренняя ошибка сервера");
        error.setTimestamp(OffsetDateTime.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
