package ru.sttplatform.stt.vosk;

import org.springframework.stereotype.Component;
import ru.sttplatform.stt.config.VoskModelHolder;
import ru.sttplatform.stt.config.VoskProperties;

import java.io.IOException;

/**
 * Создаёт отдельный распознаватель Vosk для каждого соединения.
 *
 * @version 1.0.0
 */
@Component
public class SpeechSessionFactory {

    private final VoskModelHolder modelHolder;
    private final VoskProperties props;

    /**
     * Подготавливает фабрику сессий с общей моделью и настройками Vosk.
     *
     * @param modelHolder загруженная модель Vosk, общая для сессий
     * @param props настройки приложения
     */
    public SpeechSessionFactory(VoskModelHolder modelHolder, VoskProperties props) {
        this.modelHolder = modelHolder;
        this.props = props;
    }

    /**
     * Создаёт новую сессию распознавания на общей загруженной модели.
     *
     * @return новая речевая сессия
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    public SpeechSession create() throws IOException {
        return new SpeechSession(modelHolder.getModel(), props.sampleRate());
    }
}
