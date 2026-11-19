# Prompt-Vorlage: Belohnungen von einer Sprach-KI schreiben lassen

Diese Vorlage erzeugt die App auch selbst: Tab **Belohnungen** → **Prompt kopieren**. Die Felder füllst du dort
aus, die aktuelle Steuerung wird automatisch eingetragen. So geht es per Hand:

1. Den Text unten kopieren und die Teile in `[eckigen Klammern]` durch deine Beschreibung ersetzen. Normale
   Sätze reichen, z.B. *„Belohnung, wenn die Zahl oben rechts steigt und ab der Hälfte der Strecke“*.
2. Am besten einen Screenshot genau vom Spielbereich mitschicken (Editor → **Bereich-Screenshot speichern**).
3. An ChatGPT, Claude o.ä. schicken.
4. Die Antwort in der App mit **Antwort aus Zwischenablage einfügen** übernehmen. Text um das JSON herum stört
   nicht. Danach Positionen und Farben im Editor prüfen (Pipette, Regeln mit „(prüfen)“ im Namen).

---

```text
Du hilfst mir, eine Belohnungs-Konfiguration (JSON) für meine App „CustomTrainableAI“ zu schreiben.
Die App lässt eine KI ein Spiel selbst lernen: Sie sieht nur einen Bildschirmausschnitt (den „Spielbereich“), klickt/drückt Tasten und bekommt Belohnungen nach den Regeln, die du festlegst. Gelernt wird per Evolution: Agenten mit mehr Gesamtbelohnung pro Runde werden weiterentwickelt. Die Regeln müssen also genau das belohnen, was ich als Ziel beschreibe – und nur das, was man auf dem Bildschirm erkennen kann.

## Mein Spiel
- Spiel: [Name/Art des Spiels]
- Spielziel: [Was soll die KI erreichen?]
- Belohnung soll es geben, wenn/wo: [z.B. Punktestand oben rechts steigt, Figur läuft nach rechts, ab der Hälfte des Levels]
- Schlecht / Game Over ist, wenn: [z.B. roter Bildschirm, Text GAME OVER in der Mitte, Lebensbalken leer]
- Neue Runde starten: [z.B. auf „Nochmal“ in der Mitte klicken, Leertaste drücken]
- Steuerung: [z.B. Tippen zum Springen, WASD zum Laufen, Joystick unten links]
- Angehängter Screenshot: zeigt genau den Spielbereich (oben links = x 0, y 0; unten rechts = x 1, y 1). Lies Positionen und Farben daraus ab.

## Koordinaten und Farben
- Alle Positionen sind relativ zum Spielbereich: x und y von 0.0 bis 1.0, (0,0) = oben links, (1,1) = unten rechts.
- Punkt: {"x": 0.5, "y": 0.5}. Bereich: {"x": 0.7, "y": 0.02, "w": 0.28, "h": 0.08} (x/y = obere linke Ecke, w/h = Breite/Höhe).
- Farben als "#RRGGBB". "tolerance" = erlaubte Abweichung pro Farbkanal (0–255, üblich 30–60).

## Regeltypen (Feld "type")
1. "SURVIVAL" – reward pro Sekunde, solange die Runde läuft.
2. "PIXEL_COLOR" – Farbe an einem Punkt. Felder: point, color, tolerance, condition ("MATCH" = Farbe ist da, "NO_MATCH" = Farbe ist weg), timing ("ON_ENTER" = jedes Mal, wenn es eintritt; "ONCE" = einmal pro Runde; "PER_SECOND" = pro Sekunde, solange es gilt), reward, endsEpisode (true = Runde endet, z.B. Game Over).
3. "REGION_CHANGE" – Bereich ändert sich. Ideal für Punktestand/Zähler/Münzanzeige: jede Änderung gibt reward. Felder: region, threshold (Anteil geänderter Pixel, 0.01–0.1), cooldownSec (Mindestabstand), reward, endsEpisode.
4. "REGION_STILL" – Bereich ändert sich "seconds" Sekunden lang nicht (Figur hängt fest, Spiel eingefroren). Felder: region, threshold, seconds, reward (meist negativ), endsEpisode.
5. "COLOR_AMOUNT" – Anteil einer Farbe in einem Bereich (Lebens- oder Fortschrittsbalken). Felder: region, color, tolerance, measure ("CHANGE" = reward pro 10 % Zunahme, Abnahme gibt entsprechend Minus; "LEVEL" = reward × Anteil pro Sekunde), endsEpisode + threshold (Runde endet, wenn Anteil ≤ threshold, z.B. Balken leer).
6. "OBJECT_TRACK" – verfolgt ein Objekt über seine Farbe (z.B. die Spielfigur). Felder: color, tolerance, region (Suchbereich), minPixels, goal:
   - "RIGHT" / "LEFT" / "UP" / "DOWN": reward pro 10 % Strecke in diese Richtung,
   - "TARGET": reward pro 10 % Annäherung an target {x,y} (mit endsEpisode endet die Runde bei Abstand ≤ threshold),
   - "ZONE": Objekt befindet sich in zone {x,y,w,h} (nutzt timing und endsEpisode wie PIXEL_COLOR).
   onlyNewBest: true = nur neue Rekord-Strecke zählt (verhindert Hin-und-Her-Farmen), false = jede Bewegung zählt (+ und −).
7. "ACTION_COST" – reward pro Klick/Tastendruck (negativ, z.B. -0.02, gegen sinnloses Dauerklicken).
Gemeinsame Felder: "name" (kurze Beschreibung), "enabled": true, "delaySec" (Regel erst nach X Sekunden der Runde aktiv, z.B. während eines Ladebildschirms).

## Runden ("episode")
- maxSeconds: maximale Länge einer Runde.
- startDelaySec: Wartezeit nach dem Neustart, bevor die KI loslegt.
- resetSteps: Schritte nach Rundenende, um neu zu starten, z.B. {"type": "CLICK", "point": {"x": 0.5, "y": 0.6}}, {"type": "KEY", "key": "SPACE"}, {"type": "WAIT", "seconds": 1.0}, {"type": "BACK"} (Android-Zurück). Tasten (nur Windows): A–Z, 0–9, SPACE, ENTER, ESC, TAB, UP, DOWN, LEFT, RIGHT, F1–F12.
- resetOnTimeLimit: true = auch nach Ablauf von maxSeconds die resetSteps ausführen.

## Optional: Steuerung ("actions") – nur angeben, wenn sie geändert werden soll
- mode: "CLICK_1" (1 Punkt klicken), "CLICK_2" (2 Punkte), "WASD", "WASD_CLICKS", "JOYSTICK", "JOYSTICK_CLICKS", "HOLD_1" (1 Punkt halten)
- clickPoints: Liste der Klickpunkte A, B, C, D; extraClicks: 1–4 (für *_CLICKS); joystickCenter: Punkt; joystickRadius: Anteil der Bereichsbreite (z.B. 0.08); keyLayout: "WASD" oder "ARROWS".

## Regeln für eine gute Konfiguration
- Es muss eine eindeutige Game-Over-Regel mit "endsEpisode": true und negativer Belohnung (−5 bis −20) geben, sonst merkt die KI nie, dass sie verloren hat.
- Fortschritt laufend belohnen (Punktestand-Änderung, Strecke, Annäherung), nicht nur am Ende.
- Überlebens-Belohnung klein halten, außer Überleben ist das eigentliche Ziel.
- Alle Belohnungen in ähnlicher Größenordnung (etwa −20 … +20).
- Kein Belohnungs-Schlupfloch: Die KI darf nicht mehr Punkte bekommen, indem sie z.B. stehen bleibt oder hin und her läuft.
- Wenn du eine Position oder Farbe nur schätzen kannst: sinnvollen Wert eintragen und im "name" mit „(prüfen)“ markieren – ich korrigiere sie im Editor der App per Pipette.

## Antwortformat
Antworte NUR mit einem einzigen JSON-Objekt, ohne weiteren Text:
{
  "rules": [
    { "type": "REGION_CHANGE", "name": "Punktestand steigt (oben rechts)", "region": { "x": 0.72, "y": 0.02, "w": 0.26, "h": 0.07 }, "threshold": 0.02, "cooldownSec": 0.3, "reward": 2 },
    { "type": "OBJECT_TRACK", "name": "Figur läuft nach rechts (blau, prüfen)", "color": "#2D7FF9", "tolerance": 45, "region": { "x": 0, "y": 0.1, "w": 1, "h": 0.8 }, "goal": "RIGHT", "onlyNewBest": true, "reward": 1 },
    { "type": "PIXEL_COLOR", "name": "Game Over: roter Bildschirm Mitte", "point": { "x": 0.5, "y": 0.45 }, "color": "#C62828", "tolerance": 50, "condition": "MATCH", "timing": "ON_ENTER", "endsEpisode": true, "reward": -10 },
    { "type": "REGION_STILL", "name": "Hängt fest (5 s keine Bewegung)", "region": { "x": 0, "y": 0.1, "w": 1, "h": 0.8 }, "threshold": 0.01, "seconds": 5, "endsEpisode": true, "reward": -3 }
  ],
  "episode": {
    "maxSeconds": 90,
    "startDelaySec": 1.0,
    "resetSteps": [ { "type": "CLICK", "point": { "x": 0.5, "y": 0.62 } }, { "type": "WAIT", "seconds": 1.5 } ],
    "resetOnTimeLimit": false
  }
}
```

## Beispiel: so kann man die Felder ausfüllen

- **Spiel:** Endlos-Läufer, die Figur springt über Hindernisse.
- **Spielziel:** möglichst weit kommen.
- **Belohnung:** Die Meter-Anzeige oben links steigt; jedes übersprungene Hindernis.
- **Game Over:** Der Bildschirm wird grau und in der Mitte erscheint ein roter „Retry“-Knopf.
- **Neustart:** auf „Retry“ in der Mitte klicken, dann 1 Sekunde warten.
- **Steuerung:** Tippen = springen (Aktionsart „1 Punkt klicken“).
