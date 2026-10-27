package ru.sttplatform.gateway.clients;

/**
 * Ошибка вызова или действия с HTTP-кодом, который нужно вернуть браузеру.
 *
 * @version 1.0.0
 */
public final class ApiException extends RuntimeException {
    private final int code;

    /**
     * Создаёт ошибку.
     *
     * @param code HTTP-код
     * @param message сообщение для клиента
     */
    public ApiException(int code, String message) {
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
