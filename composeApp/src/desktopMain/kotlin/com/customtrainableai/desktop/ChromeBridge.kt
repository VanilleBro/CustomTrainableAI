package com.customtrainableai.desktop

import com.customtrainableai.engine.BrowserControl
import com.customtrainableai.engine.ValueProbe
import com.customtrainableai.model.BrowserConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.customtrainableai.model.ScreenPoint
import com.customtrainableai.model.ScreenRect
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private val CdpJson = Json { ignoreUnknownKeys = true }

/** Eine DevTools-Verbindung (WebSocket) zu einem Chrome-Tab oder zum Browser. */
class CdpSession(wsUrl: String, client: HttpClient) {
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val ids = AtomicInteger(1)
    private val sendLock = Any()
    @Volatile var open = true
        private set
    /** Ereignisse ohne id (z.B. Page.screencastFrame). */
    @Volatile var onEvent: ((String, JsonObject) -> Unit)? = null

    private val ws: WebSocket = client.newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .buildAsync(URI(wsUrl), object : WebSocket.Listener {
            private val buf = StringBuilder()
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buf.append(data)
                if (last) {
                    val text = buf.toString()
                    buf.setLength(0)
                    runCatching {
                        val o = CdpJson.parseToJsonElement(text).jsonObject
                        val id = o["id"]?.jsonPrimitive?.intOrNull
                        if (id != null) pending.remove(id)?.complete(o)
                        else o["method"]?.jsonPrimitive?.contentOrNull?.let { m -> onEvent?.invoke(m, o["params"]?.jsonObject ?: JsonObject(emptyMap())) }
                    }
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
                open = false
                pending.values.forEach { it.complete(JsonObject(emptyMap())) }
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                open = false
                pending.values.forEach { it.complete(JsonObject(emptyMap())) }
            }
        }).get(6, java.util.concurrent.TimeUnit.SECONDS)

    private fun send(method: String, params: JsonObject, wantReply: Boolean): CompletableFuture<JsonObject>? {
        if (!open) return null
        val id = ids.getAndIncrement()
        val f = if (wantReply) CompletableFuture<JsonObject>().also { pending[id] = it } else null
        val msg = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        synchronized(sendLock) {
            runCatching { ws.sendText(msg, true).join() }.onFailure { open = false; f?.complete(JsonObject(emptyMap())) }
        }
        return f
    }

    /** Befehl senden, ohne auf die Antwort zu warten (Eingaben). */
    fun fire(method: String, params: JsonObject = JsonObject(emptyMap())) {
        send(method, params, false)
    }

    /** Befehl senden; die Antwort kann später abgewartet werden (Eingaben bei Zeitsteuerung). */
    fun track(method: String, params: JsonObject): CompletableFuture<JsonObject>? = send(method, params, true)

    /** Befehl senden und auf das Ergebnis warten. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 3000): JsonObject? {
        val f = send(method, params, true) ?: return null
        val r = withTimeoutOrNull(timeoutMs) { f.await() } ?: return null
        return r["result"]?.jsonObject
    }

    /** JavaScript im Tab ausführen, Ergebnis als JSON-Wert. */
    suspend fun eval(expression: String, timeoutMs: Long = 3000): JsonElement? {
        val r = call("Runtime.evaluate", buildJsonObject {
            put("expression", expression)
            put("returnByValue", true)
            put("awaitPromise", false)
        }, timeoutMs) ?: return null
        return r["result"]?.jsonObject?.get("value")
    }

    fun close() {
        open = false
        runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "") }
    }
}

/**
 * Chrome für Browser-Spiele: startet ein eigenes Chrome (eigener Profil-Ordner, Fernsteuerung über das
 * DevTools-Protokoll), öffnet mehrere Fenster nebeneinander, findet die Spielfelder und schickt Eingaben
 * direkt an den jeweiligen Tab – unabhängig von Maus und Tastatur-Fokus.
 */
class ChromeBridge(private val baseDir: File, val port: Int = 9333) : BrowserControl, ValueProbe {
    class Tab(val id: String, val session: CdpSession, @Volatile var url: String = "") {
        /** Zeitsteuerung ist in diesem Tab eingerichtet. */
        @Volatile var armed = false
        /** Letztes Bild (JPEG, nur das Spielfeld) und Werte aus dem letzten Zeitschritt. */
        @Volatile var stepJpeg: ByteArray? = null
        @Volatile var stepVals: DoubleArray? = null
        /** Noch nicht bestätigte Eingaben (müssen vor dem nächsten Zeitschritt angekommen sein). */
        val inflight = java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<JsonObject>>()
        /** Sichtbarer Seitenbereich auf dem Bildschirm (Java-Koordinaten). */
        @Volatile var viewport: ScreenRect? = null
        /** CSS-Pixel pro Java-Pixel. */
        @Volatile var cssScale = 1.0
        /** Spielfeld in CSS-Pixeln: x, y, Breite, Höhe. */
        @Volatile var canvas: DoubleArray? = null
        /** Letztes Bild aus dem Chrome-Bild-Stream (JPEG) und die CSS-Breite der Seite. */
        @Volatile var jpeg: ByteArray? = null
        @Volatile var deviceWidth = 0.0
        @Volatile var streaming = false
        var decodedFrom: ByteArray? = null
        var decoded: java.awt.image.BufferedImage? = null
        /** Fertig umgewandeltes Bild (ARGB) des letzten Zeitschritts. */
        var frame: com.customtrainableai.engine.Frame? = null
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build()
    private val tabs = CopyOnWriteArrayList<Tab>()
    private var browserSession: CdpSession? = null
    @Volatile private var lastRefresh = 0L

    /** Spielzeit wird von der KI Schritt für Schritt weitergeschaltet (alle Tabs in einem Fenster). */
    @Volatile var timeControl = true
        private set
    /** Chrome läuft unsichtbar (ohne Fenster). */
    @Volatile var hidden = true
        private set
    /** Gerade läuft ein Training mit Zeitsteuerung. */
    @Volatile var stepping = false
        private set
    @Volatile private var gameUrl = ""
    @Volatile private var stepScripts: List<String> = emptyList()
    @Volatile private var stepExpr = ""
    /** Von der App gestartetes Chrome (wird beim Beenden geschlossen, wenn es unsichtbar läuft). */
    @Volatile private var process: Process? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
        // Bilder nur im Speicher dekodieren (sonst legt ImageIO für jedes Bild eine Cache-Datei auf der Festplatte an)
        javax.imageio.ImageIO.setUseCache(false)
    }

    /** Messwerte: Zeit fürs Dekodieren der Bilder (für Diagnose). */
    val decodeNanos = java.util.concurrent.atomic.AtomicLong()

    /** Unsichtbares Chrome beim Beenden der App mit schließen (sonst liefe es unbemerkt weiter). */
    fun shutdown() {
        val p = process ?: return
        if (!launchedHidden) return
        runCatching { browser()?.fire("Browser.close") }
        runCatching { if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroy() }
        process = null
    }

    @Volatile private var launchedHidden = false

    // ---------------- Chrome finden und starten ----------------

    fun chromePath(): String? {
        fun env(name: String) = System.getenv(name) ?: System.getenv().entries.firstOrNull { it.key.equals(name, true) }?.value
        fun appPath(exe: String): String? {
            for (root in listOf(com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE, com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER)) {
                val v = runCatching {
                    com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(root, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exe", "")
                }.getOrNull()
                if (v != null) return v.trim('"')
            }
            return null
        }
        val chrome = "Google\\Chrome\\Application\\chrome.exe"
        val edge = "Microsoft\\Edge\\Application\\msedge.exe"
        val candidates = listOfNotNull(
            appPath("chrome.exe"),
            env("ProgramFiles")?.let { "$it\\$chrome" },
            env("ProgramFiles(x86)")?.let { "$it\\$chrome" },
            env("LOCALAPPDATA")?.let { "$it\\$chrome" },
            "C:\\Program Files\\$chrome",
            "C:\\Program Files (x86)\\$chrome",
            appPath("msedge.exe"),
            env("ProgramFiles(x86)")?.let { "$it\\$edge" },
            "C:\\Program Files (x86)\\$edge",
        )
        return candidates.firstOrNull { File(it).isFile }
    }

    private fun get(path: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(2)).GET().build()
        http.send(req, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body()
    }.getOrNull()

    fun alive(): Boolean = get("/json/version") != null

    private fun pageTargets(): List<JsonObject> {
        val text = get("/json/list") ?: return emptyList()
        return runCatching { CdpJson.parseToJsonElement(text).jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "page" }
            .filter { val u = it["url"]?.jsonPrimitive?.contentOrNull ?: ""; !u.startsWith("devtools://") && !u.startsWith("chrome") }
    }

    /**
     * Startet Chrome. [url] = null: leeres Fenster (Tabs werden danach geöffnet), sonst App-Fenster mit der Seite.
     * [headless] = unsichtbar, ganz ohne Fenster.
     */
    private fun launch(exe: String, url: String?, headless: Boolean): Process {
        val profile = File(baseDir, "chrome-profil").apply { mkdirs() }
        val args = mutableListOf(
            exe,
            "--remote-debugging-port=$port",
            "--user-data-dir=${profile.absolutePath}",
            "--no-first-run", "--no-default-browser-check",
            // Fenster/Tabs im Hintergrund nicht drosseln, sonst läuft das Spiel langsamer
            "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding", "--disable-background-timer-throttling",
            "--window-size=480,1000",
        )
        // Zeichnen auf der CPU: bei vielen Tabs deutlich schneller (jeder Tab rechnet in seinem eigenen Prozess)
        if (timeControl) args += "--disable-accelerated-2d-canvas"
        if (headless) args += "--headless=new"
        args += if (url != null) "--app=$url" else "about:blank"
        return ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    /** Läuft das verbundene Chrome unsichtbar? */
    private fun runningHeadless(): Boolean = get("/json/version")?.contains("Headless", ignoreCase = true) == true

    /** Verbindet sich mit allen offenen Seiten (z.B. nach Neustart der App). */
    @Synchronized
    private fun syncTabs() {
        val targets = pageTargets()
        val ids = targets.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.toSet()
        tabs.filter { it.id !in ids || !it.session.open }.forEach { it.session.close(); tabs.remove(it) }
        for (t in targets) {
            val id = t["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val url = t["url"]?.jsonPrimitive?.contentOrNull ?: ""
            val known = tabs.firstOrNull { it.id == id }
            if (known != null) { known.url = url; continue }
            val ws = t["webSocketDebuggerUrl"]?.jsonPrimitive?.contentOrNull ?: continue
            runCatching { tabs += Tab(id, CdpSession(ws, http), url) }
        }
    }

    private fun origin(url: String): String = runCatching { URI(url).let { "${it.scheme}://${it.authority}" } }.getOrDefault(url)

    /**
     * Zeitsteuerung in einem Tab einrichten: Das Skript ersetzt requestAnimationFrame, performance.now und die Timer
     * der Seite. Solange die KI nicht steuert, läuft die Seite normal in Echtzeit; beim Training schaltet die KI die
     * Spielzeit Schritt für Schritt weiter – deshalb muss der Tab weder sichtbar noch im Vordergrund sein.
     * [navigateTo] = Seite danach laden; sonst neu laden, falls die Seite noch ohne Zeitsteuerung läuft.
     */
    private suspend fun arm(t: Tab, navigateTo: String? = null) {
        t.session.call("Page.enable")
        t.session.call("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", TIME_JS) })
        t.armed = true
        if (navigateTo != null) {
            t.session.call("Page.navigate", buildJsonObject { put("url", navigateTo) })
        } else if (t.session.eval("typeof window.__ctaiT")?.jsonPrimitive?.contentOrNull != "object") {
            t.session.call("Page.reload")
        }
    }

    private fun browser(): CdpSession? {
        browserSession?.takeIf { it.open }?.let { return it }
        val v = get("/json/version") ?: return null
        val ws = runCatching { CdpJson.parseToJsonElement(v).jsonObject["webSocketDebuggerUrl"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return null
        return runCatching { CdpSession(ws, http) }.getOrNull()?.also { browserSession = it }
    }

    override fun connected(): Int {
        if (tabs.isEmpty() && alive()) syncTabs()
        return tabs.size
    }

    override suspend fun open(cfg: BrowserConfig): String? = withContext(Dispatchers.IO) {
        val url = cfg.url.trim()
        val exe = chromePath() ?: return@withContext "Chrome wurde nicht gefunden (auch kein Edge)."
        if (!url.startsWith("http")) return@withContext "Bitte eine vollständige Adresse eingeben (https://…)."
        stepping = false
        timeControl = cfg.timeControl
        hidden = cfg.hidden
        gameUrl = url
        val n = cfg.windows.coerceIn(1, if (timeControl) MAX_TABS else 12)
        // Läuft Chrome im falschen Modus (sichtbar/unsichtbar), neu starten
        if (alive() && runningHeadless() != hidden) {
            runCatching { browser()?.call("Browser.close") }
            browserSession = null
            var w = 0
            while (alive() && w < 8000) { delay(250); w += 250 }
            tabs.forEach { it.session.close() }
            tabs.clear()
        }
        if (!alive()) {
            process = launch(exe, if (timeControl || hidden) null else url, hidden)
            launchedHidden = hidden
            var waited = 0
            while (!alive() && waited < 15000) { delay(250); waited += 250 }
            if (!alive()) return@withContext "Chrome startet nicht mit Fernsteuerung. Läuft Chrome schon mit demselben Profil?"
            delay(500)
        }
        syncTabs()
        if (timeControl) {
            // Alles in EINEM Fenster: n Tabs mit dem Spiel, übrige Tabs (z.B. leere) schließen
            val b = browser() ?: return@withContext "Keine Verbindung zu Chrome."
            val site = origin(url)
            val game = tabs.filter { origin(it.url) == site }
            val missing = n - game.size
            val created = ArrayList<String>()
            repeat(missing.coerceAtLeast(0)) {
                b.call("Target.createTarget", buildJsonObject { put("url", "about:blank") })
                    ?.get("targetId")?.jsonPrimitive?.contentOrNull?.let { created += it }
            }
            syncTabs()
            for (t in tabs) {
                when {
                    t.id in created -> arm(t, navigateTo = url)
                    origin(t.url) == site -> if (!t.armed) arm(t)
                }
            }
            val keep = (game.take(n).map { it.id } + created).toSet()
            for (t in tabs.filter { it.id !in keep }) b.call("Target.closeTarget", buildJsonObject { put("targetId", t.id) })
            var waited = 0
            while (waited < 10000) {
                syncTabs()
                if (tabs.size <= n && tabs.count { origin(it.url) == site } >= n) break
                delay(300); waited += 300
            }
        } else {
            var have = tabs.size
            repeat((n - have).coerceAtLeast(0)) {
                if (hidden) {
                    browser()?.call("Target.createTarget", buildJsonObject { put("url", url); put("newWindow", true) })
                } else {
                    launch(exe, url, false)
                    delay(700)
                }
            }
            var waited = 0
            while (waited < 10000) {
                syncTabs()
                if (tabs.size >= n) break
                delay(300); waited += 300
            }
            if (!hidden) tile()
        }
        if (tabs.isEmpty()) return@withContext "Keine Chrome-Tabs gefunden."
        null
    }

    /** Chrome schließen (z.B. um den Modus zu wechseln oder Rechenzeit freizugeben). */
    override suspend fun closeAll() = withContext(Dispatchers.IO) {
        stepping = false
        runCatching { browser()?.call("Browser.close") }
        browserSession = null
        tabs.forEach { it.session.close() }
        tabs.clear()
        process = null
    }

    /**
     * Fenster anordnen. Die KI holt das Bild direkt aus Chrome, deshalb dürfen sich die Fenster überlappen:
     * bis zu 4 nebeneinander, weitere versetzt darüber.
     */
    private suspend fun tile() {
        val b = browser() ?: return
        val screen = primaryScreenBounds()
        val scale = screenScale()
        val usableH = screen.height - 48
        val w = minOf(480, screen.width / 4)
        val h = usableH.coerceAtMost(1000)
        val cols = (screen.width / w).coerceAtLeast(1)
        tabs.forEachIndexed { i, t ->
            val win = b.call("Browser.getWindowForTarget", buildJsonObject { put("targetId", t.id) }) ?: return@forEachIndexed
            val windowId = win["windowId"]?.jsonPrimitive?.intOrNull ?: return@forEachIndexed
            b.call("Browser.setWindowBounds", buildJsonObject {
                put("windowId", windowId)
                putJsonObject("bounds") { put("windowState", "normal") }
            })
            b.call("Browser.setWindowBounds", buildJsonObject {
                put("windowId", windowId)
                putJsonObject("bounds") {
                    // CDP erwartet hier Bildschirm-Pixel ohne Windows-Skalierung -> Java-Koordinaten
                    put("left", screen.x + (i % cols) * w + (i / cols) * 28)
                    put("top", screen.y + (i / cols) * 28)
                    put("width", w)
                    put("height", h)
                }
            })
        }
        if (scale > 0) delay(600)
    }

    // ---------------- Lage der Seiten auf dem Bildschirm ----------------

    /** Findet das oberste Fenster mit genau diesem Titel. */
    private fun windowByTitle(title: String): HWND? {
        val u = U32.INSTANCE ?: return null
        var found: HWND? = null
        u.EnumWindows(WNDENUMPROC { h, _ ->
            val buf = CharArray(512)
            val len = u.GetWindowText(h, buf, buf.size)
            if (len > 0 && String(buf, 0, len) == title && u.IsWindowVisible(h)) { found = h; false } else true
        }, null)
        return found
    }

    /** Der Seiteninhalt eines Chrome-Fensters (Kindfenster "Chrome_RenderWidgetHostHWND"). */
    private fun renderWidget(top: HWND): HWND? {
        val u = U32.INSTANCE ?: return null
        var found: HWND? = null
        var bestArea = 0L
        u.EnumChildWindows(top, WNDENUMPROC { h, _ ->
            val cb = CharArray(128)
            val n = u.GetClassName(h, cb, cb.size)
            if (String(cb, 0, n) == "Chrome_RenderWidgetHostHWND" && u.IsWindowVisible(h)) {
                val r = RECT()
                u.GetWindowRect(h, r)
                val a = (r.right - r.left).toLong() * (r.bottom - r.top)
                if (a > bestArea) { bestArea = a; found = h }
            }
            true
        }, null)
        return found
    }

    /** Ermittelt für jeden Tab, wo seine Seite auf dem Bildschirm liegt (über einen eindeutigen Fenstertitel). */
    private suspend fun refreshViewports() {
        lastRefresh = System.currentTimeMillis()
        val u = U32.INSTANCE ?: return
        val scale = screenScale()
        for ((i, t) in tabs.withIndex()) {
            val marker = "CTAI-${i + 1}-${(1000..9999).random()}"
            val old = t.session.eval("(()=>{const o=document.title;document.title='$marker';return o})()")?.jsonPrimitive?.contentOrNull ?: continue
            val innerW = t.session.eval("innerWidth")?.jsonPrimitive?.doubleOrNull ?: 0.0
            var hwnd: HWND? = null
            repeat(15) { if (hwnd == null) { delay(80); hwnd = withContext(Dispatchers.IO) { windowByTitle(marker) } } }
            t.session.eval("document.title=${JsonPrimitive(old)}")
            val top = hwnd ?: continue
            val widget = renderWidget(top) ?: continue
            val r = RECT()
            u.GetWindowRect(widget, r)
            val vp = ScreenRect(
                (r.left / scale).toInt(), (r.top / scale).toInt(),
                ((r.right - r.left) / scale).toInt(), ((r.bottom - r.top) / scale).toInt(),
            )
            t.viewport = vp
            if (vp.w > 0 && innerW > 0) t.cssScale = innerW / vp.w
        }
    }

    /**
     * Spielfelder als "virtuelle" Bereiche: Instanz i = Tab i. Das Bild kommt per Chrome-Bild-Stream,
     * Klicks/Tasten per DevTools – unabhängig davon, wo die Fenster liegen oder ob sie verdeckt sind.
     */
    override suspend fun areas(selector: String): List<ScreenRect> {
        if (connected() == 0) return emptyList()
        val sel = JsonPrimitive(selector.ifBlank { "canvas" }).toString()
        lastSelector = selector
        val result = ArrayList<ScreenRect>()
        for ((i, t) in tabs.withIndex()) {
            val c = canvasRect(t, sel) ?: continue
            t.canvas = c
            if (timeControl) {
                // Erstes Bild direkt aus dem Spielfeld holen (für die Vorschau)
                runCatching { stepTab(t, 0.0, sel, "", setFree = false) }
            } else {
                startStream(t)
            }
            result += ScreenRect(VIRTUAL_X + i * VIRTUAL_STEP, 0, c[2].toInt().coerceAtLeast(8), c[3].toInt().coerceAtLeast(8))
        }
        return result
    }

    @Volatile private var lastSelector = "canvas"

    private suspend fun canvasRect(t: Tab, sel: String): DoubleArray? {
        t.session.eval("(()=>{const e=document.querySelector($sel);if(e)e.scrollIntoView({block:'center',inline:'center'});return !!e})()")
        delay(150)
        val js = "(()=>{const e=document.querySelector($sel);if(!e)return null;const r=e.getBoundingClientRect();return [r.left,r.top,r.width,r.height,innerWidth]})()"
        val arr = (t.session.eval(js) as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: return null
        if (arr.size < 5) return null
        t.deviceWidth = arr[4]
        return doubleArrayOf(arr[0], arr[1], arr[2], arr[3])
    }

    /** Chrome-Bild-Stream starten: Chrome schickt jedes neue Bild der Seite als JPEG. */
    private suspend fun startStream(t: Tab) {
        t.session.onEvent = { method, p ->
            if (method == "Page.screencastFrame") {
                val data = p["data"]?.jsonPrimitive?.contentOrNull
                val sid = p["sessionId"]?.jsonPrimitive?.intOrNull
                if (data != null) t.jpeg = java.util.Base64.getDecoder().decode(data)
                p["metadata"]?.jsonObject?.get("deviceWidth")?.jsonPrimitive?.doubleOrNull?.let { if (it > 0) t.deviceWidth = it }
                if (sid != null) t.session.fire("Page.screencastFrameAck", buildJsonObject { put("sessionId", sid) })
            }
        }
        if (t.streaming) t.session.call("Page.stopScreencast")
        t.session.call("Page.startScreencast", buildJsonObject {
            put("format", "jpeg")
            put("quality", 70)
            put("maxWidth", 480)
            put("maxHeight", 1000)
            put("everyNthFrame", 1)
        })
        t.streaming = true
    }

    /** Aktuelles Bild des Spielfelds einer Instanz (aus dem Bild-Stream), null = noch keins. */
    fun frameFor(area: ScreenRect): com.customtrainableai.engine.Frame? {
        val t = tabFor(area) ?: return null
        val c = t.canvas ?: return null
        if (timeControl) {
            // Bild aus dem Zeitschritt: einmal dekodieren und als fertiges Frame merken
            val bytes = t.stepJpeg ?: return null
            val f = synchronized(t) {
                if (t.decodedFrom !== bytes) {
                    val d0 = System.nanoTime()
                    t.frame = runCatching { decodeRaw(bytes) }.getOrNull()
                    t.decodedFrom = bytes
                    decodeNanos.addAndGet(System.nanoTime() - d0)
                }
                t.frame
            } ?: return null
            return com.customtrainableai.engine.Frame(f.width, f.height, f.argb, area.w.toFloat() / f.width)
        }
        val bytes = t.jpeg ?: return null
        // Nur das Spielfeld dekodieren, in halber Auflösung (reicht für die KI und spart viel Rechenzeit)
        val img = synchronized(t) {
            if (t.decodedFrom !== bytes) {
                t.decoded = runCatching { decodeRegion(bytes, c, t.deviceWidth) }.getOrNull()
                t.decodedFrom = bytes
            }
            t.decoded
        } ?: return null
        val w = img.width
        val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        return com.customtrainableai.engine.Frame(w, h, px, area.w.toFloat() / w)
    }

    /** Diagnose: Umwandlungs-Zeit eines Bildes (ms), einzeln gemessen. */
    fun benchDecode(n: Int): String {
        val bytes = tabs.firstNotNullOfOrNull { it.stepJpeg } ?: return "kein Bild"
        repeat(20) { decodeRaw(bytes) }
        val t0 = System.nanoTime()
        repeat(n) { decodeRaw(bytes) }
        val f = decodeRaw(bytes)
        return "${bytes.size} Bytes, ${f?.width}x${f?.height}: ${"%.3f".format((System.nanoTime() - t0) / 1e6 / n)} ms je Bild"
    }

    /** Rohbild "raw:BxH:base64(RGB)" aus dem Zeitschritt in ARGB umwandeln. */
    private fun decodeRaw(bytes: ByteArray): com.customtrainableai.engine.Frame? {
        // Kopf "raw:" + Breite + "x" + Höhe + ":" lesen
        var i = 4
        var w = 0
        while (i < bytes.size && bytes[i] != 'x'.code.toByte()) { w = w * 10 + (bytes[i] - '0'.code.toByte()); i++ }
        i++
        var h = 0
        while (i < bytes.size && bytes[i] != ':'.code.toByte()) { h = h * 10 + (bytes[i] - '0'.code.toByte()); i++ }
        i++
        if (w <= 0 || h <= 0) return null
        val rgb = java.util.Base64.getDecoder().decode(java.nio.ByteBuffer.wrap(bytes, i, bytes.size - i))
        val n = w * h
        if (rgb.remaining() < n * 3) return null
        val px = IntArray(n)
        for (k in 0 until n) {
            val r = rgb.get().toInt() and 255
            val g = rgb.get().toInt() and 255
            val b = rgb.get().toInt() and 255
            px[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return com.customtrainableai.engine.Frame(w, h, px)
    }

    private fun decodeRegion(bytes: ByteArray, c: DoubleArray, deviceWidth: Double): java.awt.image.BufferedImage? {
        val input = javax.imageio.ImageIO.createImageInputStream(java.io.ByteArrayInputStream(bytes))
        val reader = javax.imageio.ImageIO.getImageReadersByFormatName("jpeg").next()
        try {
            reader.input = input
            val iw = reader.getWidth(0)
            val ih = reader.getHeight(0)
            val k = if (deviceWidth > 0) iw / deviceWidth else 1.0
            val x0 = (c[0] * k).toInt().coerceIn(0, iw - 1)
            val y0 = (c[1] * k).toInt().coerceIn(0, ih - 1)
            val w = (c[2] * k).toInt().coerceIn(1, iw - x0)
            val h = (c[3] * k).toInt().coerceIn(1, ih - y0)
            val param = reader.defaultReadParam
            param.sourceRegion = java.awt.Rectangle(x0, y0, w, h)
            if (w >= 240) param.setSourceSubsampling(2, 2, 0, 0)
            return reader.read(0, param)
        } finally {
            reader.dispose()
            input.close()
        }
    }

    /** Alte Variante: Spielfelder als Bildschirm-Bereiche (Fenster müssen sichtbar sein). */
    override suspend fun screenAreas(selector: String): List<ScreenRect> {
        if (connected() == 0) return emptyList()
        val sel = JsonPrimitive(selector.ifBlank { "canvas" }).toString()
        // Spielfeld in die Mitte scrollen
        for (t in tabs) t.session.eval("(()=>{const e=document.querySelector($sel);if(e)e.scrollIntoView({block:'center',inline:'center'});return !!e})()")
        delay(300)
        refreshViewports()
        val result = ArrayList<Pair<Tab, ScreenRect>>()
        for (t in tabs) {
            val vp = t.viewport ?: continue
            val js = "(()=>{const e=document.querySelector($sel);if(!e)return null;const r=e.getBoundingClientRect();return [r.left,r.top,r.width,r.height]})()"
            val arr = (t.session.eval(js) as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: continue
            if (arr.size < 4) continue
            val k = 1.0 / t.cssScale
            result += t to ScreenRect(
                vp.x + (arr[0] * k).toInt(), vp.y + (arr[1] * k).toInt(),
                (arr[2] * k).toInt(), (arr[3] * k).toInt(),
            )
        }
        return result.map { it.second }
    }

    override suspend fun runAll(script: String): List<String> {
        connected()
        return tabs.map { t -> t.session.eval(script)?.toString() ?: "–" }
    }

    /** Tab, dessen Seite die Mitte des Bereichs enthält. */
    fun tabFor(area: ScreenRect?): Tab? {
        if (area == null) return null
        if (tabs.isEmpty()) connected()
        if (area.x >= VIRTUAL_X) return tabs.getOrNull((area.x - VIRTUAL_X) / VIRTUAL_STEP)
        val cx = area.x + area.w / 2
        val cy = area.y + area.h / 2
        fun match() = tabs.firstOrNull { t -> t.viewport?.let { cx in it.x until it.right && cy in it.y until it.bottom } == true }
        match()?.let { return it }
        return null
    }

    /** Lagen neu bestimmen, falls ein Bereich keinem Tab zugeordnet werden kann (höchstens alle 3 s). */
    suspend fun ensureViewports() {
        if (tabs.isEmpty()) connected()
        if (tabs.any { it.viewport == null } && System.currentTimeMillis() - lastRefresh > 3000) refreshViewports()
    }

    override suspend fun prepare(cfg: BrowserConfig?) {
        if (cfg != null) timeControl = cfg.timeControl
        if (connected() == 0) return
        val sel = JsonPrimitive(lastSelector.ifBlank { "canvas" }).toString()
        if (timeControl) {
            // Zeitsteuerung in allen Tabs sicherstellen (z.B. nach Neustart der App), dann ersten Schritt holen
            var reloaded = false
            for (t in tabs) {
                if (!t.armed || t.session.eval("typeof window.__ctaiT")?.jsonPrimitive?.contentOrNull != "object") {
                    arm(t); reloaded = true
                }
            }
            if (reloaded) delay(4000)
            for (t in tabs) {
                canvasRect(t, sel)?.let { t.canvas = it }
                if (t.streaming) { t.session.call("Page.stopScreencast"); t.streaming = false }
                t.stepJpeg = null
                t.stepVals = null
            }
            stepScripts = emptyList()
            stepExpr = ""
            stepping = true
            advanceAll(0.0)
            return
        }
        // Spielfelder neu vermessen und Bild-Stream (wieder) starten
        for (t in tabs) {
            canvasRect(t, sel)?.let { t.canvas = it }
            if (!t.streaming || t.jpeg == null) startStream(t)
        }
    }

    /** Nach dem Training: Seiten laufen wieder normal in Echtzeit. */
    override suspend fun release() {
        stepping = false
        for (t in tabs) runCatching { t.session.eval("window.__ctaiT&&(window.__ctaiT.free=true)") }
    }

    /** Uhr für die KI: jeder Takt schaltet die Spielzeit in allen Tabs gleichzeitig weiter. */
    override fun stepClock(): com.customtrainableai.engine.Clock = object : com.customtrainableai.engine.Clock {
        private var t = 0.0
        override fun now() = t
        override suspend fun sleep(seconds: Double) {
            if (seconds <= 0.0) { kotlinx.coroutines.yield(); return }
            advanceAll(seconds * 1000.0)
            t += seconds
        }
    }

    /** Spielzeit in allen Tabs um [ms] weiterschalten; danach liegen Bild und Regel-Werte jedes Tabs bereit. */
    suspend fun advanceAll(ms: Double) {
        val sel = JsonPrimitive(lastSelector.ifBlank { "canvas" }).toString()
        val expr = stepExpr
        val list = tabs.filter { it.canvas != null }
        if (list.isEmpty()) { delay((ms).toLong().coerceIn(1, 1000)); return }
        val t0 = System.nanoTime()
        kotlinx.coroutines.coroutineScope {
            list.map { t -> async(Dispatchers.IO) { runCatching { stepTab(t, ms, sel, expr, setFree = true) } } }.awaitAll()
        }
        stepNanos.addAndGet(System.nanoTime() - t0)
        stepCount.incrementAndGet()
    }

    /** Messwerte: Zeit in Zeitschritten (für Diagnose). */
    val stepNanos = java.util.concurrent.atomic.AtomicLong()
    val stepCount = AtomicInteger()

    private suspend fun stepTab(t: Tab, ms: Double, sel: String, expr: String, setFree: Boolean) {
        // Eingaben dieses Takts müssen angekommen sein, bevor die Zeit weiterläuft
        while (true) {
            val f = t.inflight.poll() ?: break
            withTimeoutOrNull(1500) { f.await() }
        }
        val free = if (setFree) "T.free=false;" else ""
        val js = "(()=>{const T=window.__ctaiT;if(!T)return null;${free}T.advance(${"%.3f".format(java.util.Locale.ROOT, ms)});" +
            "return [T.obs($sel)${if (expr.isNotEmpty()) ",$expr" else ""}]})()"
        val arr = t.session.eval(js, 4000) as? JsonArray
        if (arr == null) {
            t.stepJpeg = null
            t.stepVals = null
            return
        }
        val img = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull
        t.stepJpeg = img?.takeIf { it.startsWith("raw:") }?.toByteArray(Charsets.ISO_8859_1)
        t.stepVals = if (expr.isEmpty()) null else DoubleArray(arr.size - 1) { i -> arr[i + 1].jsonPrimitive.doubleOrNull ?: Double.NaN }
    }

    /** Aktuelle Bilder aller Tabs (für die Live-Ansicht in der App). */
    override fun previews(): List<com.customtrainableai.engine.Frame?> = tabs.mapIndexed { i, t ->
        val c = t.canvas ?: return@mapIndexed null
        runCatching { frameFor(ScreenRect(VIRTUAL_X + i * VIRTUAL_STEP, 0, c[2].toInt().coerceAtLeast(8), c[3].toInt().coerceAtLeast(8))) }.getOrNull()
    }

    private fun scriptsExpr(scripts: List<String>) = scripts.joinToString(",") { s ->
        if (s.isBlank()) "NaN" else "(()=>{try{const v=($s);return typeof v==='boolean'?(v?1:0):Number(v)}catch(e){return NaN}})()"
    }

    /** Selektor für prepare() setzen (aus dem Profil). */
    fun useSelector(selector: String) { lastSelector = selector }

    override suspend fun values(area: ScreenRect, scripts: List<String>): DoubleArray? {
        val t = tabFor(area) ?: run { ensureViewports(); tabFor(area) } ?: return null
        if (stepping) {
            // Werte kommen mit jedem Zeitschritt mit (ein Aufruf pro Takt statt zwei)
            if (scripts != stepScripts) {
                stepScripts = scripts
                stepExpr = scriptsExpr(scripts)
            } else {
                t.stepVals?.takeIf { it.size == scripts.size }?.let { return it.copyOf() }
            }
        }
        val parts = scriptsExpr(scripts)
        val arr = t.session.eval("JSON.stringify([$parts])", 1500)?.jsonPrimitive?.contentOrNull ?: return null
        return runCatching {
            CdpJson.parseToJsonElement(arr).jsonArray.map { it.jsonPrimitive.doubleOrNull ?: Double.NaN }.toDoubleArray()
        }.getOrNull()
    }

    // ---------------- Eingaben ----------------

    fun cssPoint(t: Tab, p: ScreenPoint): Pair<Double, Double>? {
        if (p.x >= VIRTUAL_X) {
            val c = t.canvas ?: return null
            val lx = (p.x - VIRTUAL_X) % VIRTUAL_STEP
            return c[0] + lx to c[1] + p.y
        }
        val vp = t.viewport ?: return null
        return (p.x - vp.x) * t.cssScale to (p.y - vp.y) * t.cssScale
    }

    fun mouse(t: Tab, type: String, p: ScreenPoint, pressed: Boolean) {
        val (x, y) = cssPoint(t, p) ?: return
        sendInput(t, "Input.dispatchMouseEvent", buildJsonObject {
            put("type", type)
            put("x", x)
            put("y", y)
            put("button", if (type == "mouseMoved" && !pressed) "none" else "left")
            put("buttons", if (pressed) 1 else 0)
            put("clickCount", if (type == "mouseMoved") 0 else 1)
        })
    }

    fun key(t: Tab, name: String, down: Boolean) {
        val k = KEY_INFO[name.trim().uppercase()] ?: run {
            val n = name.trim().uppercase()
            if (n.length == 1 && (n[0] in 'A'..'Z' || n[0] in '0'..'9')) {
                Triple(n.lowercase(), if (n[0] in 'A'..'Z') "Key$n" else "Digit$n", n[0].code)
            } else return
        }
        val (key, code, vk) = k
        sendInput(t, "Input.dispatchKeyEvent", buildJsonObject {
            put("type", if (down) "keyDown" else "keyUp")
            put("key", key)
            put("code", code)
            put("windowsVirtualKeyCode", vk)
            put("nativeVirtualKeyCode", vk)
            if (down && key.length == 1) put("text", key)
        })
    }

    /** Eingabe an einen Tab; bei Zeitsteuerung wird die Ankunft vor dem nächsten Zeitschritt abgewartet. */
    private fun sendInput(t: Tab, method: String, params: JsonObject) {
        if (stepping) t.session.track(method, params)?.let { t.inflight.add(it) } else t.session.fire(method, params)
    }

    companion object {
        /** Virtuelle Bereiche für Chrome-Instanzen liegen weit außerhalb jedes Bildschirms. */
        const val VIRTUAL_X = com.customtrainableai.model.VIRTUAL_AREA_X
        const val VIRTUAL_STEP = 4000
        /** Höchstzahl Tabs mit Zeitsteuerung. */
        const val MAX_TABS = 64

        /**
         * Zeitsteuerung, wird in jede Seite vor deren eigenen Skripten geladen. Ersetzt requestAnimationFrame,
         * performance.now und setTimeout/setInterval durch eine eigene Uhr:
         * - free = true: die Uhr läuft in Echtzeit (Seite verhält sich normal),
         * - free = false: nur T.advance(ms) schaltet weiter (die KI gibt den Takt vor, auch in versteckten Tabs).
         * T.obs(selektor) liefert das Spielfeld verkleinert als JPEG.
         * Netzwerk-Anfragen der Seite werden während der Zeitsteuerung über alle Tabs hinweg auf höchstens eine
         * alle 2 Sekunden gedrosselt – sonst würden schnell laufende Timer den Server der Seite überlasten.
         */
        /** Breite des Bildes, das die KI pro Zeitschritt bekommt (Pixel). */
        const val OBS_W = 100

        val TIME_JS get() = TIME_JS_SRC.replace("OBS_W", OBS_W.toString())

        private val TIME_JS_SRC = """
(() => {
  if (window.__ctaiT) return;
  const R = { raf: window.requestAnimationFrame.bind(window), st: window.setTimeout.bind(window), si: window.setInterval.bind(window), pnow: performance.now.bind(performance), fetch: window.fetch && window.fetch.bind(window) };
  const T = window.__ctaiT = { v: R.pnow(), free: true, q: new Map(), rid: 0, timers: new Map(), tid: 1e6 };
  performance.now = () => T.v;
  window.requestAnimationFrame = (cb) => { const id = ++T.rid; T.q.set(id, cb); return id; };
  window.cancelAnimationFrame = (id) => { T.q.delete(id); };
  window.setTimeout = (fn, ms, ...a) => { const id = ++T.tid; T.timers.set(id, { at: T.v + Math.max(0, +ms || 0), fn, a, every: 0 }); return id; };
  window.setInterval = (fn, ms, ...a) => { const id = ++T.tid; const e = Math.max(1, +ms || 0); T.timers.set(id, { at: T.v + e, fn, a, every: e }); return id; };
  window.clearTimeout = window.clearInterval = (id) => { T.timers.delete(id); };
  const call = (f, a) => { try { typeof f === 'function' ? f(...a) : (0, eval)(String(f)); } catch (e) { console.error(e); } };
  function timers() {
    for (let n = 0; n < 2000; n++) {
      let best = null, bid = 0;
      for (const [id, t] of T.timers) if (t.at <= T.v && (!best || t.at < best.at)) { best = t; bid = id; }
      if (!best) return;
      if (best.every) best.at += best.every; else T.timers.delete(bid);
      call(best.fn, best.a);
    }
  }
  function frame() { const q = T.q; T.q = new Map(); for (const cb of q.values()) call(cb, [T.v]); }
  T.advance = (ms) => {
    const end = T.v + Math.max(0, ms);
    for (let n = 0; n < 1000; n++) {
      let next = end;
      for (const t of T.timers.values()) if (t.at < next) next = t.at;
      if (next >= end) break;
      T.v = next; timers();
    }
    T.v = end; timers(); frame();
  };
  T.obs = (sel) => {
    const c = document.querySelector(sel); if (!c) return null;
    // Größe nur ab und zu neu messen (erzwingt sonst bei jedem Schritt ein Layout der Seite)
    if (!T.sz || T.sz.c !== c || ++T.sz.n > 200) { const r = c.getBoundingClientRect(); T.sz = { c, n: 0, rw: r.width, rh: r.height }; }
    const k = Math.min(0.5, OBS_W / Math.max(1, T.sz.rw));
    const w = Math.max(8, Math.round(T.sz.rw * k)), h = Math.max(8, Math.round(T.sz.rh * k));
    const o = T.oc || (T.oc = document.createElement('canvas'));
    if (o.width !== w) o.width = w; if (o.height !== h) o.height = h;
    try {
      // Verkleinertes Spielfeld als rohe RGB-Pixel (Base64) – kein JPEG, das spart auf der App-Seite viel Rechenzeit
      const g = o.getContext('2d', { willReadFrequently: true });
      g.imageSmoothingEnabled = true; g.imageSmoothingQuality = 'high';
      g.drawImage(c, 0, 0, w, h);
      const d = g.getImageData(0, 0, w, h).data;
      const n = w * h, rgb = new Uint8Array(n * 3);
      for (let i = 0, j = 0; i < n; i++, j += 4) { rgb[i * 3] = d[j]; rgb[i * 3 + 1] = d[j + 1]; rgb[i * 3 + 2] = d[j + 2]; }
      let bin = '';
      for (let i = 0; i < rgb.length; i += 8192) bin += String.fromCharCode.apply(null, rgb.subarray(i, i + 8192));
      return 'raw:' + w + 'x' + h + ':' + btoa(bin);
    } catch (e) { return null; }
  };
  // Netzwerk drosseln, solange die KI die Zeit steuert (gemeinsam für alle Tabs der Seite)
  const gate = () => {
    if (T.free) return 0;
    const now = Date.now();
    let last = 0; try { last = +localStorage.getItem('__ctaiNet') || 0; } catch (e) {}
    const at = Math.max(now, last + 2000);
    try { localStorage.setItem('__ctaiNet', String(at)); } catch (e) {}
    return at - now;
  };
  if (R.fetch) window.fetch = (...a) => { const w = gate(); return w > 60000 ? Promise.reject(new TypeError('gedrosselt')) : w > 0 ? new Promise((res, rej) => R.st(() => R.fetch(...a).then(res, rej), w)) : R.fetch(...a); };
  const xs = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function (...a) { const w = gate(); if (w > 0) R.st(() => xs.apply(this, a), Math.min(w, 60000)); else xs.apply(this, a); };
  // Solange die KI nicht steuert, läuft die Seite in Echtzeit weiter (auch in Hintergrund-Tabs)
  let last = R.pnow();
  const free = () => { const now = R.pnow(); if (T.free) T.advance(Math.min(100, now - last)); last = now; };
  const loop = () => { free(); R.raf(loop); };
  R.raf(loop);
  R.si(() => { if (document.hidden) free(); }, 50);
})();
"""
        val KEY_INFO = mapOf(
            "SPACE" to Triple(" ", "Space", 32),
            "ENTER" to Triple("Enter", "Enter", 13),
            "ESC" to Triple("Escape", "Escape", 27),
            "TAB" to Triple("Tab", "Tab", 9),
            "UP" to Triple("ArrowUp", "ArrowUp", 38),
            "DOWN" to Triple("ArrowDown", "ArrowDown", 40),
            "LEFT" to Triple("ArrowLeft", "ArrowLeft", 37),
            "RIGHT" to Triple("ArrowRight", "ArrowRight", 39),
        )
    }
}
