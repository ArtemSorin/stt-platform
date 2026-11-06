package ru.sttplatform.analytics.export;

import org.junit.jupiter.api.Test;
import ru.sttplatform.common.json.Json;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportFilesAndJsonCoverageTest {

    @Test
    void file_name_is_transliterated_when_the_file_system_cannot_store_cyrillic() {
        String saved = System.getProperty("sun.jnu.encoding");
        try {
            System.setProperty("sun.jnu.encoding", "US-ASCII");
            String name = ReportFiles.fileName("Иван Ёжиков-Щукин Ъ", Instant.parse("2026-10-14T20:54:00Z"));
            assertTrue(name.startsWith("Interview_2026-10-1"));
            assertTrue(name.endsWith(".xlsx"));
            assertTrue(name.contains("Ivan_Ezhikov_Shchukin"), name);
            assertTrue(name.chars().allMatch(c -> c < 128), name);

            System.setProperty("sun.jnu.encoding", "НЕТ-ТАКОЙ-КОДИРОВКИ");
            assertTrue(ReportFiles.encodable("кириллица"), "неизвестная кодировка не должна ломать экспорт");
            System.clearProperty("sun.jnu.encoding");
            assertTrue(ReportFiles.encodable("кириллица"));
            System.setProperty("sun.jnu.encoding", "UTF-8");
            assertFalse(ReportFiles.fileName("Иван", Instant.now()).contains("Ivan"));
        } finally {
            if (saved == null) System.clearProperty("sun.jnu.encoding"); else System.setProperty("sun.jnu.encoding", saved);
        }
    }

    @Test
    void file_name_handles_empty_names_and_long_names_and_symbols() {
        Instant t = Instant.parse("2026-01-02T03:04:00Z");
        assertTrue(ReportFiles.fileName(null, t).contains("кандидат") || ReportFiles.fileName(null, t).contains("kandidat"));
        assertTrue(ReportFiles.fileName("   ", t).contains("кандидат") || ReportFiles.fileName("   ", t).contains("kandidat"));
        String longName = ReportFiles.fileName("x".repeat(100), t);
        assertTrue(longName.contains("x".repeat(40)) && !longName.contains("x".repeat(41)));
        assertTrue(ReportFiles.fileName("a/b\\c:d", t).contains("a_b_c_d"));
    }

    @Test
    void translit_keeps_case_ascii_and_replaces_unknown_symbols() {
        assertEquals("Ivan", ReportFiles.translit("Иван"));
        assertEquals("Shchuka", ReportFiles.translit("Щука"));
        assertEquals("Ezh", ReportFiles.translit("Ёж"));
        assertEquals("a_1", ReportFiles.translit("a✓1"));
        assertEquals("", ReportFiles.translit("ъь"));
    }

    @Test
    void json_helpers() {
        assertThrows(IllegalArgumentException.class, () -> Json.map("только-ключ"));
        assertEquals(Map.of("a", 1), Json.map("a", 1));
        assertThrows(IllegalStateException.class, () -> Json.write(new Object()));
        assertTrue(Double.isNaN(Json.percentile(List.of(), 0.5)));
        assertEquals(5.0, Json.percentile(List.of(5.0), 0.99), 1e-9);
        assertEquals(1.0, Json.percentile(List.of(3.0, 1.0, 2.0), 0.0), 1e-9);
        assertEquals(3.0, Json.percentile(List.of(3.0, 1.0, 2.0), 1.0), 1e-9);
        assertEquals(2.5, Json.percentile(List.of(1.0, 2.0, 3.0, 4.0), 0.5), 1e-9);
    }
}
