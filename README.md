# CustomTrainableAI

Eine KI, die ein Spiel **selbst durch Belohnung lernt**: Sie sieht einen Bildschirmausschnitt, klickt, hält, wischt
oder drückt Tasten, und du legst fest, wofür es Belohnung oder Strafe gibt. Das Training läuft per Neuroevolution:
Die besten Agenten jeder Generation werden weiterentwickelt.

Läuft auf **Windows** und **Android** (eine gemeinsame Kotlin/Compose-Multiplatform-Codebasis).

## Download

Unter [Releases](../../releases) liegen:

- `CustomTrainableAI-Windows.zip`: entpacken und `CustomTrainableAI.exe` starten. Java muss nicht installiert sein.
- `CustomTrainableAI-Android.apk`: auf dem Handy installieren (Installation aus unbekannten Quellen erlauben).

## Funktionen

| | |
|---|---|
| **Aktionsarten** | 1 Punkt klicken · 2 Punkte · WASD · WASD + Klicks · Joystick · Joystick + Klicks · 1 Punkt halten |
| **Spielbereich** | Rechteck im Editor auf einem Screenshot ziehen und skalieren; Klickpunkte, Joystick-Mitte und -Radius sowie Tasten-Punkte frei verschieben |
| **Mehrere Instanzen** | Beliebig viele Spielbereiche gleichzeitig. Jede Instanz spielt einen anderen Agenten, das Training läuft entsprechend schneller. Windows: Instanzen per Startbefehl öffnen oder offene Fenster übernehmen |
| **Belohnungen** | 7 Regeltypen: Überleben, Pixelfarbe, Bereich ändert sich (z.B. Punktestand), Bereich steht still, Farbanteil (Lebensbalken), Objekt verfolgen (Richtung, Ziel, Zone), Kosten pro Aktion. Dazu Neustart-Schritte nach Game Over |
| **Prompt-Assistent** | Spielziel in eigenen Worten beschreiben, Prompt kopieren, an ChatGPT/Claude geben und die JSON-Antwort einfügen (siehe [PROMPT_BELOHNUNG.md](PROMPT_BELOHNUNG.md)) |
| **KI-Einstellungen** | Neuronen und Schichten (Komplexität), Bildraster, Farbe/Bewegung, Agenten pro Generation, Generationen pro Durchlauf (0 = endlos), **Experimentierfreude**, Eliten, Runden pro Agent, eigene Mutationsrate |
| **Abspielen** | „▶ Abspielen (ohne Lernen)“ spielt das beste Netz, ohne es zu verändern |
| **Fortschritt** | Graph mit bester, durchschnittlicher und schlechtester Belohnung pro Generation; beim Abspielen ein Graph der Rundenergebnisse |
| **Profile** | Speichern, Exportieren und Importieren (`.json`, inkl. aller Gewichte, Einstellungen und Verlauf). Weitertrainieren setzt bei der letzten Generation fort |
| **Backups** | Alle 2 Minuten automatisch, z.B. `Fangenbackup19.11.2026_14.32.json`. Das vorherige Backup desselben Profils wird dabei ersetzt. Wiederherstellen auf der Startseite |

## Schnellstart mit dem Übungsspiel

1. Startseite → **Übungsspiel „Fangen“ öffnen** (ein kleines Spiel, das mitgeliefert wird).
2. **Vorlage: Fangen (2 Punkte)** anlegen. Die Bereiche passen sich automatisch an das Übungsfenster an.
3. Tab **Training** → **▶ Training starten**. Nach 3 Sekunden (Android: 5 s) übernimmt die KI.
4. Den Graph beobachten. Nach etwa 30–60 Generationen fängt sie die meisten Bälle.

Tipp: Mit dem Schalter **Simulator** im Training-Tab lernt die KI das Übungsspiel unsichtbar und sehr schnell,
ohne Maus und Bildschirm. Damit lassen sich Einstellungen gut ausprobieren.

## Eigenes Spiel einrichten

1. **+ Neues Profil**, Tab **Aktionen**: Aktionsart wählen (z.B. „1 Punkt halten“ für ein Flappy-artiges Spiel).
2. Tab **Bereiche** → **Im Editor festlegen (Screenshot in 3 s)**: das Spiel in den Vordergrund holen. Auf dem
   Screenshot das Rechteck über das Spielfeld ziehen und die Punkte (A, B, Joystick, …) an die richtigen Stellen
   schieben. **+ Instanz** fügt weitere Spielbereiche hinzu. Alle Punkte gelten relativ für jede Instanz.
3. Tab **Belohnungen** → **Prompt kopieren**: Spielziel, wofür es Belohnung gibt, wann Game Over ist und wie man
   neu startet eintragen. Optional mit **Bereich-Screenshot speichern** ein Bild erzeugen und mitschicken.
   Die Antwort der Sprach-KI mit **Antwort aus Zwischenablage einfügen** übernehmen. Positionen und Farben im Editor
   prüfen (Pipette).
4. **▶ Training starten**.

### Training stoppen

- **Windows:** **F8** drücken oder die Maus in die **linke obere Bildschirmecke** schieben. Alternativ
  „■ Stopp“ im Statusfenster.
- **Android:** **Lautstärke-leiser-Taste** oder „■ Stopp“ in der schwebenden Blase.

Sicherung (Windows): Wird ein Spielfenster geschlossen, minimiert oder verdeckt, pausiert die Runde und es wird
nicht mehr in diesen Bereich geklickt, bis das Spiel wieder zu sehen ist.

### Eingabe-Methode (Windows)

- **Echte Maus/Tastatur:** funktioniert mit fast allen Spielen, belegt aber Maus und Tastatur. Mehrere Instanzen
  teilen sich die eine Maus, und Tasten gehen an das Fenster mit dem Fokus.
- **Im Hintergrund (Fenster-Nachrichten):** Maus und Tastatur bleiben frei, und mehrere Instanzen laufen echt
  parallel. Das klappt **nicht mit jedem Spiel**: Viele Spiele ignorieren solche Nachrichten komplett, z.B. Unity,
  DirectX/Vollbild, Java und manche Browser-Spiele. Deshalb kurz testen: Bewegt sich im Spiel nichts, „Echte
  Maus/Tastatur“ nehmen. Das eingebaute Übungsspiel bekommt die Eingaben direkt und funktioniert mit beiden
  Methoden.

### Android-Berechtigungen

- **Bedienungshilfe „CustomTrainableAI Steuerung“** (für Tippen, Halten und Wischen): Einstellungen →
  Bedienungshilfen → Installierte Apps. Ab Android 13 ist der Schalter bei Apps, die nicht aus dem Play Store
  stammen, zunächst gesperrt. Dann App-Info → ⋮ → **„Eingeschränkte Einstellungen zulassen“** und erneut versuchen.
- **Bildschirmaufnahme:** wird beim ersten Training pro App-Start abgefragt. „Gesamter Bildschirm“ wählen.
- Auf Android gibt es kein Hintergrund-Klicken. Mehrere Instanzen gehen, wenn sie gleichzeitig auf dem
  Bildschirm sichtbar sind (z.B. Split-Screen).

## Speicherorte

- Windows: `%USERPROFILE%\CustomTrainableAI\profiles` und `…\backups`
- Android: `Android/data/com.customtrainableai/files/profiles` und `…/backups`

## Tipps für gutes Lernen

- Eine eindeutige **Game-Over-Regel** (beendet die Runde, gibt Minus) ist das Wichtigste.
- Fortschritt **laufend** belohnen (Punktestand, Strecke), nicht nur am Ende.
- Mehr **Agenten pro Generation** heißt stabileres Lernen, aber langsamere Generationen. 16–30 ist ein guter Bereich.
- **Experimentierfreude** hoch (0.5–0.8) am Anfang oder wenn das Lernen stagniert, niedrig (0.1–0.3) zum Verfeinern.
- Kleines Netz (z.B. 16 Neuronen) und grobes Raster (8×8 bis 12×12) lernen am schnellsten. Größer nur bei Bedarf.
  Eine Änderung am Netz-Aufbau startet das Training neu.
- Bei zufälligem Spielverlauf **Runden pro Agent** auf 2–3 erhöhen.

## Selbst bauen

Benötigt JDK 17+ und für Android das Android SDK.

```
./gradlew :composeApp:run                    # Windows-App starten
./gradlew :composeApp:desktopTest            # Lern-Tests (KI lernt die Übungsspiele im Simulator)
./gradlew :composeApp:createDistributable    # Windows-Programmordner (build/compose/binaries)
./gradlew :composeApp:assembleRelease        # Android-APK
```

Live-Test auf dem echten Bildschirm (öffnet Übungsfenster, nimmt sie auf und steuert sie im Hintergrund):

```
./gradlew :composeApp:run --args="--selftest catch 2 background 20 selftest-log.txt"
```

Bei jedem Push baut GitHub Actions beide Versionen. Ein Tag `v*` erstellt ein Release mit EXE-Zip und APK.
