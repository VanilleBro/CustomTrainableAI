package com.customtrainableai.desktop

import com.customtrainableai.engine.BrowserControl
import com.customtrainableai.engine.ValueProbe
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
    class Tab(val id: String, val session: CdpSession) {
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
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build()
    private val tabs = CopyOnWriteArrayList<Tab>()
    private var browserSession: CdpSession? = null
    @Volatile private var lastRefresh = 0L

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

    private fun launch(exe: String, url: String) {
        val profile = File(baseDir, "chrome-profil").apply { mkdirs() }
        val args = listOf(
            exe,
            "--remote-debugging-port=$port",
            "--user-data-dir=${profile.absolutePath}",
            "--no-first-run", "--no-default-browser-check",
            // Fenster im Hintergrund nicht drosseln, sonst läuft das Spiel langsamer
            "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding", "--disable-background-timer-throttling",
            "--app=$url",
        )
        ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    /** Verbindet sich mit allen offenen Seiten (z.B. nach Neustart der App). */
    @Synchronized
    private fun syncTabs() {
        val targets = pageTargets()
        val ids = targets.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.toSet()
        tabs.filter { it.id !in ids || !it.session.open }.forEach { it.session.close(); tabs.remove(it) }
        for (t in targets) {
            val id = t["id"]?.jsonPrimitive?.contentOrNull ?: continue
            if (tabs.any { it.id == id }) continue
            val ws = t["webSocketDebuggerUrl"]?.jsonPrimitive?.contentOrNull ?: continue
            runCatching { tabs += Tab(id, CdpSession(ws, http)) }
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

    override suspend fun open(url: String, count: Int): String? = withContext(Dispatchers.IO) {
        val n = count.coerceIn(1, 12)
        val exe = chromePath() ?: return@withContext "Chrome wurde nicht gefunden (auch kein Edge)."
        if (!url.startsWith("http")) return@withContext "Bitte eine vollständige Adresse eingeben (https://…)."
        if (alive()) syncTabs()
        var have = tabs.size
        if (!alive()) {
            launch(exe, url)
            have = 0
            var waited = 0
            while (!alive() && waited < 15000) { delay(250); waited += 250 }
            if (!alive()) return@withContext "Chrome startet nicht mit Fernsteuerung. Läuft Chrome schon mit demselben Profil?"
            delay(800)
            syncTabs()
            have = tabs.size
        }
        repeat((n - have).coerceAtLeast(0)) {
            launch(exe, url)
            delay(700)
        }
        var waited = 0
        while (waited < 10000) {
            syncTabs()
            if (tabs.size >= n) break
            delay(300); waited += 300
        }
        if (tabs.isEmpty()) return@withContext "Keine Chrome-Fenster gefunden."
        tile()
        null
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
            startStream(t)
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

    override suspend fun prepare() {
        if (connected() == 0) return
        // Spielfelder neu vermessen und Bild-Stream (wieder) starten
        val sel = JsonPrimitive(lastSelector.ifBlank { "canvas" }).toString()
        for (t in tabs) {
            canvasRect(t, sel)?.let { t.canvas = it }
            if (!t.streaming || t.jpeg == null) startStream(t)
        }
    }

    /** Selektor für prepare() setzen (aus dem Profil). */
    fun useSelector(selector: String) { lastSelector = selector }

    override suspend fun values(area: ScreenRect, scripts: List<String>): DoubleArray? {
        val t = tabFor(area) ?: run { ensureViewports(); tabFor(area) } ?: return null
        val parts = scripts.joinToString(",") { s ->
            if (s.isBlank()) "NaN" else "(()=>{try{const v=($s);return typeof v==='boolean'?(v?1:0):Number(v)}catch(e){return NaN}})()"
        }
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
        t.session.fire("Input.dispatchMouseEvent", buildJsonObject {
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
        t.session.fire("Input.dispatchKeyEvent", buildJsonObject {
            put("type", if (down) "keyDown" else "keyUp")
            put("key", key)
            put("code", code)
            put("windowsVirtualKeyCode", vk)
            put("nativeVirtualKeyCode", vk)
            if (down && key.length == 1) put("text", key)
        })
    }

    companion object {
        /** Virtuelle Bereiche für Chrome-Instanzen liegen weit außerhalb jedes Bildschirms. */
        const val VIRTUAL_X = com.customtrainableai.model.VIRTUAL_AREA_X
        const val VIRTUAL_STEP = 4000
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
