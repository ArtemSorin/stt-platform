package ru.sttplatform.stt.transcript;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Создаёт атомарно опубликованную копию исходного PCM в контейнере WAV. */
public final class ArchiveWave {
    private ArchiveWave() {}

    /** Упаковывает проверенную запись в WAV PCM16 с частотой 16 кГц.
     * @param pcm путь к закрытой исходной записи
     * @param destination путь готового WAV
     * @param channels число синхронных каналов: один или два
     * @param samples число сэмплов на канал
     * @throws IOException если запись неполна либо не может быть сохранена
     */
    public static void write(Path pcm, Path destination, int channels, long samples) throws IOException {
        if ((channels != 1 && channels != 2) || samples <= 0 || samples > (0xffffffffL - 36) / (channels * 2L))
            throw new IOException("Недопустимый формат или размер WAV");
        long length = samples * channels * 2L;
        if (Files.size(pcm) != length) throw new IOException("Размер PCM не совпадает с числом сэмплов");
        Path parent = destination.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".audio-", ".tmp");
        try {
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + length));
            header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1);
            header.putShort((short) channels).putInt(16000).putInt(16000 * channels * 2);
            header.putShort((short) (channels * 2)).putShort((short) 16);
            header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) length);
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                output.write(header.array());
                long copied = Files.copy(pcm, output);
                if (copied != length) throw new IOException("PCM изменился во время архивации");
            }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, destination); }
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Извлекает PCM из WAV, ранее созданного архивом, проверяя формат и длину.
     * @param wave архивная запись
     * @param destination новый файл PCM
     * @param channels ожидаемое число каналов
     * @param samples ожидаемое число сэмплов на канал
     * @throws IOException если заголовок или размер не соответствуют метаданным */
    public static void extract(Path wave, Path destination, int channels, long samples) throws IOException {
        if ((channels != 1 && channels != 2) || samples <= 0 || samples > (0xffffffffL - 36) / (channels * 2L))
            throw new IOException("Недопустимый формат или размер WAV");
        long length = samples * channels * 2L;
        if (Files.size(wave) != length + 44) throw new IOException("Размер WAV не соответствует метаданным");
        byte[] expected = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + length))
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) channels).putInt(16000).putInt(16000 * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) length).array();
        try (var input = Files.newInputStream(wave)) {
            if (!java.util.Arrays.equals(expected, input.readNBytes(44))) throw new IOException("Недопустимый заголовок WAV");
            try (var output = Files.newOutputStream(destination, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                if (input.transferTo(output) != length) throw new IOException("Неполный WAV");
            } catch (IOException error) {
                Files.deleteIfExists(destination);
                throw error;
            }
        }
    }
}
