FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
ARG TARGETARCH

COPY pom.xml ./
COPY common/pom.xml common/pom.xml
COPY analytics-service/pom.xml analytics-service/pom.xml
COPY stt-service/pom.xml stt-service/pom.xml
COPY finalization-worker/pom.xml finalization-worker/pom.xml

RUN --mount=type=cache,target=/root/.m2 set -eu; \
    case "$TARGETARCH" in \
      amd64) sherpa_arch=x64; sherpa_sha=30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba; sherpa_profile=sherpa-linux ;; \
      arm64) sherpa_arch=aarch64; sherpa_sha=5123d2e48ae1a7ce82ba89ce153906c651bf418a63db2f7dff82265e6d49c104; sherpa_profile=sherpa-linux-arm64 ;; \
      *) echo "Unsupported Linux architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    sherpa_artifact="sherpa-onnx-native-lib-linux-${sherpa_arch}"; \
    cached="/root/.m2/repository/com/github/k2-fsa/sherpa-onnx/${sherpa_artifact}/v1.13.8/${sherpa_artifact}-v1.13.8.jar"; \
    if test -f "$cached" && echo "$sherpa_sha  $cached" | sha256sum -c -; then :; else \
      curl -fL --http1.1 --retry 3 --retry-delay 3 -o /tmp/sherpa-linux.jar "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/${sherpa_artifact}-1.13.8.jar"; \
      echo "$sherpa_sha  /tmp/sherpa-linux.jar" | sha256sum -c -; \
      mvn -B install:install-file -Dfile=/tmp/sherpa-linux.jar -DgroupId=com.github.k2-fsa.sherpa-onnx -DartifactId="$sherpa_artifact" -Dversion=v1.13.8 -Dpackaging=jar; \
    fi; \
    mvn -B -pl stt-service -am package -DskipTests -P "$sherpa_profile"

COPY common/src common/src
COPY analytics-service/src analytics-service/src
COPY stt-service/src stt-service/src


FROM eclipse-temurin:17-jre AS runtime
ARG TARGETARCH

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates libarchive-tools libatomic1 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/stt

RUN set -eu; \
    case "$TARGETARCH" in \
      amd64) nemo_arch=x86_64; nemo_sha=255f12b722ab15b2a38a51cf125d56e3fa97058b528f7e895b39d6a473deb06e ;; \
      arm64) nemo_arch=aarch64; nemo_sha=56858eaf50d19b05902c043f65d0057352bdc107ae380c41053bfbddfc204bf1 ;; \
      *) echo "Unsupported Linux architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    curl -fL --http1.1 --retry 5 --retry-delay 3 --connect-timeout 15 -o /tmp/nemo.tar.gz "https://github.com/NVIDIA/NeMo-Speech.cpp/releases/download/v0.3.0/nemo-speech-0.3.0-linux-${nemo_arch}-cpu.tar.gz"; \
    echo "$nemo_sha  /tmp/nemo.tar.gz" | sha256sum -c -; \
    mkdir /opt/nemo; \
    bsdtar -xzf /tmp/nemo.tar.gz -C /opt/nemo --strip-components=1; \
    rm /tmp/nemo.tar.gz

# Vosk 0.3.45 JAR не содержит Linux ARM64 native library; добавляем официальный ARM64 runtime отдельно.
RUN if [ "$TARGETARCH" = arm64 ]; then \
      curl -fL --http1.1 --retry 5 --retry-delay 3 --connect-timeout 15 -o /tmp/vosk.zip https://github.com/alphacep/vosk-api/releases/download/v0.3.45/vosk-linux-aarch64-0.3.45.zip; \
      echo '45e95d37755deb07568e79497d7feba8c03aee5a9e071df29961aa023fd94541  /tmp/vosk.zip' | sha256sum -c -; \
      bsdtar -xf /tmp/vosk.zip -C /tmp; \
      install -m 755 /tmp/vosk-linux-aarch64-0.3.45/libvosk.so /usr/local/lib/libvosk.so; \
      ldconfig; \
      rm -rf /tmp/vosk-linux-aarch64-0.3.45 /tmp/vosk.zip; \
    fi

RUN mkdir -p /opt/models \
 && curl -fL --http1.1 --retry 5 --retry-delay 3 --connect-timeout 15 -o /tmp/vosk.zip 'https://huggingface.co/rhasspy/vosk-models/resolve/e7ac2109d134b5f2404ba95389b2fb51916d4cab/ru/vosk-model-small-ru-0.22.zip' \
 && echo '961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4  /tmp/vosk.zip' | sha256sum -c - \
 && unzip -q /tmp/vosk.zip -d /opt/models && rm /tmp/vosk.zip

RUN curl -fL --http1.1 --retry 5 --retry-delay 3 --connect-timeout 15 -o /opt/models/model.gguf 'https://huggingface.co/nvidia/Nemotron-3-Diarization/resolve/f667ed73aee57d40cc39428eb768b4fd87a0a29e/Nemotron-3-Diarization.q8_0.gguf' \
 && echo '08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1  /opt/models/model.gguf' | sha256sum -c -

RUN curl -fL --http1.1 --retry 5 --retry-delay 3 --connect-timeout 15 -o /opt/models/speaker.onnx 'https://huggingface.co/bitsydarel/campplus-onnx/resolve/e7bec0a1949af42884eb7911a3c01ba8cf8584b8/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx' \
 && echo 'aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2  /opt/models/speaker.onnx' | sha256sum -c -

COPY --from=build /src/stt-service/target/stt-service-1.0.0.jar /opt/stt/app.jar

ENV VOSK_MODEL_PATH=/opt/models/vosk-model-small-ru-0.22 \
    NEMOTRON_LIB=/opt/nemo/lib/libnemo_speech_asr_c.so \
    NEMOTRON_HOME=/opt/models \
    LD_LIBRARY_PATH=/opt/nemo/lib

RUN mv /opt/models/speaker.onnx /opt/models/speaker_embedding_model.onnx \
 && mkdir -p /data && chown 10001:10001 /data

USER 10001:10001
ENTRYPOINT ["java", "-Xmx2g", "-jar", "/opt/stt/app.jar"]
