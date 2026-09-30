#!/bin/sh
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP="$HERE/SonnenbankWeb/bin/SonnenbankWeb"

if ! command -v java >/dev/null 2>&1; then
    echo "Java 17 oder neuer fehlt. Installiere z. B. OpenJDK 17 und starte dieses Skript erneut."
    echo "Unter Ubuntu/Debian: sudo apt install openjdk-17-jre"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')
if [ -z "$JAVA_VERSION" ] || [ "$JAVA_VERSION" -lt 17 ]; then
    echo "Sonnenbank Web benötigt Java 17 oder neuer. Gefundene Version: ${JAVA_VERSION:-unbekannt}"
    exit 1
fi

if [ ! -x "$APP" ]; then
    echo "Der Programmstarter fehlt oder ist nicht ausführbar: $APP"
    echo "Bitte das ZIP vollständig entpacken und Start_Sonnenbank.sh erneut starten."
    exit 1
fi

exec "$APP"
