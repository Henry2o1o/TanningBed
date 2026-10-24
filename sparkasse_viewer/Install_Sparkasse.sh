#!/usr/bin/env bash
set -e
APP_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
DESKTOP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$DESKTOP_DIR"
cat > "$DESKTOP_DIR/sparkasse.desktop" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=Sparkasse
Comment=Kontoauszüge lokal und nur lesend ansehen
Exec=python3 "$APP_DIR/sparkasse.py"
Icon=$APP_DIR/sparkasse.png
Terminal=false
Categories=Office;Finance;
StartupNotify=true
EOF
chmod +x "$DESKTOP_DIR/sparkasse.desktop"
echo "Sparkasse ist im Anwendungsmenü eingerichtet."
