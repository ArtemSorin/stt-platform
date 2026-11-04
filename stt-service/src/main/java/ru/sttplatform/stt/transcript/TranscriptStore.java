package ru.sttplatform.stt.transcript;

import org.springframework.stereotype.Component;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.gateway.config.GatewayProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Создаёт и хранит опубликованные транскрипты завершённых сессий. */
@Component
public class TranscriptStore {
    private final Path root;

    /** @param props каталог результатов интегрированных интервью */
    public TranscriptStore(GatewayProperties props) {
        root = Path.of(props.outputDir()).toAbsolutePath().normalize();
    }

    private record Word(double start, double end, String role, String text, String source, String speaker, String id) {
        String key() { return source + "\u0000" + speaker + "\u0000" + role; }
    }
    private record Turn(double start, double end, String role, String text) {}
    private record TurnSpan(double start, double end, String key) {}

    /** Публикует оба варианта и архив; маркер готовности записывается последним.
     * @param directory каталог сессии
     * @param id полный UUID сессии
     * @param rows замороженный таймлайн слов
     * @throws IOException при ошибке записи
     */
    public void publish(Path directory, String id, List<Map<String, Object>> rows) throws IOException {
        UUID.fromString(id);
        Path session = directory.toAbsolutePath().normalize();
        if (!session.startsWith(root) || !session.getParent().equals(root)) throw new IOException("Неверный каталог сессии");
        Path dir = session.resolve("transcripts");
        Files.createDirectories(dir);
        if (Files.isRegularFile(dir.resolve("ready.json"))) {
            if (!id.equals(Json.MAPPER.readTree(dir.resolve("ready.json").toFile()).path("sessionId").asText()))
                throw new IOException("Каталог уже принадлежит другой сессии");
            return;
        }
        List<Turn> turns = turns(rows);
        StringBuilder plain = new StringBuilder(), timed = new StringBuilder();
        for (Turn turn : turns) {
            String line = turn.role() + ": " + turn.text();
            plain.append(line).append('\n');
            timed.append('[').append(time(turn.start())).append('–').append(time(turn.end())).append("] ").append(line).append('\n');
        }
        byte[] p = plain.toString().getBytes(StandardCharsets.UTF_8), t = timed.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("transcript.md")); zip.write(p); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("transcript-timed.md")); zip.write(t); zip.closeEntry();
        }
        write(dir, "transcript.md", p); write(dir, "transcript-timed.md", t); write(dir, "transcripts.zip", bytes.toByteArray());
        write(dir, "ready.json", Json.MAPPER.writeValueAsBytes(Map.of("sessionId", id)));
    }

    private static void write(Path dir, String name, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(dir, ".transcript-", ".tmp");
        try { Files.write(tmp, data); Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(tmp); }
    }

    /** Находит опубликованный файл по идентификатору сессии.
     * @param id полный UUID сессии
     * @param name одно из трёх фиксированных имён файлов
     * @return путь к файлу или {@code null}
     * @throws IOException при ошибке чтения каталога
     */
    public Path find(String id, String name) throws IOException {
        try { if (!UUID.fromString(id).toString().equals(id)) return null; } catch (IllegalArgumentException e) { return null; }
        if (!List.of("transcript.md", "transcript-timed.md", "transcripts.zip").contains(name) || !Files.isDirectory(root)) return null;
        try (var dirs = Files.list(root)) {
            for (Path session : dirs.filter(p -> Files.isDirectory(p) && !Files.isSymbolicLink(p)).toList()) {
                Path dir = session.resolve("transcripts"), marker = dir.resolve("ready.json");
                if (Files.isSymbolicLink(dir) || !Files.isRegularFile(marker) || Files.isSymbolicLink(marker)) continue;
                try {
                    if (!id.equals(Json.MAPPER.readTree(marker.toFile()).path("sessionId").asText())) continue;
                } catch (IOException malformed) { continue; }
                Path file = dir.resolve(name);
                return Files.isRegularFile(file) && !Files.isSymbolicLink(file) ? file : null;
            }
        }
        return null;
    }

    private static List<Turn> turns(List<Map<String, Object>> rows) throws IOException {
        List<Word> words = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!"final".equals(row.get("status"))) continue;
            String text = String.valueOf(row.getOrDefault("word", "")).trim();
            if (text.isEmpty()) continue;
            if (!(row.get("start") instanceof Number s) || !(row.get("end") instanceof Number e)
                    || !Double.isFinite(s.doubleValue()) || !Double.isFinite(e.doubleValue()) || s.doubleValue() < 0 || e.doubleValue() < s.doubleValue())
                throw new IOException("Неверное время слова в таймлайне");
            String role = String.valueOf(row.getOrDefault("role", "Не определено"));
            if (role.isBlank() || "null".equals(role)) role = "Не определено";
            words.add(new Word(s.doubleValue(), e.doubleValue(), role, text, String.valueOf(row.get("source")),
                    String.valueOf(row.getOrDefault("speaker_id", role)), String.valueOf(row.get("word_id"))));
        }
        words.sort(Comparator.comparingDouble(Word::start).thenComparing(Word::source).thenComparing(Word::id));
        // Сначала собираем слова каждого канала и голоса: совпадение времени не разрывает чужую реплику.
        Map<String, List<Word>> groups = new java.util.LinkedHashMap<>();
        for (Word word : words) groups.computeIfAbsent(word.key(), ignored -> new ArrayList<>()).add(word);
        List<TurnSpan> spans = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            double start = -1, end = -1;
            for (Word word : entry.getValue()) {
                if (start < 0 || word.start() - end > 1.5) {
                    if (start >= 0) spans.add(new TurnSpan(start, end, entry.getKey()));
                    start = word.start(); end = word.end();
                } else end = Math.max(end, word.end());
            }
            if (start >= 0) spans.add(new TurnSpan(start, end, entry.getKey()));
        }
        spans.sort(Comparator.comparingDouble(TurnSpan::start));
        List<Turn> turns = new ArrayList<>();
        for (List<Word> group : groups.values()) {
            StringBuilder text = new StringBuilder();
            double start = -1, end = -1;
            String role = null;
            for (Word word : group) {
                boolean interrupted = false;
                if (start >= 0 && word.start() >= end) {
                    int low = 0, high = spans.size();
                    while (low < high) {
                        int mid = (low + high) >>> 1;
                        if (spans.get(mid).start() < end) low = mid + 1; else high = mid;
                    }
                    for (int i = low; i < spans.size() && spans.get(i).start() <= word.start(); i++) {
                        TurnSpan other = spans.get(i);
                        if (!other.key().equals(word.key()) && other.end() <= word.start()) { interrupted = true; break; }
                    }
                }
                if (start < 0 || interrupted || word.start() - end > 1.5) {
                    if (start >= 0) turns.add(new Turn(start, end, role, text.toString()));
                    start = word.start(); end = word.end(); role = word.role();
                    text.setLength(0); text.append(word.text());
                } else {
                    String separator = word.text().matches("^[.,!?;:%)»].*") ? "" : " ";
                    end = Math.max(end, word.end());
                    text.append(separator).append(word.text());
                }
            }
            if (start >= 0) turns.add(new Turn(start, end, role, text.toString()));
        }
        turns.sort(Comparator.comparingDouble(Turn::start).thenComparingDouble(Turn::end).thenComparing(Turn::role));
        return turns;
    }

    private static String time(double seconds) {
        long ms = Math.round(seconds * 1000), hours = ms / 3_600_000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d", hours, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }
}
