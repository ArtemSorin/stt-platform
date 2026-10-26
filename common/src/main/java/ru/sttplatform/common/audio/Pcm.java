package ru.sttplatform.common.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Преобразования PCM16 little-endian ↔ float, общие для всех сервисов.
 *
 * @version 1.0.0
 */
public final class Pcm {
    /** Частота дискретизации всей платформы, Гц. */
    public static final int RATE = 16_000;
    /** Байт PCM16 моно в одной миллисекунде. */
    public static final int BYTES_PER_MS = RATE * 2 / 1000;

    private Pcm() {}

    /**
     * Переводит PCM16 little-endian в сэмплы от -1 до 1.
     *
     * @param bytes сырой PCM без заголовка
     * @return сэмплы с плавающей точкой
     */
    public static float[] toFloats(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / 2];
        for (int i = 0; i < result.length; i++) result[i] = buffer.getShort() / 32768f;
        return result;
    }

    /**
     * Переводит сэмплы от -1 до 1 в PCM16 little-endian.
     *
     * @param samples сэмплы с плавающей точкой
     * @return сырой PCM без заголовка
     */
    public static byte[] toBytes(float[] samples) {
        byte[] result = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int value = Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767)));
            result[i * 2] = (byte) value;
            result[i * 2 + 1] = (byte) (value >>> 8);
        }
        return result;
    }

    /**
     * Проверяет, что блок состоит только из нулевых байтов (полная тишина после маскирования).
     *
     * @param data PCM-блок
     * @return {@code true}, если все байты нулевые
     */
    public static boolean isSilent(byte[] data) {
        for (byte value : data) if (value != 0) return false;
        return true;
    }
}
