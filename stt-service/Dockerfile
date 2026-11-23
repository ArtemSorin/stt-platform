FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src

COPY pom.xml ./
COPY common/pom.xml common/pom.xml
COPY analytics-service/pom.xml analytics-service/pom.xml
COPY stt-service/pom.xml stt-service/pom.xml

COPY common/src common/src
COPY analytics-service/src analytics-service/src
COPY stt-service/src stt-service/src

RUN --mount=type=cache,target=/root/.m2 curl -fL --http1.1 --retry 3 --retry-delay 3 -o /tmp/sherpa-linux.jar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-native-lib-linux-x64-1.13.8.jar \
 && echo '30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba  /tmp/sherpa-linux.jar' | sha256sum -c - \
 && mvn -B install:install-file -Dfile=/tmp/sherpa-linux.jar -DgroupId=com.github.k2-fsa.sherpa-onnx -DartifactId=sherpa-onnx-native-lib-linux-x64 -Dversion=v1.13.8 -Dpackaging=jar

# Сборка проекта
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl stt-service -am package -DskipTests


FROM eclipse-temurin:17-jre AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/stt

RUN curl -fL --http1.1 --retry 5 --retry-delay 3 --connect-timeout 15 -o /tmp/nemo.tar.gz https://github.com/NVIDIA/NeMo-Speech.cpp/releases/download/v0.3.0/nemo-speech-0.3.0-linux-x86_64-cpu.tar.gz \
 && echo '255f12b722ab15b2a38a51cf125d56e3fa97058b528f7e895b39d6a473deb06e  /tmp/nemo.tar.gz' | sha256sum -c - \
 && mkdir /opt/nemo && tar xzf /tmp/nemo.tar.gz -C /opt/nemo --strip-components=1 && rm /tmp/nemo.tar.gz

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