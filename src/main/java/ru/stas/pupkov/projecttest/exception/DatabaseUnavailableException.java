package ru.stas.pupkov.projecttest.exception;

/**
 * Исключение, выбрасываемое при недоступности базы данных.
 */
public class DatabaseUnavailableException extends RuntimeException {

    public DatabaseUnavailableException(String message) {
        super(message);
    }

    public DatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
