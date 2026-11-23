# Whisper helper: faster-whisper на CPU. Контекст сборки — корень репозитория.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/models/hf \
    WHISPER_BACKEND=faster-whisper \
    WHISPER_MODEL=small \
    WHISPER_DEVICE=cpu \
    WHISPER_COMPUTE_TYPE=int8 \
    WHISPER_CPU_THREADS=2

WORKDIR /app
COPY stt-service/whisper/requirements.txt ./
RUN pip install -r requirements.txt
COPY stt-service/whisper/whisper_server.py ./

# Каталог кэша моделей: при монтировании тома Docker наследует эти права.
RUN mkdir -p /models/hf && chown -R 10001:10001 /models /app
USER 10001:10001
EXPOSE 9000

# Порт открывается после загрузки модели, поэтому GET / одновременно служит проверкой готовности.
HEALTHCHECK --interval=10s --timeout=3s --start-period=600s --retries=5 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:9000/', timeout=2).status == 200 else 1)"]

CMD ["sh", "-c", "exec python whisper_server.py --host 0.0.0.0 --port 9000 --backend \"$WHISPER_BACKEND\" --model \"$WHISPER_MODEL\" --device \"$WHISPER_DEVICE\" --compute-type \"$WHISPER_COMPUTE_TYPE\" --cpu-threads \"$WHISPER_CPU_THREADS\""]
