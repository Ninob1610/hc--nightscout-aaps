# HC → AAPS

Android-App, die Aktivitäten aus **Health Connect** als Behandlungen in **Nightscout** schreibt,
sodass **AAPS 4** (NSClient v3) sie in Verlauf und Grafik übernimmt.

## Was übertragen wird

| Health Connect            | Nightscout-Treatment                     | in AAPS 4                   |
|---------------------------|------------------------------------------|-----------------------------|
| Trainingseinheit          | `Exercise` mit Dauer, Notiz: Art · km · kcal · Ø/max Puls · Schritte (Quelle) | Therapie-Ereignis „Sport“ |
| Schlafsitzung             | `Note` mit Dauer, Notiz: Schlafdauer, Tief/REM/wach | Therapie-Ereignis „Notiz“ |
| Tages-Schritte (optional) | `Note` am Tagesende                      | Therapie-Ereignis „Notiz“   |
| Körpertemperatur ≥ 37,5 °C (optional) | `Note` „Erhöhte Temperatur …“ | Therapie-Ereignis „Notiz“ |
| Periode (optional)        | `Note` mit Dauer „Periode, Tag 1–5“      | Therapie-Ereignis „Notiz“   |

In der Trainingsnotiz stehen zusätzlich die Minuten je Pulszone (Z1–Z5, bezogen auf die eingestellte
maximale Herzfrequenz).

Puls, Distanz, Kalorien und Schritte werden **in die Trainingsnotiz** geschrieben. Als eigene
Datenreihen liest AAPS 4 Puls und Schritte nicht aus Nightscout ein (nachgesehen im Code von
AAPS 4.0.0: `NsIncomingDataProcessor` übernimmt nur Treatments wie Therapie-Ereignisse und temporäre Ziele).

**Blutzucker aus Health Connect wird nie übertragen.** Die App fordert dafür nicht einmal eine
Leseberechtigung an.

## Einrichtung

1. **Nightscout ≥ 15** (API v3). Unter *Admin-Werkzeuge*:
   - **Neue Rolle** `healthconnect` mit der Berechtigung `api:treatments:*` anlegen.
   - **Neues Subjekt** `healthconnect` mit der Rolle `healthconnect` anlegen und dessen Zugriffstoken
     (`healthconnect-1a2b…`) kopieren.

   Die Standardrolle **careportal reicht nicht**: Sie hat nur `api:treatments:create` und Nightscout
   antwortet beim Aktualisieren und Löschen mit HTTP 403. Gebraucht werden `create`, `update` und `delete`.
   Das API_SECRET funktioniert mit API v3 nicht. „Verbindung testen“ zeigt fehlende Rechte einzeln an.
2. App installieren, URL und Token eintragen, **Verbindung testen**.
3. **Berechtigungen** antippen und Training, Schlaf usw. sowie **Hintergrund-Lesen** erlauben
   (ohne Hintergrund-Lesen läuft der Sync nur, wenn die App offen ist).
4. In **AAPS 4**: *Konfiguration → Synchronisierung → NSClient (v3) → Einstellungen → Synchronisierung*:
   **„Therapie-Ereignisse übernehmen“** einschalten.

## Funktionsweise

- Alle 15 Minuten (WorkManager, nur mit Netz; optional nur über WLAN und/oder nur beim Laden) liest die App das Zeitfenster der letzten *n* Tage
  (Standard 7) aus Health Connect.
- Jeder Eintrag bekommt einen stabilen `identifier` (UUID aus der Health-Connect-Record-ID) und wird per
  `PUT /api/v3/treatments/{identifier}` geschrieben. Wiederholte Läufe erzeugen deshalb keine Duplikate.
- Nur geänderte Einträge werden hochgeladen (Inhalts-Hash in `files/sync_state.json`). Trainings werden
  48 h lang neu berechnet, weil Uhren Puls und Distanz oft verspätet liefern.
- In Health Connect gelöschte Einträge werden in Nightscout gelöscht (v3 markiert sie als ungültig,
  AAPS übernimmt das).
- Die Schrittnotiz für den laufenden Tag wird höchstens stündlich aktualisiert.
- Schreiben mehrere Apps dasselbe Training/denselben Schlaf (z. B. Garmin und Samsung Health), wird nur
  die längste Sitzung übertragen (≥ 50 % Überlappung, verschiedene Apps). Abschaltbar; einzelne
  Quell-Apps lassen sich ganz ausschließen.
- Löschst du einen Eintrag in AAPS/Nightscout, antwortet v3 beim nächsten Schreiben mit HTTP 410; die App
  merkt sich das und legt ihn nicht wieder an.
- Vor jedem Lauf werden die Token-Rechte geprüft. Fehlt nur `delete`, wird trotzdem hochgeladen, aber nichts gelöscht.

## Auswertung (Tab „Auswertung“)

Verknüpft Trainings und Schlaf der letzten 30 Tage mit den CGM-Werten aus Nightscout. Die Werte werden nur
gelesen und in der App angezeigt; es wird nichts hochgeladen.

Zeitraum wählbar (14, 30 oder 90 Tage). Diagramme zum Antippen:

- **Kennzahlen:** Nächte im Ziel, Hypo-Nächte, Sportzeit.
- **Zeit im Zielbereich je Nacht:** gestapelte Balken unter 70 / 70–180 / über 180 mg/dl.
- **Nachtprofil:** alle Nächte ab dem Einschlafen übereinandergelegt, Median und mittlere 50 % (wie ein AGP).
- **Nächte mit und ohne Sport** in den 12 h vor dem Einschlafen im Vergleich.
- **Schlafqualität und Glukose:** Tiefschlaf-Anteil gegen Ø Nachtglukose (Streudiagramm), dazu ein Vergleich
  der Nächte mit viel und wenig Tiefschlaf.
- **Uhrzeit des Trainings:** Anteil der Trainings mit Hypo in den 12 h danach, getrennt nach morgens, mittags,
  abends und spät.
- **Je Training und je Nacht** eine kleine Glukosekurve (unterbrochen bei CGM-Lücken), bei Nächten dazu der
  Abend davor: Insulin inkl. SMB, Kohlenhydrate, letzter selbst ausgelöster Bolus, temporäres Ziel.
- **Wann nachts Hypos beginnen:** Histogramm nach Uhrzeit. Hypo = mindestens 15 min durchgehend unter 70 mg/dl.
- **Kompressions-Verdacht:** steiler Abfall (≥ 30 mg/dl in 15 min), schneller Wiederanstieg (≥ 30 mg/dl in
  20 min), höchstens 90 min und keine Kohlenhydrate in der Zeit – typisch für Liegen auf dem Sensor. Solche
  Tiefs werden markiert, nicht ausgeblendet, zählen aber nicht für Vergleiche und Hinweise.
- **Was vor Hypo-Nächten anders war:** Nächte mit echter Hypo gegen die übrigen – Sport, Bolus < 3 h vor dem
  Einschlafen, temporäres Ziel, Insulin und Kohlenhydrate am Abend. Zeigt Häufungen, keine Ursachen.
  Braucht `api:treatments:read` (in `api:treatments:*` enthalten).
- **CSV-Export** (Semikolon, UTF-8 mit BOM, öffnet direkt in Excel/LibreOffice), z. B. für die Sprechstunde.

Die Diagrammfarben (Rot/Blau/Gelb statt Rot/Grün) sind mit einem Palette-Validator geprüft und auch bei
Rot-Grün-Sehschwäche unterscheidbar; jede Grafik hat eine Legende und die Werte zusätzlich als Text.

Dafür braucht das Token zusätzlich `api:entries:read`, in der Rolle also z. B. `api:treatments:* api:entries:read`.

## Benachrichtigungen und Bedienung

- **Hypo-Hinweis nach Training:** nach einem frisch übertragenen Training ab einstellbarer Dauer (Standard
  45 min). Mit `api:entries:read` steht dabei, wie oft ähnliche Trainings bisher zu einer Hypo führten.
  Es ist nur ein Hinweis: Die App setzt nie selbst ein Ziel und ändert nichts in AAPS.
- **Hinweis vor dem Schlafengehen:** etwa 30 min vor der gelernten üblichen Einschlafzeit, aber nur, wenn
  der heutige Abend ein Merkmal hat (Sport ≥ 30 min, Bolus < 3 h vorher), nach dem du in der eigenen
  Vorgeschichte deutlich häufiger eine echte Hypo-Nacht hattest (mindestens 50 % und 20 Prozentpunkte mehr
  als sonst, je mindestens 3 Nächte).
- **Wochenbericht:** sonntags um 19 Uhr – Nächte im Ziel (mit Vorwoche), Hypo-Nächte, Sportzeit.
- **Sync-Fehler:** sofort bei Token- oder Berechtigungsproblemen, bei Netzwerkfehlern ab dem dritten Fehlschlag in Folge.
- **Schnelleinstellungs-Kachel „HC-Sync“:** startet einen Sync sofort.
- Das Nightscout-Token wird mit einem Schlüssel aus dem Android Keystore verschlüsselt gespeichert.

## Bauen

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Unit-Tests: `./gradlew :app:testDebugUnitTest`.

## Mögliche Erweiterungen

- Temporäres Ziel „Activity“ während einer laufenden Trainingseinheit. Bewusst weggelassen: Die meisten
  Apps schreiben Sitzungen erst **nach** dem Ende in Health Connect, und automatisch gesetzte Ziele
  greifen direkt in die Dosierung ein.
- Filter nach Quell-App (z. B. nur Garmin).
