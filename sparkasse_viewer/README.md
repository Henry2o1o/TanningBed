# Sparkasse Kontoübersicht für Linux

Die Anwendung zeigt Sparkasse-Kontoexporte als lokale, schreibgeschützte Übersicht. Sie lädt CSV-Dateien direkt vom ausgewählten Speicherort und kopiert sie nicht in den Anwendungsordner.

## Start

- `Start_Sparkasse.sh` doppelklicken oder im Terminal starten.
- Für einen Eintrag mit eigenem Symbol im Anwendungsmenü `Install_Sparkasse.sh` einmal ausführen.
- In der Anwendung **CSV öffnen** wählen und den Sparkasse-Export auswählen.
- Alternativ kann eine CSV-Datei auf `Start_Sparkasse.sh` gezogen werden.

Es werden keine Kontodaten ins Internet übertragen. Die Anwendung verändert die CSV nicht. Python 3 mit Tkinter wird benötigt.

Das Sparkasse-Symbol ist eigens für diese Anwendung gestaltet und verwendet keine offizielle Sparkassen-Marke.

## Anzeigen

Saldo, Einnahmen und Ausgaben für den gewählten Monat oder den gesamten Export, Einnahmen-/Ausgabenverlauf, Ausgaben nach Kategorie und eine durchsuchbare Buchungsliste. Die Tabelle ist auf 3.000 sichtbare Zeilen begrenzt; die Suche und Auswertung berücksichtigen weiterhin alle passenden Buchungen.
