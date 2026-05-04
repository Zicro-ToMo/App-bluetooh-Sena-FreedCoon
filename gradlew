#!/bin/sh
# Gradle wrapper script for Unix
exec "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" "$@" 2>/dev/null || \
gradle "$@"
