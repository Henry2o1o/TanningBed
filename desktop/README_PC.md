# Sonnenbank für Linux und Windows

Desktop-Begleitprogramm zur Android-App. Es meldet sich am Anker-SOLIX-Konto an, lädt Systeme und Geräte, liest Cloud- und MQTT-Werte und speichert Diagrammwerte lokal unter `~/.sonnenbank/history.tsv`.

## Start unter Linux

1. Java 17 oder neuer installieren.
2. Das Archiv entpacken.
3. Im entpackten Ordner `Sonnenbank/bin/Sonnenbank` starten.

Unter Windows kann `Sonnenbank/bin/Sonnenbank.bat` gestartet werden. Die Anwendung speichert das Passwort nicht. Anmelde-Tokens werden in den Java-Benutzereinstellungen abgelegt; die Messwert-Historie liegt lokal im Benutzerordner.

Die Desktop-Version zeigt Energiefluss, Batterieladestand, Smart-Meter-Phasen und -Spannungen, myStrom sowie bis zu zwei Smart-Plug-Gen2-Verläufe mit Tag/Monat/Jahr. Das verwendete Anker-SOLIX-Cloud- und MQTT-Protokoll ist inoffiziell und kann sich ändern.
