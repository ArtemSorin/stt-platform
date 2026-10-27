package ru.sttplatform.analytics.live;

import ru.sttplatform.analytics.engine.InterviewDetails;

/** Сохраняет итоговый отчёт (в приложении — Excel-файл).
 * @version 1.0.0
 */
public interface ReportExporter {

    /**
     * @param fileName    имя файла
     * @param path        полный путь на диске
     * @param downloadUrl ссылка для скачивания из браузера (null — скачивание выключено)
     * @version 1.0.0
     */
    record Result(String fileName, String path, String downloadUrl) {}

    Result export(InterviewDetails details) throws Exception;
}
