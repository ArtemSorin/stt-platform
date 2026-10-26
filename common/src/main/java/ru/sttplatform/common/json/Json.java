package ru.sttplatform.common.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Общий JSON-помощник сервисов: единый {@link ObjectMapper}, упорядоченные карты событий и простая статистика.
 *
 * @version 1.0.0
 */
public final class Json {
    /** Общий потокобезопасный маппер. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    /**
     * Строит упорядоченную карту из пар «ключ, значение».
     *
     * @param pairs чётное число аргументов: ключ-строка, значение, ключ, значение…
     * @return изменяемая карта с сохранением порядка вставки
     */
    public static Map<String, Object> map(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Нужны пары ключ-значение.");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    /**
     * Сериализует объект в компактный JSON.
     *
     * @param value любой сериализуемый объект
     * @return строка JSON
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Разбирает JSON-текст в дерево.
     *
     * @param text строка JSON
     * @return корневой узел
     * @throws IOException если текст не является корректным JSON
     */
    public static JsonNode tree(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    /**
     * Преобразует JSON-узел в изменяемую карту.
     *
     * @param node узел-объект
     * @return карта полей
     */
    public static Map<String, Object> toMap(JsonNode node) {
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /**
     * Округляет число до заданного количества знаков.
     *
     * @param value исходное значение
     * @param digits число знаков после запятой
     * @return округлённое значение
     */
    public static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.rint(value * scale) / scale;
    }

    /**
     * Считает перцентиль методом линейной интерполяции.
     *
     * @param values выборка (может быть пустой)
     * @param fraction доля от 0 до 1
     * @return значение перцентиля или NaN для пустой выборки
     */
    public static double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        double position = (sorted.size() - 1) * fraction;
        int low = (int) Math.floor(position), high = (int) Math.ceil(position);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (position - low);
    }

    /**
     * Сводка выборки: количество, медиана, p95 и максимум.
     *
     * @param values выборка значений
     * @return карта {@code count, p50, p95, max}
     */
    public static Map<String, Object> distribution(List<Double> values) {
        if (values.isEmpty()) return map("count", 0, "p50", null, "p95", null, "max", null);
        return map("count", values.size(), "p50", round(percentile(values, .5), 1),
                "p95", round(percentile(values, .95), 1),
                "max", round(values.stream().mapToDouble(Double::doubleValue).max().orElse(0), 1));
    }
}
