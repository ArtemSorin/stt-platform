package ru.sttplatform.diarization.audio;

/**
 * Блок аудио с позицией первого сэмпла в общей временной шкале сессии.
 *
 * @param index порядковый номер блока
 * @param firstSample номер первого сэмпла
 * @param samples сэмплы от -1 до 1
 * @param capturedAtNanos момент получения блока ({@code System.nanoTime()})
 * @version 1.0.0
 */
public record AudioChunk(long index, long firstSample, float[] samples, long capturedAtNanos) {}
