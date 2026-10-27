package ru.sttplatform.analytics.export;

import java.util.List;

/**
 * Столбчатая диаграмма по диапазону ячеек листа (данные берутся из самого листа, поэтому диаграмма «живая»).
 *
 * @param title заголовок диаграммы
 * @param horizontal {@code true} для горизонтальных полос
 * @param sheetName имя листа с данными
 * @param firstRow первая строка диапазона данных
 * @param lastRow последняя строка диапазона данных
 * @param catCol столбец категорий
 * @param valCol столбец значений
 * @param cats категории для кэша диаграммы
 * @param vals значения для кэша диаграммы
 * @param axisMax верхняя граница оси значений
 * @param color цвет столбцов в формате RGB
 * @param labelFormat формат подписей значений
 * @version 1.0.0
 */
public record XlsxChart(String title, boolean horizontal, String sheetName, int firstRow, int lastRow,
                        int catCol, int valCol, List<String> cats, List<Double> vals,
                        double axisMax, String color, String labelFormat) {
}
