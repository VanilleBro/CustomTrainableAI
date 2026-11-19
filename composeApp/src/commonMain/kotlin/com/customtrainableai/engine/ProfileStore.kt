package com.customtrainableai.engine

import com.customtrainableai.model.Profile
import com.customtrainableai.model.profileFromJson
import com.customtrainableai.model.toJson

/** Minimaler Dateizugriff, den jede Plattform bereitstellt. */
interface Storage {
    val profilesDir: String
    val backupsDir: String
    fun list(dir: String): List<String>
    fun read(path: String): String?
    /** Schreibt atomar (erst temporäre Datei, dann umbenennen), damit ein Absturz nichts zerstört. */
    fun write(path: String, text: String)
    fun delete(path: String): Boolean
    fun join(dir: String, name: String): String
}

data class BackupInfo(val fileName: String, val baseName: String, val stamp: String)

/**
 * Profile speichern/laden + Backups.
 * Backup-Name: "{Dateiname}backup{dd.MM.yyyy}_{HH.mm}.json". Pro Profil gibt es genau ein Backup:
 * beim nächsten Backup desselben Profils wird das alte ersetzt.
 */
class ProfileStore(private val storage: Storage, private val timestamp: () -> String) {

    fun listProfiles(): List<String> =
        storage.list(storage.profilesDir).filter { it.endsWith(EXT) }.map { it.removeSuffix(EXT) }.sortedBy { it.lowercase() }

    fun load(name: String): Profile {
        val text = storage.read(storage.join(storage.profilesDir, sanitize(name) + EXT)) ?: error("Datei nicht gefunden: $name")
        return profileFromJson(text)
    }

    fun save(name: String, profile: Profile) {
        storage.write(storage.join(storage.profilesDir, sanitize(name) + EXT), profile.toJson())
    }

    fun delete(name: String) {
        storage.delete(storage.join(storage.profilesDir, sanitize(name) + EXT))
    }

    fun exists(name: String) = sanitize(name) + EXT in storage.list(storage.profilesDir)

    fun listBackups(): List<BackupInfo> = storage.list(storage.backupsDir).mapNotNull { parseBackupName(it) }
        .sortedByDescending { sortKey(it.stamp) }

    /** Schreibt das Backup und löscht ältere Backups desselben Profils. Gibt den Dateinamen zurück. */
    fun writeBackup(baseName: String, profile: Profile): String {
        val base = sanitize(baseName)
        val file = backupFileName(base, timestamp())
        storage.write(storage.join(storage.backupsDir, file), profile.toJson())
        for (old in storage.list(storage.backupsDir)) {
            val info = parseBackupName(old) ?: continue
            if (info.baseName == base && old != file) storage.delete(storage.join(storage.backupsDir, old))
        }
        return file
    }

    fun loadBackup(fileName: String): Profile {
        val text = storage.read(storage.join(storage.backupsDir, fileName)) ?: error("Backup nicht gefunden: $fileName")
        return profileFromJson(text)
    }

    fun deleteBackup(fileName: String) {
        storage.delete(storage.join(storage.backupsDir, fileName))
    }

    companion object {
        const val EXT = ".json"
        private val BACKUP_RE = Regex("""^(.*)backup(\d{2}\.\d{2}\.\d{4})_(\d{2}\.\d{2})\.json$""")

        fun backupFileName(base: String, stamp: String) = "${base}backup$stamp$EXT"

        fun parseBackupName(file: String): BackupInfo? {
            val m = BACKUP_RE.matchEntire(file) ?: return null
            return BackupInfo(file, m.groupValues[1], "${m.groupValues[2]}_${m.groupValues[3]}")
        }

        /** "dd.MM.yyyy_HH.mm" -> sortierbar "yyyyMMddHHmm" */
        private fun sortKey(stamp: String): String {
            val (d, t) = stamp.split("_")
            val (dd, mm, yyyy) = d.split(".")
            return yyyy + mm + dd + t.replace(".", "")
        }

        fun sanitize(name: String): String =
            name.trim().replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").ifBlank { "Profil" }.take(80)
    }
}
