# Задачи: фоновая финализация интервью

## Подготовка

- [x] T001 Зафиксировать границы модулей и добавить каркас `finalization-worker/pom.xml` в корневой `pom.xml`. Обновить сборочный реактор контейнеров.
- [x] T002 Описать внутреннее API задания в `docs/api/analytics.openapi.yaml` и подтверждение снимка в `docs/api/interview-events.md`.

## Основа

- [x] T003 Реализовать Flyway-миграцию и транзакционное хранилище в `analytics-service/src/main/resources/db/migration/` и `analytics-service/src/main/java/ru/sttplatform/analytics/archive/`. Хранить поля «Задание» (`interviewId`, `state`, `attemptCount`, `nextAttemptAt`, `leaseToken`, `leaseUntil`, `errorCode`, `resultManifest`) по [модели данных](data-model.md).
- [x] T004 Проверить миграцию, конкуренцию выдачи, истечение аренды и отказ от поздней попытки в `analytics-service/src/test/java/ru/sttplatform/analytics/archive/`, используя настоящий PostgreSQL.

## US1 · Отчёт без вкладки

- [x] T005 [US1] Добавить тесты снимка, пропусков, отмен, исходной версии банка, старых интервью и старого профиля в `analytics-service/src/test/` и `stt-service/src/test/`.
- [x] T006 [US1] Сохранить «Снимок аналитики» по [модели данных](data-model.md) до подтверждения `prepare_final` в `analytics-service/src/main/java/ru/sttplatform/analytics/live/LiveInterview.java` и `analytics-service/src/main/java/ru/sttplatform/analytics/web/InterviewWebSocketHandler.java`. Проверить `interviewId`, `catalogVersion`, `schemaVersion`, `reportContext` и `attempts`.
- [x] T007 [US1] Закрыть и поставить на доставку «Пакет финализации» по [модели данных](data-model.md) в `stt-service/src/main/java/ru/sttplatform/gateway/session/` и `common/src/main/java/ru/sttplatform/common/archive/`. Проверить `interviewId`, `schemaVersion`, `audio`, `journal`, `replayInputs`, хеши и согласованность границ. Зарегистрировать задание только для полного пакета.
- [x] T008 [US1] Переиспользовать обработку WAV, Whisper, журнал и сборку ответов из `stt-service/src/main/` в `finalization-worker/src/main/` через общий код. Покрыть сборку ответов, двухканальный WAV, тестовый моноканал, неполный журнал и Vosk-резерв в `finalization-worker/src/test/`.
- [x] T009 [US1] Реализовать приём итоговых ответов и оценку со снимком и исходным банком в `analytics-service/src/main/java/ru/sttplatform/analytics/`. Переиспользовать существующий экспорт XLSX.
- [x] T010 [US1] Вывести ожидание, обработку, готовность и ошибку в `stt-service/src/main/java/ru/sttplatform/gateway/api/ArchiveBrowserController.java` и `stt-service/src/main/resources/static/archive-view.js`. Обновить `docs/api/stt.openapi.yaml` и Node-тесты в `stt-service/src/test/js/`.

## US2 · Восстановление

- [x] T011 [US2] Реализовать аренду и повтор с проверкой токена в `analytics-service/src/main/java/ru/sttplatform/analytics/archive/` и `finalization-worker/src/main/`. Проверить падение процесса во время запроса Whisper.
- [x] T012 [US2] Реализовать «Манифест результата» по [модели данных](data-model.md) в `analytics-service/src/main/java/ru/sttplatform/analytics/archive/` и `common/src/main/java/ru/sttplatform/common/archive/`. Закрепить `interviewId`, `transcriptSource` и проверенные `artifacts`. Повторять публикацию тех же байтов. Учесть хранение входов и уборку временных результатов.
- [x] T013 [US2] Автоматизировать остановку обработчика, недоступность S3, потерю подтверждения и повтор публикации. Проверить содержимое XLSX/транскрипций, число попыток, SHA-256 и очистку staging в `scripts/test-finalization-worker.py` и интеграционных тестах аналитики.

## US3 · Запуск и приёмка

- [x] T014 [US3] Добавить `finalization-worker/Dockerfile` и седьмой сервис в `compose.yaml`. Настроить доступ к входам, зависимость от аналитики, внутреннюю сеть, готовность и отсутствие опубликованного порта.
- [x] T015 [US3] Обновить `scripts/start-pilot.ps1`. Сохранить существующий `.env`, учесть Windows-путь к Whisper, ждать готовности и показывать диагностику при сбое. Проверить Compose-конфигурацию и синтаксис скрипта, не трогая работающий пользовательский стенд.
- [x] T016 [US3] Добавить проверки седьмого образа и сценария с перезапуском в `.github/workflows/ci.yml`. Прогнать Maven, Node, Python и OpenAPI; локально проверить контейнер восстановления с реальным S3 и контролируемыми Whisper/аналитикой, включая XLSX с подтверждённым ответом и SHA-256. Ручной двухканальный прогон реального интервью описан в [quickstart](quickstart.md) для проверки перед слиянием.
- [x] T017 [US3] Обновить быстрый запуск и ручную приёмку в `README.md` и `docs/archive-pilot.md`, связать их с [quickstart](quickstart.md). Обновить `docs/architecture.md` и C4-исходник/PNG в `docs/diagrams/`.

## Завершение

- [x] T018 Сверить требования, обновить `docs/project-journal.md` с решениями и результатами, закоммитить, отправить в `refactor/add-fisrt-containerization` и обновить PR в `master`. Добавить в PR сценарий ручной приёмки.

## Порядок и параллельная работа

T001–T004 задают модель и контракты. После этого можно параллельно готовить передачу входов (T005–T007) и обработку записи (T008). US1 завершает сквозной проход, US2 проверяет его при отказах. Образ и подготовку инструкции US3 можно делать параллельно с обработкой, полный прогон зависит от US1 и US2. Минимальная полезная поставка проходит US1, для готовности к слиянию нужны все три сценария.
