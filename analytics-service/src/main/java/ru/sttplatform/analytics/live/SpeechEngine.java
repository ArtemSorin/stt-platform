package ru.sttplatform.analytics.live;

import java.util.List;

/**
 * Потоковый распознаватель речи для одного интервью. В платформе это клиент микросервиса
 * {@code stt-service} (Vosk), в тестах — заглушка со сценарием.
 *
 * @version 1.0.0
 */
public interface SpeechEngine extends AutoCloseable {

    /**
     * Результат распознавания.
     *
     * @param isFinal {@code true} — фраза зафиксирована распознавателем; {@code false} — промежуточная гипотеза
     * @param text распознанный текст
     */
    record Result(boolean isFinal, String text) {}

    /**
     * Подаёт кусок PCM 16 кГц / 16 бит / моно.
     *
     * @param pcm байты PCM
     * @param len число значимых байтов
     * @return результаты, накопленные с прошлого вызова, в порядке поступления; пустой список, если нового нет
     */
    List<Result> accept(byte[] pcm, int len);

    /**
     * Дораспознаёт остаток и сбрасывает состояние для следующего ответа.
     *
     * @return все результаты вплоть до финального по сбросу (последний — с {@code isFinal == true})
     */
    List<Result> finish();

    /**
     * Отношение времени распознавания ко времени звука, если его измеряет сам движок.
     *
     * @return значение или {@code null}, чтобы {@link LiveInterview} измерил время сам
     */
    default Double realtimeFactor() {
        return null;
    }

    @Override
    void close();
}
