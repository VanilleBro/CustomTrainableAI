package com.customtrainableai.model

import com.customtrainableai.engine.PracticeLayout

/** Fertige Profile für das eingebaute Übungsspiel (zum Ausprobieren und als Beispiel). */
object Templates {
    fun blank(name: String = "Neues Profil") = Profile(name = name)

    private val gameOverRule = RewardRule(
        type = RuleType.PIXEL_COLOR,
        name = "Game Over (Statusfeld rot)",
        reward = -5f,
        point = PracticeLayout.STATUS_POINT,
        color = "#EF4444",
        tolerance = 60,
        timing = Timing.ON_ENTER,
        endsEpisode = true,
    )

    private val restartSteps = listOf(ResetStep(StepType.CLICK, PracticeLayout.RESTART_POINT))

    fun practiceCatch(area: ScreenRect = ScreenRect(100, 100, 400, 640)) = Profile(
        name = "Übung Fangen",
        practiceKind = PracticeKind.CATCH,
        instances = listOf(area),
        actions = ActionConfig(
            mode = ActionMode.CLICK_2,
            clickPoints = listOf(
                PracticeLayout.center(PracticeLayout.LEFT_BTN),
                PracticeLayout.center(PracticeLayout.RIGHT_BTN),
                RelPoint(0.5f, 0.5f),
                RelPoint(0.5f, 0.3f),
            ),
            extraClicks = 1,
            joystickCenter = PracticeLayout.JOY_CENTER,
            joystickRadius = PracticeLayout.JOY_RANGE,
            keyPoints = listOf(
                RelPoint(0.5f, 0.3f),
                PracticeLayout.center(PracticeLayout.LEFT_BTN),
                RelPoint(0.5f, 0.35f),
                PracticeLayout.center(PracticeLayout.RIGHT_BTN),
            ),
        ),
        tickRate = 10,
        network = NetworkConfig(hidden = listOf(16), gridW = 7, gridH = 11, motion = true),
        evolution = EvolutionConfig(population = 20, generationsPerRun = 40, experiment = 0.35f),
        rewards = RewardSetup(
            rules = listOf(
                RewardRule(
                    type = RuleType.REGION_CHANGE,
                    name = "Ball gefangen (Punktestand ändert sich)",
                    reward = 5f,
                    region = PracticeLayout.SCORE,
                    threshold = 0.01f,
                    cooldownSec = 0.3f,
                ),
                RewardRule(
                    type = RuleType.REGION_CHANGE,
                    name = "Leben verloren",
                    reward = -3f,
                    region = PracticeLayout.LIVES,
                    threshold = 0.05f,
                    cooldownSec = 0.3f,
                ),
                gameOverRule,
                RewardRule(RuleType.SURVIVAL, "Überleben", reward = 0.2f),
            ),
            episode = EpisodeConfig(maxSeconds = 45f, startDelaySec = 0.15f, resetSteps = restartSteps),
        ),
    )

    fun practiceJump(area: ScreenRect = ScreenRect(100, 100, 400, 640)) = Profile(
        name = "Übung Springen",
        practiceKind = PracticeKind.JUMP,
        instances = listOf(area),
        actions = ActionConfig(
            mode = ActionMode.CLICK_1,
            clickPoints = listOf(PracticeLayout.center(PracticeLayout.JUMP_BTN)) + DEFAULT_CLICK_POINTS.drop(1),
        ),
        tickRate = 10,
        network = NetworkConfig(hidden = listOf(16), gridW = 7, gridH = 11, motion = true),
        evolution = EvolutionConfig(population = 20, generationsPerRun = 40, experiment = 0.35f),
        rewards = RewardSetup(
            rules = listOf(
                RewardRule(RuleType.SURVIVAL, "Überleben", reward = 1f),
                RewardRule(
                    type = RuleType.OBJECT_TRACK,
                    name = "Vogel zur Mitte (orange Figur verfolgen)",
                    reward = 1f,
                    color = "#F97316",
                    tolerance = 50,
                    region = RelRect(0.15f, PracticeLayout.FIELD_TOP, 0.2f, PracticeLayout.FIELD_BOTTOM - PracticeLayout.FIELD_TOP),
                    goal = TrackGoal.TARGET,
                    target = RelPoint(PracticeLayout.BIRD_X, 0.45f),
                    onlyNewBest = false,
                ),
                RewardRule(
                    type = RuleType.REGION_CHANGE,
                    name = "Röhre geschafft (Punktestand ändert sich)",
                    reward = 3f,
                    region = PracticeLayout.SCORE,
                    threshold = 0.01f,
                    cooldownSec = 0.3f,
                ),
                gameOverRule,
            ),
            episode = EpisodeConfig(maxSeconds = 60f, startDelaySec = 0.15f, resetSteps = restartSteps),
        ),
    )

    const val NEON_FLAP_URL = "https://addict-games.netlify.app/#/flap"

    /** Setzt in Neon Flap: kleinste Lücke, höchste Geschwindigkeit, $1 Einsatz. */
    val NEON_FLAP_SETUP = """
(()=>{
  const nameBox=[...document.querySelectorAll('input')].find(e=>e.offsetParent&&!['range','number','checkbox','radio','hidden'].includes(e.type)&&!e.closest('.arcade-box'));
  let acc={};try{acc=JSON.parse(localStorage.getItem('addictgames_v1')||'{}')}catch(e){}
  if(nameBox&&acc.name){location.reload();return 'reload'}
  if(nameBox)return 'Bitte einmal einen Namen eingeben (dazu „unsichtbar“ ausschalten und Chrome öffnen)';
  const setV=(el,v)=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,String(v));el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))};
  const r=[...document.querySelectorAll('input[type=range]')];
  if(r[0])setV(r[0],r[0].max);
  if(r[1])setV(r[1],r[1].max);
  const n=document.querySelector('input[type=number]');
  if(n)setV(n,1);
  const box=document.querySelector('.arcade-box');if(box)box.scrollIntoView({block:'center'});
  return 'Lücke '+(r[0]&&r[0].value)+'/5, Tempo '+(r[1]&&r[1].value)+'/3, Einsatz $'+(n&&n.value);
})()
""".trim()

    /** Neon Flap (addict-games.netlify.app) in Chrome: Flappy-artig, Klick = fliegen, Tore und Crash direkt aus der Seite. */
    fun neonFlap() = Profile(
        name = "Neon Flap (Chrome)",
        instances = listOf(ScreenRect(0, 0, 400, 600)),
        inputMethod = InputMethod.CHROME,
        actions = ActionConfig(
            mode = ActionMode.CLICK_1,
            clickPoints = listOf(RelPoint(0.5f, 0.3f)) + DEFAULT_CLICK_POINTS.drop(1),
        ),
        tickRate = 20,
        network = NetworkConfig(hidden = listOf(16), gridW = 8, gridH = 12, motion = true),
        evolution = EvolutionConfig(population = 48, generationsPerRun = 0, experiment = 0.35f),
        rewards = RewardSetup(
            rules = listOf(
                RewardRule(
                    type = RuleType.BROWSER_JS,
                    name = "Überleben (nur solange geflogen wird)",
                    reward = 0.5f,
                    measure = Measure.LEVEL,
                    script = "(()=>{const o=document.querySelector('.arcade-overlay');return o&&!o.hidden?0:1})()",
                ),
                RewardRule(
                    type = RuleType.OBJECT_TRACK,
                    name = "Schiff auf Höhe der nächsten Lücke (türkise Kanten)",
                    reward = 2f,
                    color = "#D6C8FD",
                    tolerance = 45,
                    region = RelRect(0.19f, 0.06f, 0.16f, 0.94f),
                    goal = TrackGoal.FOLLOW_Y,
                    color2 = "#22D3EE",
                    zone = RelRect(0.2f, 0.06f, 0.6f, 0.94f),
                    target = RelPoint(0.25f, 0.5f),
                    minPixels = 3,
                    onlyNewBest = false,
                ),
                RewardRule(
                    type = RuleType.BROWSER_JS,
                    name = "Tor geschafft (Zähler oben links)",
                    reward = 3f,
                    script = "parseInt(document.querySelector('.arcade-hud .h-l').textContent)||0",
                ),
                RewardRule(
                    type = RuleType.BROWSER_JS,
                    name = "Crash = Game Over",
                    reward = -5f,
                    endsEpisode = true,
                    script = "(()=>{const o=document.querySelector('.arcade-overlay');return !!(o&&!o.hidden&&/Crash/.test(o.textContent))})()",
                ),
                RewardRule(
                    type = RuleType.BROWSER_JS,
                    name = "Spiel läuft seit 2 s nicht (hängt) = Runde vorbei",
                    reward = -5f,
                    endsEpisode = true,
                    delaySec = 0.5f,
                    script = "(()=>{const o=document.querySelector('.arcade-overlay');const run=!(o&&!o.hidden);const t=performance.now();if(run||!window.__ctaiRun)window.__ctaiRun=t;return !run&&t-window.__ctaiRun>2000})()",
                ),
            ),
            episode = EpisodeConfig(
                maxSeconds = 90f,
                // Kurz halten: das erste Tor kommt nach ~1 s, die KI soll möglichst früh steuern
                startDelaySec = 0.05f,
                resetSteps = listOf(ResetStep(StepType.KEY, key = "SPACE"), ResetStep(StepType.WAIT, seconds = 0.05f)),
                resetAtStart = true,
            ),
        ),
        browser = BrowserConfig(url = NEON_FLAP_URL, windows = 24, selector = ".arcade-box canvas", setupScript = NEON_FLAP_SETUP),
    )

    /** Die Vorlage an mehrere Bereiche anpassen. */
    fun Profile.withAreas(areas: List<ScreenRect>) = if (areas.isEmpty()) this else copy(instances = areas)
}
