# Runtime image. The distribution is built on the host first (./dev up runs `./gradlew installDist`), so the image
# build needs no network access and stays small.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 api
WORKDIR /app
COPY build/install/switchon-api /app
USER api
EXPOSE 8080
ENTRYPOINT ["/app/bin/switchon-api"]
