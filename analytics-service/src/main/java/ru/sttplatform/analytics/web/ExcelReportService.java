package ru.sttplatform.analytics.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.sttplatform.analytics.engine.InterviewDetails;
import ru.sttplatform.analytics.export.InterviewExcelReport;
import ru.sttplatform.analytics.export.ReportFiles;
import ru.sttplatform.analytics.live.ReportExporter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Сохраняет Excel-отчёт в interview.reports-dir (по умолчанию ./reports) и отдаёт ссылку для скачивания. */
@Service
public class ExcelReportService implements ReportExporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelReportService.class);

    private final Path dir;
    private final boolean download;

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param props настройки приложения
     */
    public ExcelReportService(InterviewProperties props) {
        this.dir = Path.of(props.reportsDir()).toAbsolutePath().normalize();
        this.download = props.reportDownload();
    }

    /**
     * Сохраняет новый XLSX и формирует ссылку, если скачивание разрешено.
     *
     * @param details данные завершённого интервью
     * @return результат операции
     * @throws Exception если операция или подключённый сервис завершились ошибкой
     */
    @Override
    public Result export(InterviewDetails details) throws Exception {
        byte[] bytes = InterviewExcelReport.build(details);
        Files.createDirectories(dir);
        Path file = ReportFiles.uniquePath(dir, ReportFiles.fileName(details.candidate(), details.startedAt()));
        Files.write(file, bytes);
        log.info("Excel-отчёт сохранён: {} ({} КБ)", file, bytes.length / 1024);
        String name = file.getFileName().toString();
        String url = download ? "/api/reports/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20") : null;
        return new Result(name, file.toString(), url);
    }

    /** Файл для скачивания или null (имя неподходящее, файла нет, скачивание выключено). */
    public Path find(String name) {
        if (!download || !ReportFiles.isSafeName(name)) return null;
        Path p = dir.resolve(name).normalize();
        return p.startsWith(dir) && Files.isRegularFile(p) ? p : null;
    }
}
