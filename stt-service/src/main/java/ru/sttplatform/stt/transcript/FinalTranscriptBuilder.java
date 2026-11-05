package ru.sttplatform.stt.transcript;

import ru.sttplatform.common.json.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Назначает роли итоговым словам Whisper и осторожно убирает подтверждённое эхо. */
public final class FinalTranscriptBuilder {
    private static final int RATE = 16_000;

    /** @param start начало интервала в сэмплах
     * @param end конец интервала
     * @param role подтверждённая роль
     * @param speaker идентификатор голоса диаризации */
    public record SpeakerSpan(long start, long end, String role, String speaker) {}

    /** Создаёт строки таймлайна только из итогового прохода Whisper.
     * @param source исходная синхронная запись
     * @param words слова обоих каналов
     * @param spans окончательные интервалы системного канала
     * @param testMode единственный канал является кандидатом
     * @return слова с ролями и идентификаторами говорящих
     * @throws IOException при ошибке чтения аудио для проверки эха
     */
    public List<Map<String, Object>> build(InterviewAudioSpool source, List<PostInterviewWhisper.Word> words,
                                           List<SpeakerSpan> spans, boolean testMode) throws IOException {
        List<SpeakerSpan> ordered = spans.stream().sorted(Comparator.comparingLong(SpeakerSpan::start)).toList();
        long[] maxEnds = new long[ordered.size()];
        for (int i = 0; i < ordered.size(); i++)
            maxEnds[i] = Math.max(i == 0 ? 0 : maxEnds[i - 1], ordered.get(i).end());
        Map<String, String> interviewers = new HashMap<>();
        for (SpeakerSpan span : ordered) {
            if (span.role().startsWith("Интервьюер") && !interviewers.containsKey(span.speaker()))
                interviewers.put(span.speaker(), "Интервьюер " + (interviewers.size() + (source.channels() == 2 ? 2 : 1)));
        }
        Set<PostInterviewWhisper.Word> echoes = source.channels() == 2 ? echoWords(source, words) : Set.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (PostInterviewWhisper.Word word : words) {
            if (echoes.contains(word)) continue;
            String role, speaker;
            if (testMode) { role = "Кандидат"; speaker = "test-candidate"; }
            else if (word.channel() == 1) { role = "Интервьюер 1"; speaker = "local-microphone"; }
            else {
                SpeakerSpan bestSpan = null;
                long best = 0, second = 0;
                Map<String, SpeakerSpan> perSpeaker = new HashMap<>();
                Map<String, Long> overlaps = new HashMap<>();
                int low = 0, high = ordered.size();
                while (low < high) {
                    int mid = (low + high) >>> 1;
                    if (maxEnds[mid] <= word.start()) low = mid + 1; else high = mid;
                }
                for (int index = low; index < ordered.size() && ordered.get(index).start() < word.end(); index++) {
                    SpeakerSpan span = ordered.get(index);
                    long overlap = Math.max(0, Math.min(word.end(), span.end()) - Math.max(word.start(), span.start()));
                    if (overlap > overlaps.getOrDefault(span.speaker(), 0L)) {
                        overlaps.put(span.speaker(), overlap); perSpeaker.put(span.speaker(), span);
                    }
                }
                for (var entry : overlaps.entrySet()) {
                    long overlap = entry.getValue();
                    if (overlap > best) { second = best; best = overlap; bestSpan = perSpeaker.get(entry.getKey()); }
                    else second = Math.max(second, overlap);
                }
                if (bestSpan == null || word.end() <= word.start() || best * 2 < word.end() - word.start()
                        || second * 4 >= best * 3) {
                    role = "Не определено"; speaker = "unknown";
                } else {
                    role = bestSpan.role().startsWith("Интервьюер")
                            ? interviewers.get(bestSpan.speaker()) : bestSpan.role();
                    if (!"Кандидат".equals(role) && !role.startsWith("Интервьюер ")) role = "Не определено";
                    speaker = bestSpan.speaker();
                }
            }
            result.add(Json.map("word", word.text(), "word_id", "whisper:full:" + word.channel() + ":" + result.size(),
                    "start", Json.round(word.start() / (double) RATE, 3), "end", Json.round(word.end() / (double) RATE, 3),
                    "role", role, "role_status", "Не определено".equals(role) ? "unknown" : "confirmed",
                    "source", testMode ? "test" : word.channel() == 0 ? "system" : "microphone",
                    "speaker_id", speaker, "model", "whisper", "status", "final", "confidence", word.confidence()));
        }
        return result;
    }

    private static Set<PostInterviewWhisper.Word> echoWords(InterviewAudioSpool audio, List<PostInterviewWhisper.Word> words) throws IOException {
        List<PostInterviewWhisper.Word> system = words.stream().filter(w -> w.channel() == 0)
                .sorted(Comparator.comparingLong(PostInterviewWhisper.Word::start)).toList();
        List<PostInterviewWhisper.Word> mic = words.stream().filter(w -> w.channel() == 1)
                .sorted(Comparator.comparingLong(PostInterviewWhisper.Word::start)).toList();
        Set<PostInterviewWhisper.Word> remove = new HashSet<>();
        int i = 0, j = 0;
        while (i < system.size() && j < mic.size()) {
            PostInterviewWhisper.Word a = system.get(i), b = mic.get(j);
            if (Math.abs(a.start() - b.start()) <= RATE * 15L / 100 && same(a.text(), b.text())) {
                int count = 0;
                while (i + count < system.size() && j + count < mic.size()) {
                    a = system.get(i + count); b = mic.get(j + count);
                    if (count > 0 && Math.max(a.end() - system.get(i).start(), b.end() - mic.get(j).start()) > 3L * RATE) break;
                    if (Math.abs(a.start() - b.start()) > RATE * 15L / 100 || !same(a.text(), b.text())) break;
                    count++;
                }
                if (count >= 3) {
                    long start = Math.max(system.get(i).start(), mic.get(j).start());
                    long end = Math.min(system.get(i + count - 1).end(), mic.get(j + count - 1).end());
                    int quieter = correlatedQuieter(audio, start, end);
                    if (quieter >= 0) for (int k = 0; k < count; k++)
                        remove.add(quieter == 0 ? system.get(i + k) : mic.get(j + k));
                }
                i += count; j += count;
            } else if (a.start() <= b.start()) i++; else j++;
        }
        return remove;
    }

    private static boolean same(String a, String b) {
        String left = a.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return !left.isEmpty() && left.equals(b.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""));
    }

    private static int correlatedQuieter(InterviewAudioSpool audio, long start, long end) throws IOException {
        if (end - start < RATE / 2) return -1;
        byte[] a = audio.read(0, start, end), b = audio.read(1, start, end);
        double aa = 0, bb = 0, ab = 0;
        for (int i = 0; i + 1 < a.length; i += 16) {
            int x = (short) ((a[i] & 255) | (a[i + 1] << 8));
            int y = (short) ((b[i] & 255) | (b[i + 1] << 8));
            aa += (double) x * x; bb += (double) y * y; ab += (double) x * y;
        }
        if (aa == 0 || bb == 0 || Math.abs(ab) / Math.sqrt(aa * bb) < .98) return -1;
        if (aa <= bb * .25) return 0;
        if (bb <= aa * .25) return 1;
        return -1;
    }
}
