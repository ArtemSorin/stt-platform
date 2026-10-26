package ru.sttplatform.diarization.session;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import ru.sttplatform.common.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Запись результатов на диск.
 *
 * @version 1.0.0
 */
final class Out {
    private Out() {}

    static void writePretty(Path path, Object data) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        String pretty = Json.MAPPER.writer(printer).writeValueAsString(data) + "\n";
        Files.writeString(path, pretty.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
    }
}
