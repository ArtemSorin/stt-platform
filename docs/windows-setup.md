# Windows: установка и запуск

Проверенный профиль проекта — Windows x64, JDK 17, PowerShell, IntelliJ IDEA и Chrome/Edge. Для основного сценария нужны локальные Vosk и Nemotron/CAM++ модели. Пути и каталоги модели не коммитятся.

## 1. Подготовка моделей

### Vosk

Из корня репозитория выполните:

```powershell
powershell -ExecutionPolicy Bypass -File .\stt-service\download-model.ps1
```

Скрипт загружает и распаковывает `vosk-model-ru-0.42` в `stt-service/models/`. Убедитесь, что внутри каталога есть файлы модели (например, `am/`, `conf/`, `graph/`). Если модель уже находится в другом месте, задайте полный путь:

```powershell
$env:VOSK_MODEL_PATH = 'D:\models\vosk-model-ru-0.42'
```

Для сохранения настройки в профиле пользователя используйте системные переменные Windows и перезапустите IntelliJ. Не помещайте модели в Git.

### Nemotron и CAM++

Для основного режима задайте `NEMOTRON_HOME` на каталог с файлами:

```text
<NEMOTRON_HOME>/runtime/bin/nemo_speech_asr_c.dll
<NEMOTRON_HOME>/model.gguf
<NEMOTRON_HOME>/speaker_embedding_model.onnx
```

По умолчанию используется `C:/Users/Public/NemotronTest`. При ошибке загрузки нативной библиотеки проверьте архитектуру x64 и используемый JDK; скрипт `stt-service/prepare-windows-jdk.ps1` помогает подготовить среду для случая несовместимой библиотеки Microsoft C++ Runtime.

Если Nemotron сообщает о несовместимой версии Microsoft C++ Runtime, подготовьте отдельную копию JDK с DLL из runtime Nemotron:

```powershell
powershell -ExecutionPolicy Bypass -File .\stt-service\prepare-windows-jdk.ps1 `
  -JdkHome $env:JAVA_HOME `
  -NemoBin "$env:NEMOTRON_HOME\runtime\bin" `
  -Target "$env:LOCALAPPDATA\stt-platform\java-runtime"
```

Убедитесь, что `JAVA_HOME` указывает на JDK 17, а `NEMOTRON_HOME` — на каталог модели. В IntelliJ выберите созданный каталог `java-runtime` как Project SDK и JRE конфигураций STT. Скрипт копирует `bin` и DLL Microsoft C++ Runtime в отдельный каталог, остальные каталоги JDK подключает junction-ссылками; исходный JDK не меняется.

## 2. Общий секрет событий

`analytics-service` проверяет заголовок `X-Internal-Token` при WebSocket handshake. Тот же секрет нужен STT для подключения к analytics. Он должен быть не короче 32 символов. Установите переменную окружения Windows `INTERVIEW_EVENTS_TOKEN` и перезапустите IntelliJ:

```powershell
$bytes = New-Object byte[] 48
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$token = [Convert]::ToBase64String($bytes)
[Environment]::SetEnvironmentVariable('INTERVIEW_EVENTS_TOKEN', $token, 'User')
```

Не добавляйте секрет в `.run/*.xml`, `application.yml`, журналы или коммиты. В IntelliJ-конфигурации **All services** должны запускаться процессы, наследующие одну и ту же переменную окружения.

## 3. Запуск

1. Откройте корневой `pom.xml` как Maven-проект, дождитесь импорта и выполните `mvn test`.
2. Запустите IntelliJ-конфигурацию **All services**. Она содержит только `stt-service` и `analytics-service`.
3. Откройте `http://localhost:8080/` в Chrome или Edge.
4. Разрешите микрофон и демонстрацию всего экрана со звуком. Во время десятисекундной записи эталона должен говорить только кандидат, его голос должен воспроизводиться на компьютере.
5. После записи STT распознаёт системный звук и микрофон раздельно и собирает общую хронологию; диаризация подтверждает голос кандидата для аналитики. Остановите интервью кнопкой, дождитесь отчёта.

Если `VOSK_MODEL_PATH` или `NEMOTRON_HOME` нужны только для одного запуска, задайте их в Environment variables обеих конфигураций, где используются эти файлы, прежде чем нажать Run.

## 4. Необязательные помощники моделей

### Whisper

Whisper запускается рядом со STT: он уточняет ответы во время интервью и после завершения распознаёт всю запись для Markdown. Чтобы проверить HTTP-контракт без скачивания модели, откройте отдельный терминал:

```powershell
python .\stt-service\whisper\whisper_server.py --backend fake --port 9000
```

Тестовый backend отвечает фиксированным текстом и не распознаёт речь. Для реального второго прохода установите зависимости и запустите модель:

```powershell
python -m venv .venv-whisper
.\.venv-whisper\Scripts\Activate.ps1
python -m pip install -r .\stt-service\whisper\requirements.txt
python .\stt-service\whisper\whisper_server.py --model small --device cpu
```

Whisper выключен по умолчанию. Чтобы включить его в основном и тестовом режиме, задайте `WHISPER_URL=http://127.0.0.1:9000` в окружении `stt-service` и запустите helper. Вместо отдельного терминала можно включить автозапуск переменной `WHISPER_AUTOSTART=true`; без явного `WHISPER_URL` в этом режиме используется локальный адрес `127.0.0.1:9000`. Для виртуального окружения задайте `WHISPER_PYTHON` на путь к его `python.exe`, а нужные модель и устройство — через `WHISPER_MODEL` и `WHISPER_DEVICE`. На `/test.html` флажок Whisper включён по умолчанию; при выключенном Whisper запись для итоговой обработки не создаётся. Если настроенный помощник недоступен, результат может содержать помеченный резерв Vosk. После интервью дождитесь обработки обоих каналов и скачайте ZIP на странице.

### FRIDA-Decisions

Чтобы запустить полное интервью без модели FRIDA, выберите IntelliJ-конфигурацию **All services без FRIDA**. Она запускает STT и аналитику, но передаёт аналитике `JUDGE_ENABLED=false` и `JUDGE_AUTOSTART=false`; решение и оценки выполняются резервными правилами. Браузерная страница остаётся `http://localhost:8080/integrated.html`, отдельный процесс на порту `9100` не запускается.

Для проверки связи без настоящей модели запустите сервер с тестовым backend:

```powershell
python .\analytics-service\judge\frida_server.py --backend fake --host 127.0.0.1 --port 9100
```

`fake` — фиксированный ответ для проверки контракта, а не модель FRIDA. Для реального backend установите зависимости из `analytics-service/judge/requirements.txt` и запустите, например:

```powershell
python .\analytics-service\judge\frida_server.py --backend torch --device cuda
```

Недоступность FRIDA переводит анализ на правила. Измеряйте фактическую работу на своей машине через `GET http://localhost:8083/api/judge/selfcheck`.

## 5. Тестовый сценарий и диагностика

`http://localhost:8080/test.html` проверяет аналитику без эталона и диаризации. Во время ответа весь звук микрофона считается голосом кандидата. Этот сценарий не проверяет качество разделения собеседников.

Проверки из корня:

```powershell
mvn test
mvn package
node --test .\stt-service\src\test\js\*.test.cjs
python -m unittest discover -s .\stt-service\whisper -p 'test_*.py' -v
python -m unittest discover -s .\analytics-service\judge -p 'test_*.py' -v
npx --yes @redocly/cli@1.34.2 lint .\docs\api\stt.openapi.yaml .\docs\api\analytics.openapi.yaml
```

| Симптом | Проверить |
|---|---|
| Браузер не открывает страницу | Оба Java-сервиса запущены; STT слушает `127.0.0.1:8080`. |
| Ошибка `/ws/interview-events` 401/403 | `INTERVIEW_EVENTS_TOKEN` задан одинаково в процессах, содержит минимум 32 символа, IntelliJ перезапущена после установки. |
| Ошибка загрузки Vosk | `VOSK_MODEL_PATH` указывает на распакованную модель, а не архив. |
| Эталон не принят (`retry`) | Проверьте передачу системного звука и запишите речь одного кандидата без других громких звуков. |
| Не загружается Nemotron/CAM++ | Проверьте все три пути, архитектуру Windows x64 и совместимость runtime с JDK. |
| В основном режиме отсутствует звук компьютера | В Chrome/Edge выберите весь экран и включите передачу системного звука; при сбое вкладка завершит сессию. |
| Не создаётся голосовой эталон | Во время записи кандидат должен говорить через системный звук компьютера. Проверьте передачу системного звука и устройство вывода звонка: если приложение звонка использует другой выход, выберите этот же выход устройством Windows по умолчанию, затем заново начните демонстрацию экрана со звуком. В консоли STT строка «Эталон кандидата не создан» показывает длительность и RMS принятого аудио; запись эталона в файл не сохраняется. |
| В записи слышно эхо при работе с колонками | Проверьте, включил ли браузер эхоподавление микрофона; уменьшите громкость колонок или используйте наушники. Эхо, возвращённое устройством кандидата, нужно устранять на его стороне. |
| Аналитика использует правила вместо FRIDA | Проверьте `GET /api/judge/status`, URL сервера и backend; правила являются штатным резервным режимом. |
| Whisper не включается | Проверьте `GET http://localhost:8080/api/whisper/status` и `GET http://localhost:9000/`; Whisper необязателен. |
| Сессия остановилась из-за очереди/связи | Найдите `bridge_error` в состоянии и журналы в `runs/integrated/`; проверьте, что analytics не остановлен во время записи. |

## 6. Результаты и форматы

Файлы остаются локально и исключены из Git:

- `runs/diarization/<session>/events.jsonl` — события диаризации и состояние потока;
- `runs/diarization/<session>/segments.json` — интервалы речи и роли;
- `runs/diarization/<session>/summary.json` — итоговая сводка диаризации;
- `runs/integrated/<session>/stt-events.jsonl` — события Vosk;
- `runs/integrated/<session>/timeline-events.jsonl` — изменения ролей, подсказки и метрики интеграции;
- там же события `whisper_postprocess_chunk` показывают каждый фрагмент финального прохода и число распознанных слов; `whisper_postprocess_error` показывает сбой с сохранением ранее подтверждённых уточнений и Vosk;
- `runs/integrated/<session>/timeline.json` — плоский JSON таймлайн слов с источником, моделью, временем и статусом роли;
- `runs/integrated/<session>/transcripts/transcript.md` и `transcript-timed.md` — реплики после завершения интервью; `transcripts.zip` содержит оба файла;
- `runs/integrated/<session>/integration-summary.json` — очереди, задержки, ошибки и пути итоговых файлов;
- `reports/*.xlsx` — отчёт аналитики с итогом по вопросам, критериями оценки и ответами.

JSONL — по одному JSON-объекту на строку; `segments.json` и `timeline.json` — массивы JSON-объектов. Для пояснения полей см. [архитектуру](architecture.md) и [протокол событий](api/interview-events.md).

На страницах основного и тестового интервью после завершения доступно чтение обоих вариантов и скачивание ZIP. Ссылка на сессию сохраняет доступ после перезапуска сервиса, пока каталог результатов существует. При ошибке обработки или записи результат может быть недоступен; проверьте сообщение на странице и журналы.

Если Whisper включён, после остановки интервью и завершения диаризации STT отправляет каждый канал целиком: сначала системный звук, затем микрофон. При повторном сбое неудачный участок обрабатывается по частям, а в крайнем случае используется помеченный резерв Vosk. Роли назначаются словам по финальным интервалам диаризации. Обработка может занять время; кнопка скачивания появится после публикации. Временная запись хранится как сырой, несжатый PCM в `${java.io.tmpdir}/stt-platform/audio`; каталог меняется параметром `GATEWAY_AUDIO_SPOOL_DIR`. Файл имеет суффикс `.pcm.tmp`, содержит два перемежающихся канала PCM16 16 кГц и занимает около 19,2 МБ за пять минут. После публикации без резерва файл удаляется; при использовании резерва или ошибке исходной записи сохраняется для диагностики и может быть удалён вручную.
