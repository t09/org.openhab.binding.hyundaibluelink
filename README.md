# Hyundai BlueLink Binding (openHAB) - unfinished project

Dieses Binding verbindet openHAB direkt mit der Hyundai BlueLink API.  

## Konfiguration
Im UI ein neues Thing `Hyundai Vehicle` über die manuelle Hinzufügen-Funktion anlegen und Email, Passwort, PIN sowie die Marke
eintragen. Nach dem erfolgreichen Anlegen der Account-Bridge startet automatisch ein einmaliger Discovery-Scan, der die verfügbaren
Fahrzeuge meldet. Weitere Scans lassen sich bei Bedarf über den UI-Button „Scan“ manuell auslösen.
Das Binding erledigt Login, Token-Handling, Datenabfrage automatisch.

Der Parameter `refresh` legt fest, in welchem Minutenintervall der Fahrzeugstatus automatisch von der Hyundai-API abgefragt wird.
Der Standardwert beträgt 60 Minuten. Wird `0` eingetragen, ist das zyklische Polling deaktiviert und Aktualisierungen erfolgen ausschließlich
über manuelle `REFRESH`-Kommandos bzw. nach ausgeführten Aktionen.

Der Parameter `language` bestimmt die Sprache, die bei allen Login- und API-Aufrufen an den BlueLink-Dienst übermittelt wird. (`cs`, `da`, `nl`, `en`, `fi`, `fr`, `de`, `it`, `pl`, `hu`, `no`, `sk`, `es`, `sv`).

## Kanäle und Funktionen

| Kanal | Item-Typ | Beschreibung |
|-------|----------|--------------|
| `lockState` | `Switch` | Verriegelt (`LOCKED`/`ON`) bzw. entriegelt (`UNLOCKED`/`OFF`) das Fahrzeug. Bei einem Refresh wird der reale Verriegelungsstatus aktualisiert. |
| `climateControl` | `Switch` | Startet (`ON`) bzw. stoppt (`OFF`) die Remote-Klimatisierung. |
| `status` | `String` | Zusammenfassung zentraler Telemetriedaten (Verriegelung, Laden, Klima, Warnungen inkl. `lowFuelLight`, Türen/Fenster, Batterie, Zeitstempel). |
| `odometer` | `Number:Length` | Aktueller Kilometerstand bzw. Meilenstand abhängig vom Fahrzeug. |
| `batteryLevel` | `Number:Dimensionless` | Aktueller Ladezustand der Hochvoltbatterie in Prozent. |
| `range` | `Number:Length` | Geschätzte verbleibende elektrische Reichweite (gesamt) in Kilometer oder Meilen. |
| `evModeRange` | `Number:Length` | Geschätzte verbleibende rein elektrische Reichweite in Kilometer oder Meilen. |
| `gasModeRange` | `Number:Length` | Geschätzte verbleibende Reichweite mit Verbrennungsmotor in Kilometer oder Meilen. |
| `fuelLevel` | `Number:Dimensionless` | Aktueller Füllstand des Kraftstofftanks in Prozent. |
| `lastUpdated` | `DateTime` | Zeitstempel der letzten Statusaktualisierung laut Hyundai-API. |
| `vin` | `String` | Fahrgestellnummer (VIN) des Fahrzeugs. |
| `location` | `Location` | Letzte bekannte GPS-Position. |

Sobald ein erster Statusabruf erfolgreich war, setzt der Handler die vorgeschlagene Einheit (`Unit`-Feld) beim Verknüpfen neuer
Items automatisch auf Kilometer (`km`) oder Meilen (`mi`) entsprechend der letzten Fahrzeugantwort.

### Statusaktualisierung

Alle Statuskanäle (Verriegelung, Türen/Fenster, Klima, Batterie, Telemetrie, Kilometerstand sowie die Position) werden automatisch im eingestellten
`refresh`-Intervall abgefragt. Nach einem erfolgreichen Kommando (z. B. Lock state oder Start/Stopp/Charge) wird zusätzlich eine asynchrone
Aktualisierung mit kurzer Verzögerung (ca. 10–15 s) ausgelöst, damit die Items den neuen Zustand der offiziellen BlueLink-App zeitnah widerspiegeln
und das Hyundai-Backend genügend Zeit zur Verarbeitung hat. Bei Fehlern wird weiterhin `UNDEF` gesetzt, aber ebenfalls ein verzögertes
Statusupdate eingeplant.

## Telemetriedaten

Alle Statuskanäle greifen auf die offiziellen CCAPI-Endpunkte zu. Fenster-, Türen- und Klimadaten werden aus den verschachtelten Antwortobjekten extrahiert und als verständliche Strings bzw. Schalter bereitgestellt. Das Feld `telemetry` liefert das unveränderte JSON – hilfreich für Debugging oder zur Anbindung weiterer Items.

## Notes

Not tested outsite EU. Working Binding can not be guaranteed

## Fehlerbehebung

