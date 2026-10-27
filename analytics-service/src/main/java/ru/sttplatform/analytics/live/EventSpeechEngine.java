package ru.sttplatform.analytics.live;

/** Хранит промежуточный текст Vosk, пришедший по внутреннему каналу STT.
 * @version 1.0.0
 */
public final class EventSpeechEngine implements SpeechEngine {
    private String partial = "";

    /** Запоминает новую гипотезу распознавателя.
     * @param result результат распознавания или {@code null}, если обновления нет
     */
    public void observe(Result result) {
        if (result == null) return;
        partial = result.isFinal() ? "" : result.text();
    }

    /** Сбрасывает гипотезу перед следующим ответом. */
    public void reset() { partial = ""; }

    /** Запрещает передачу аудио через устаревший интерфейс движка.
     * @param pcm бинарный аудиоблок
     * @param len число значимых байтов в блоке
     * @return не возвращается: аналитический сервис принимает текстовые события
     * @throws UnsupportedOperationException всегда, так как аудио обрабатывает STT-сервис
     */
    @Override
    public java.util.List<Result> accept(byte[] pcm, int len) {
        throw new UnsupportedOperationException("Аналитика принимает события STT, а не аудио");
    }

    /** Возвращает накопленный финальный текст при завершении совместимого потока.
     * @return пустой список или одно финальное текстовое событие
     */
    @Override
    public java.util.List<Result> finish() {
        String text = partial;
        partial = "";
        return text.isBlank() ? java.util.List.of() : java.util.List.of(new Result(true, text));
    }

    /** Очищает временный текст совместимого потока. */
    @Override
    public void close() { partial = ""; }
}
