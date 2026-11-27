# FRIDA-Decisions helper на CPU. Контекст сборки — корень репозитория.
# FRIDA_BACKEND=onnx (по умолчанию): int8 ONNX без torch, быстрее на CPU.
# FRIDA_BACKEND=torch: PyTorch CPU, эталонный бэкенд; медленнее и образ больше.
FROM python:3.12-slim

ARG FRIDA_BACKEND=onnx
ENV PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/models/hf \
    JUDGE_BACKEND=${FRIDA_BACKEND} \
    JUDGE_MODEL=ai-forever/FRIDA-Decisions \
    JUDGE_DEVICE=cpu

RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# CPU-сборка torch берётся из отдельного индекса, чтобы не тянуть CUDA-библиотеки.
RUN pip install --extra-index-url https://download.pytorch.org/whl/cpu \
    "frida-decisions[${FRIDA_BACKEND}] @ git+https://github.com/ai-forever/FRIDA-Decisions@main"
COPY analytics-service/judge/frida_server.py analytics-service/judge/heuristic_backend.py ./

RUN mkdir -p /models/hf && chown -R 10001:10001 /models /app
USER 10001:10001
EXPOSE 9100

# Порт открывается после загрузки и прогрева модели, поэтому GET / служит проверкой готовности.
HEALTHCHECK --interval=10s --timeout=3s --start-period=600s --retries=5 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:9100/', timeout=2).status == 200 else 1)"]

CMD ["sh", "-c", "exec python frida_server.py --host 0.0.0.0 --port 9100 --backend \"$JUDGE_BACKEND\" --model \"$JUDGE_MODEL\" ${JUDGE_DEVICE:+--device \"$JUDGE_DEVICE\"} ${JUDGE_THREADS:+--threads \"$JUDGE_THREADS\"}"]
