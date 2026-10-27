package ru.sttplatform.analytics.live;

/** Хранит промежуточный текст Vosk, пришедший по внутреннему каналу STT. */
public final class EventSpeechEngine implements SpeechEngine {
    private String partial = "";

    /** Запоминает новую гипотезу распознавателя. */
    public void observe(Result result) {
        if (result == null) return;
        partial = result.isFinal() ? "" : result.text();
    }

    /** Сбрасывает гипотезу перед следующим ответом. */
    public void reset() { partial = ""; }

    @Override
    public java.util.List<Result> accept(byte[] pcm, int len) {
        throw new UnsupportedOperationException("Аналитика принимает события STT, а не аудио");
    }

    @Override
    public java.util.List<Result> finish() {
        String text = partial;
        partial = "";
        return text.isBlank() ? java.util.List.of() : java.util.List.of(new Result(true, text));
    }

    @Override
    public void close() { partial = ""; }
}
