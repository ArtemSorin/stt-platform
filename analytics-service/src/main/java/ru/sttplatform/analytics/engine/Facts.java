package ru.sttplatform.analytics.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Делит эталонный ответ на отдельные утверждения («факты»): каждое проверяется моделью как «сказал / не сказал».
 * Предложения не рвутся внутри скобок и кавычек; слишком короткие обрывки приклеиваются к предыдущему факту.
 *
 * @version 1.0.0
 */
public final class Facts {
    /** Не больше фактов на вопрос: каждый — отдельный вопрос к модели. */
    public static final int MAX_FACTS = 6;
    /** Длиннее обрезается по запятой или пробелу: у инструкции к модели лимит 96 токенов вместе с обязательным суффиксом. */
    public static final int MAX_CHARS = 100;
    private static final int MIN_CHARS = 25;

    private Facts() {}

    /**
     * Разбивает эталон на факты.
     *
     * @param reference текст эталонного ответа
     * @return от одного до {@link #MAX_FACTS} утверждений; пустой список для пустого эталона
     */
    public static List<String> split(String reference) {
        List<String> parts = new ArrayList<>();
        if (reference == null || reference.isBlank()) return parts;
        String text = reference.strip();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == '«') quoted = true;
            else if (c == '»') quoted = false;
            boolean end = (c == '.' || c == '!' || c == '?' || c == ';') && depth == 0 && !quoted
                    && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)));
            if (end) {
                flush(parts, current);
            }
        }
        flush(parts, current);

        List<String> merged = new ArrayList<>();
        for (String part : parts) {
            if (!merged.isEmpty() && (part.length() < MIN_CHARS || merged.size() >= MAX_FACTS)) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + " " + part);
            } else {
                merged.add(part);
            }
        }
        List<String> out = new ArrayList<>(merged.size());
        for (String fact : merged) out.add(clip(fact, MAX_CHARS));
        return out;
    }

    private static void flush(List<String> parts, StringBuilder current) {
        String s = current.toString().strip();
        current.setLength(0);
        if (!s.isEmpty()) parts.add(s);
    }

    /**
     * Обрезает текст до длины по границе слова.
     *
     * @param text исходный текст
     * @param max максимум символов (с многоточием)
     * @return исходный или укороченный текст
     */
    public static String clip(String text, int max) {
        if (text.length() <= max) return text;
        int cut = text.lastIndexOf(", ", max - 2);
        if (cut < max / 2) cut = text.lastIndexOf(' ', max - 2);
        if (cut < max / 2) cut = max - 2;
        return text.substring(0, cut).stripTrailing() + "…";
    }
}
