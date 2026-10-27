package ru.sttplatform.diarization.session;

/**
 * Ошибка действия над сессией с HTTP-кодом ответа.
 *
 * @version 1.0.0
 */
public final class SessionException extends RuntimeException {
    private final int code;

    /**
     * Создаёт ошибку.
     *
     * @param code HTTP-код
     * @param message сообщение для клиента
     */
    public SessionException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * HTTP-код ответа.
     *
     * @return код
     */
    public int code() {
        return code;
    }
}
