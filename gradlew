#!/bin/sh
#
# Gradle launcher (POSIX). The official Gradle wrapper script, trimmed for
# clarity. Reproducible builds need this to come from upstream — but Orange
# is small enough that the slim version below covers our needs without
# vendoring a 250-line script we'd never read.
#
# If a future contributor needs Windows builds, run `./gradlew wrapper`
# inside this repo to regenerate the full wrapper alongside gradlew.bat.

set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "gradle-wrapper.jar missing. Run: gradle wrapper" >&2
    echo "Or download from https://github.com/gradle/gradle/raw/v8.10.2/gradle/wrapper/gradle-wrapper.jar" >&2
    exit 1
fi

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVA_BIN="${JAVA_BIN:-java}"

exec "$JAVA_BIN" -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
