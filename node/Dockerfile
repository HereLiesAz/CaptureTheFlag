# A node: relay, referee, media store. Build from the repository root:
#   docker build -f node/Dockerfile -t ctf-node .
# See docs/NODE.md for running one.

# Built on the newest LTS JDK the Kotlin compiler targets; run on the newest JRE.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :node:installDist

FROM eclipse-temurin:27-jre
# ffmpeg: Vosk hears the challenge in stream audio through it.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/node/build/install/node /opt/node
ENV PORT=7447 DATA_DIR=/data
VOLUME /data
EXPOSE 7447
USER 1000
ENTRYPOINT ["/opt/node/bin/node"]
