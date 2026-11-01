# `i18n-check` on a Java 21 runtime, for CI systems other than GitHub Actions.
#
# The build context is the CLI's distribution, built beforehand, so the image holds exactly what the
# release's zip holds and building it needs no Gradle, nor any emulation for arm64: the jars run on
# any JVM, and tree-sitter's jar carries its native library for Linux x86_64 and aarch64.
#
#   ./gradlew :cli:installDist
#   docker build -f cli/Dockerfile -t i18n-check cli/build/install
#   docker run --rm -v "$PWD:/work" i18n-check --code
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.source="https://github.com/IBRAHIMDANS/i18nSupportPlus" \
      org.opencontainers.image.description="i18n-check: the translation checks of I18n Support Plus, from a terminal or a CI job" \
      org.opencontainers.image.licenses="MIT"

COPY i18n-check /opt/i18n-check
ENV PATH="/opt/i18n-check/bin:${PATH}"

# The project to check is mounted here: `i18n-check` checks the current directory by default.
WORKDIR /work
ENTRYPOINT ["i18n-check"]
