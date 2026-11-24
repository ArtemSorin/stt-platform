package ru.sttplatform.finalization;

import com.fasterxml.jackson.databind.JsonNode;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.gateway.session.FinalAnswerAssembler;
import ru.sttplatform.gateway.session.InterviewJournalReplay;
import ru.sttplatform.stt.transcript.ArchiveWave;
import ru.sttplatform.stt.transcript.FinalTranscriptBuilder;
import ru.sttplatform.stt.transcript.InterviewAudioSpool;
import ru.sttplatform.stt.transcript.PostInterviewWhisper;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Восстанавливает ответы из проверенного WAV и завершённого журнала интервью. */
public final class FinalizationTranscriber {
    private static final int RATE = 16000;

    /** @param answers итоговые ответы для аналитики
     * @param words слова итоговой транскрипции
     * @param source источник транскрипции */
    public record Result(List<Map<String, Object>> answers, List<Map<String, Object>> words, String source) {}

    /** Выполняет итоговое распознавание и сопоставление со всеми принятыми ответами.
     * @param id ID интервью
     * @param wave проверенный WAV
     * @param journal завершённый журнал
     * @param input метаданные и резервный таймлайн Vosk
     * @param pcm временный путь для извлечения PCM
     * @param whisper клиент сервера распознавания
     * @return итоговый текст и ответы
     * @throws IOException при некорректных входах или недоступности распознавания
     * @throws InterruptedException если обработка прервана */
    public Result transcribe(String id, Path wave, Path journal, JsonNode input, Path pcm,
                             WhisperClient whisper) throws IOException, InterruptedException {
        return transcribe(id, wave, journal, input, pcm, whisper, false);
    }

    /** Разрешает резерв Vosk только после исчерпания повторов итогового распознавания.
     * @param id ID интервью
     * @param wave проверенный WAV
     * @param journal завершённый журнал
     * @param input метаданные и резервный таймлайн Vosk
     * @param pcm временный путь для извлечения PCM
     * @param whisper клиент сервера распознавания
     * @param allowVoskOnlyFallback разрешить полностью резервную транскрипцию
     * @return итоговый текст и ответы
     * @throws IOException при некорректных входах или недоступности распознавания
     * @throws InterruptedException если обработка прервана */
    public Result transcribe(String id, Path wave, Path journal, JsonNode input, Path pcm,
                             WhisperClient whisper, boolean allowVoskOnlyFallback)
            throws IOException, InterruptedException {
        int channels = input.path("channels").asInt();
        long samples = input.path("samples").asLong(-1);
        boolean testMode = input.path("testMode").asBoolean();
        if (input.path("schemaVersion").asInt() != 1 || !id.equals(input.path("sessionId").asText()) || input.path("sampleRate").asInt() != RATE
                || (channels != 1 && channels != 2) || samples <= 0 || !input.path("words").isArray()
                || !input.path("roles").isArray()) throw new IOException("Некорректные данные записи");
        var intervals = new InterviewJournalReplay().read(journal, id, samples);
        ArchiveWave.extract(wave, pcm, channels, samples);
        try (var audio = InterviewAudioSpool.openExisting(pcm, channels == 2, samples)) {
            List<FinalTranscriptBuilder.SpeakerSpan> roles = new ArrayList<>();
            List<PostInterviewWhisper.SpeechEvidence> evidence = new ArrayList<>();
            for (JsonNode row : input.path("roles")) {
                var role = Json.MAPPER.treeToValue(row, FinalTranscriptBuilder.SpeakerSpan.class);
                if (role.start() < 0 || role.end() <= role.start() || role.end() > samples)
                    throw new IOException("Некорректные границы диаризации");
                roles.add(role);
                evidence.add(new PostInterviewWhisper.SpeechEvidence(0, role.start(), role.end()));
            }
            for (JsonNode row : input.path("words")) {
                if (!"vosk".equals(row.path("model").asText())) continue;
                long first = Math.round(row.path("start").asDouble(-1) * RATE);
                long last = Math.round(row.path("end").asDouble(-1) * RATE);
                int channel = "microphone".equals(row.path("source").asText()) ? 1 : 0;
                if (first < 0 || last < first || last > samples || channel >= channels)
                    throw new IOException("Некорректные границы слов Vosk");
                if (last > first) evidence.add(new PostInterviewWhisper.SpeechEvidence(channel, first, last,
                        Math.max(1, Math.min(RATE / 5L, (last - first) / 2))));
            }
            var result = new PostInterviewWhisper(audio).transcribe(evidence, whisper, (done, total) -> {});
            if (result.words().isEmpty() && !result.gaps().isEmpty() && !allowVoskOnlyFallback)
                throw new IOException("Whisper не обработал запись; требуется повтор");
            var builder = new FinalTranscriptBuilder();
            List<Map<String, Object>> rows = builder.build(audio, result.words(), roles, testMode);
            boolean fallbackComplete = true;
            boolean fallbackUsed = false;
            for (var gap : result.gaps()) {
                List<PostInterviewWhisper.Word> reserve = new ArrayList<>();
                for (JsonNode row : input.path("words")) {
                    if (!"vosk".equals(row.path("model").asText())) continue;
                    int channel = "microphone".equals(row.path("source").asText()) ? 1 : 0;
                    long first = Math.round(row.path("start").asDouble() * RATE);
                    long last = Math.round(row.path("end").asDouble() * RATE);
                    long midpoint = first + (last - first) / 2;
                    boolean covered = result.words().stream().anyMatch(word -> word.channel() == channel
                            && midpoint >= word.start() && midpoint < word.end());
                    if (channel == gap.channel() && midpoint >= gap.start() && midpoint < gap.end() && !covered)
                        reserve.add(new PostInterviewWhisper.Word(channel, row.path("word").asText(), first, last, -1, null));
                }
                if (reserve.isEmpty()) {
                    fallbackComplete = false;
                    rows.add(Json.map("word", "[Аудио этого участка не распознано]",
                            "start", gap.start() / (double) RATE, "end", gap.end() / (double) RATE,
                            "role", testMode ? "Кандидат" : gap.channel() == 1 ? "Интервьюер 1" : "Не определено",
                            "source", gap.channel() == 1 ? "microphone" : testMode ? "test" : "system", "model", "unavailable",
                            "word_id", "gap:" + gap.channel() + ":" + gap.start(), "speaker_id", "gap", "status", "final"));
                } else {
                    fallbackUsed = true;
                    for (var row : builder.build(audio, reserve, roles, testMode)) {
                        row.put("role", row.get("role") + " (резерв Vosk)");
                        row.put("model", "vosk");
                        rows.add(row);
                    }
                }
            }
            if (allowVoskOnlyFallback && result.words().isEmpty() && (!fallbackUsed || !fallbackComplete))
                throw new IOException("Whisper недоступен, а резервная транскрипция Vosk неполна");
            String source = result.gaps().isEmpty() ? "whisper"
                    : result.words().isEmpty() ? "vosk_fallback" : "mixed";
            List<FinalAnswerAssembler.Word> words = new ArrayList<>();
            for (var row : rows) words.add(new FinalAnswerAssembler.Word(String.valueOf(row.get("word")),
                    Math.round(((Number) row.get("start")).doubleValue() * RATE),
                    Math.round(((Number) row.get("end")).doubleValue() * RATE),
                    String.valueOf(row.get("role")), String.valueOf(row.get("source")), String.valueOf(row.get("model"))));
            List<Map<String, Object>> answers = new ArrayList<>();
            for (var answer : new FinalAnswerAssembler().assemble(intervals, words, testMode)) {
                var interval = answer.interval();
                answers.add(Json.map("answerId", interval.answerId(), "questionId", interval.questionId(),
                        "followUpId", interval.followUpId(), "text", answer.text(),
                        "source", "vosk_fallback".equals(source) ? "vosk" : source,
                        "transcriptIncomplete", !answer.warnings().isEmpty()));
            }
            return new Result(List.copyOf(answers), List.copyOf(rows), source);
        }
    }
}
