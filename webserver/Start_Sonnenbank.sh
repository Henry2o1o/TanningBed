#!/bin/sh
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP="$HERE/SonnenbankWeb/bin/SonnenbankWeb"
JAVA_CMD=java
if [ -n "${JAVA_HOME:-}" ]; then JAVA_CMD="$JAVA_HOME/bin/java"; fi

if [ ! -x "$(command -v "$JAVA_CMD" 2>/dev/null || printf '%s' "$JAVA_CMD")" ] && ! command -v "$JAVA_CMD" >/dev/null 2>&1; then
    echo "Java 17 oder neuer fehlt. Installiere z. B. OpenJDK 17 und starte dieses Skript erneut."
    echo "Unter Ubuntu/Debian: sudo apt install openjdk-17-jre"
    exit 1
fi

JAVA_VERSION=$("$JAVA_CMD" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')
if [ -z "$JAVA_VERSION" ] || [ "$JAVA_VERSION" -lt 17 ]; then
    echo "Sonnenbank Web benötigt Java 17 oder neuer. Gefundene Version: ${JAVA_VERSION:-unbekannt}"
    exit 1
fi

if [ ! -x "$APP" ]; then
    echo "Der Programmstarter fehlt oder ist nicht ausführbar: $APP"
    echo "Bitte das ZIP vollständig entpacken und Start_Sonnenbank.sh erneut starten."
    exit 1
fi

CLASSPATH="$HERE/SonnenbankWeb/lib/webserver.jar:$HERE/SonnenbankWeb/lib/json-20250517.jar"
exec "$JAVA_CMD" -cp "$CLASSPATH" SonnenbankServerControl "$APP"
