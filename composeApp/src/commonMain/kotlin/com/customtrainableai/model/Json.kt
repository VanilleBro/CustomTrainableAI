package com.customtrainableai.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64

/** Tolerantes JSON – auch für Antworten von Sprach-KIs (Kommentare, Kommas am Ende, Groß/Klein). */
val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
    allowTrailingComma = true
    allowComments = true
    decodeEnumsCaseInsensitive = true
}

val PrettyJson = Json(AppJson) { prettyPrint = true }

fun Profile.toJson(): String = AppJson.encodeToString(this)
fun profileFromJson(text: String): Profile = AppJson.decodeFromString(Profile.serializer(), text)

fun RewardSetup.toPrettyJson(): String = PrettyJson.encodeToString(this)

/** Ergebnis eines Belohnungs-Imports aus Text (z.B. Antwort von ChatGPT/Claude). */
data class RewardImport(
    val rules: List<RewardRule>?,
    val episode: EpisodeConfig?,
    val actions: ActionConfig?,
    val summary: String,
)

/**
 * Liest eine Belohnungs-Konfiguration aus beliebigem Text: sucht das erste '{' bis zum letzten '}'.
 * Felder, die fehlen, behalten die aktuellen Werte (wird mit [current] zusammengeführt).
 */
fun parseRewardImport(text: String, current: Profile): RewardImport {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    require(start >= 0 && end > start) { "Kein JSON-Objekt gefunden ({ ... })." }
    val root = AppJson.parseToJsonElement(text.substring(start, end + 1)).jsonObject

    val rulesEl = root["rules"] ?: root["rewards"]
    val rules = (rulesEl as? JsonArray)?.map { AppJson.decodeFromJsonElement(RewardRule.serializer(), it) }

    val episode = (root["episode"] as? JsonObject)?.let { merge(current.rewards.episode, it, EpisodeConfig.serializer()) }
    val actions = (root["actions"] as? JsonObject)?.let { merge(current.actions, it, ActionConfig.serializer()) }

    val parts = buildList {
        if (rules != null) add("${rules.size} Regeln")
        if (episode != null) add("Runden-Einstellungen")
        if (actions != null) add("Aktionen (${actions.mode.label})")
    }
    require(parts.isNotEmpty()) { "JSON enthält weder \"rules\", \"episode\" noch \"actions\"." }
    return RewardImport(rules, episode, actions, parts.joinToString(", "))
}

private fun <T> merge(base: T, patch: JsonObject, serializer: kotlinx.serialization.KSerializer<T>): T {
    val baseObj = AppJson.encodeToJsonElement(serializer, base).jsonObject
    val merged = JsonObject(baseObj + patch.filterValues { it !is kotlinx.serialization.json.JsonNull })
    return AppJson.decodeFromJsonElement(serializer, merged as JsonElement)
}

// ---- Gewichte kompakt speichern (Base64 der Float-Bits, little endian) ----

fun encodeWeights(w: FloatArray): String {
    val bytes = ByteArray(w.size * 4)
    for (i in w.indices) {
        val b = w[i].toRawBits()
        bytes[i * 4] = b.toByte()
        bytes[i * 4 + 1] = (b shr 8).toByte()
        bytes[i * 4 + 2] = (b shr 16).toByte()
        bytes[i * 4 + 3] = (b shr 24).toByte()
    }
    return Base64.Default.encode(bytes)
}

fun decodeWeights(s: String): FloatArray? = try {
    val bytes = Base64.Default.decode(s)
    FloatArray(bytes.size / 4) { i ->
        val b = (bytes[i * 4].toInt() and 0xFF) or
            ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
            ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
            ((bytes[i * 4 + 3].toInt() and 0xFF) shl 24)
        Float.fromBits(b)
    }
} catch (e: Exception) {
    null
}
