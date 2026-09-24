#!/bin/sh
# Кладёт в desktop/src-tauri/resources то, что приложение для компьютера везёт с собой:
# минимальную Java (jlink) и сервер groupbase.jar. Jar должен быть собран (make build).
#
#   scripts/desktop-resources.sh                          — для этой же системы
#   JMODS=<jdk-целевой-системы>/jmods scripts/desktop-resources.sh — для другой (например, x64 на arm64)
set -eu
cd "$(dirname "$0")/.."

RES=desktop/src-tauri/resources
JAR=target/groupbase.jar
[ -f "$JAR" ] || { echo "Нет $JAR — сначала make build" >&2; exit 1; }

mkdir -p "$RES"
scripts/jre.sh "$RES/runtime"
cp "$JAR" "$RES/groupbase.jar"
echo "Готово: $RES ($(du -sh "$RES" | cut -f1))"
