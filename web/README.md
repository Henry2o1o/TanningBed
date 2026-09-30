# Sonnenbank HTML-Oberfläche

`index.html` ist die Oberfläche der PC-Web-App. Für echte Gerätewerte muss sie über den lokalen Java-Begleitdienst geöffnet werden. Direkt im Browser geöffnet, läuft die Seite nur als Designvorschau mit simulierten Diagrammkurven.

Der Begleitdienst bindet nur an `127.0.0.1`, übernimmt die Anker-SOLIX-Anmeldung, Cloud-Abfragen und den MQTT-Echtzeitkanal. Er speichert das Passwort nicht. Messwerte werden lokal unter `~/.sonnenbank/web-history.jsonl` aufgezeichnet. myStrom wird über seine IP-Adresse im Heimnetz abgefragt.
