#!/bin/sh
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP="$HERE/SonnenbankWeb/bin/SonnenbankWeb"
URL="http://127.0.0.1:8765/"

# A second click should reuse the running dashboard instead of binding the
# same local port again.
if command -v curl >/dev/null 2>&1 && curl -fsS --max-time 1 "$URL/api/state" 2>/dev/null | grep -q '"authenticated"'; then
    echo "Sonnenbank Web läuft bereits: $URL"
    if command -v xdg-open >/dev/null 2>&1; then xdg-open "$URL" >/dev/null 2>&1 & fi
    exit 0
fi

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
