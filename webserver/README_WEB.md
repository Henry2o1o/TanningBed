# Sonnenbank Web für Linux

## Starten

1. Das ZIP vollständig entpacken.
2. Im entpackten Ordner `Sonnenbank Web Linux.desktop` doppelklicken. Im Browser-Steuerfeld stehen **Server starten**, **Server stoppen** und **Dashboard öffnen** bereit. Alternativ im Terminal `./Start_Sonnenbank.sh` starten.
3. Falls Java fehlt, Java 17 oder neuer installieren. Auf Ubuntu/Debian: `sudo apt install openjdk-17-jre`.
4. **Server starten** öffnet `http://127.0.0.1:8765` automatisch. Das Steuerfeld unter `http://127.0.0.1:8764` bleibt verfügbar, wenn der Webserver gestoppt ist. Beim Schließen des Steuerfelds läuft der Webserver weiter, bis **Server stoppen** gedrückt wird.
5. **Mit Anker verbinden** auswählen und mit dem Anker-SOLIX-Konto anmelden.

Der lokale Begleitdienst zeigt Systeme und Geräte an, fragt Cloudwerte ab und startet den MQTT-Echtzeitkanal. Er speichert das Passwort nicht; die Sitzungstokens liegen in den lokalen Java-Benutzereinstellungen. Messwerthistorie wird in `~/.sonnenbank/web-history.jsonl` gespeichert.

myStrom: In der Karte **Weitere PV · myStrom** die lokale IPv4-Adresse eingeben. Die App liest `http://<IP>/report` nur im Heimnetz.
myStrom wird ungefähr einmal pro Sekunde abgefragt. Seine aktuelle Leistung wird sofort in der Live-Anzeige verwendet; für die dauerhaft gespeicherte Historie wird aus Platzgründen nur alle 10 Sekunden ein Messpunkt abgelegt.

Bluetti AC200 Max: **Bluetooth verbinden** auswählen und das Gerät im Browserdialog anklicken. Dafür muss der PC Bluetooth unterstützen und das Bluetti-Gerät eingeschaltet sowie in Reichweite sein. Chrome oder Edge wird benötigt; der Browser erteilt die Bluetooth-Verbindung direkt.

Smart Meter L1–L3, Phasenleistung und Spannung sowie bis zu zwei Smart-Plug-Gen2-Verläufe erscheinen, sofern Anker diese Geräte und MQTT-Werte für das Konto liefert. Das Anker-Protokoll ist inoffiziell und kann sich ändern. Die Anwendung ist nur am lokalen PC erreichbar.
