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

    private record Word(double start, double end, String role, String text, String source, String id) {}
    private record Turn(double start, double end, String role, String text) {}

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
            words.add(new Word(s.doubleValue(), e.doubleValue(), role, text, String.valueOf(row.get("source")), String.valueOf(row.get("word_id"))));
        }
        words.sort(Comparator.comparingDouble(Word::start).thenComparing(Word::source).thenComparing(Word::id));
        // Слова разных говорящих могут идти одновременно. Собираем каждую роль
        // отдельно, иначе пересечение превращает длинный ответ в строки по слову.
        Map<String, List<Turn>> byRole = new java.util.LinkedHashMap<>();
        Map<String, Boolean> interrupted = new java.util.HashMap<>();
        for (Word word : words) {
            for (var entry : byRole.entrySet()) {
                if (entry.getKey().equals(word.role())) continue;
                List<Turn> earlier = entry.getValue();
                if (word.start() >= earlier.get(earlier.size() - 1).end())
                    interrupted.put(entry.getKey(), true);
            }
            List<Turn> turns = byRole.computeIfAbsent(word.role(), ignored -> new ArrayList<>());
            if (turns.isEmpty() || Boolean.TRUE.equals(interrupted.remove(word.role()))
                    || word.start() - turns.get(turns.size()-1).end() > 1.5) {
                turns.add(new Turn(word.start(), word.end(), word.role(), word.text()));
            } else {
                Turn last = turns.remove(turns.size()-1);
                String separator = word.text().matches("^[.,!?;:%)»].*") ? "" : " ";
                turns.add(new Turn(last.start(), Math.max(last.end(), word.end()), last.role(), last.text() + separator + word.text()));
            }
        }
        List<Turn> turns = new ArrayList<>();
        byRole.values().forEach(turns::addAll);
        turns.sort(Comparator.comparingDouble(Turn::start).thenComparingDouble(Turn::end).thenComparing(Turn::role));
        return turns;
    }

    private static String time(double seconds) {
        long ms = Math.round(seconds * 1000), hours = ms / 3_600_000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d", hours, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }
}
