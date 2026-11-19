package com.customtrainableai

import com.customtrainableai.engine.ProfileStore
import com.customtrainableai.engine.Storage
import com.customtrainableai.model.Templates
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreTest {
    private class MemStorage : Storage {
        val files = HashMap<String, String>()
        override val profilesDir = "p"
        override val backupsDir = "b"
        override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }
        override fun read(path: String) = files[path]
        override fun write(path: String, text: String) { files[path] = text }
        override fun delete(path: String) = files.remove(path) != null
        override fun join(dir: String, name: String) = "$dir/$name"
    }

    @Test
    fun backupReplacesOlderBackupOfSameProfile() {
        val mem = MemStorage()
        var stamp = "19.11.2026_14.30"
        val store = ProfileStore(mem) { stamp }
        val p = Templates.practiceCatch()
        assertEquals("Fangenbackup19.11.2026_14.30.json", store.writeBackup("Fangen", p))
        store.writeBackup("Springen", p)
        stamp = "19.11.2026_14.32"
        store.writeBackup("Fangen", p)
        assertEquals(
            setOf("Fangenbackup19.11.2026_14.32.json", "Springenbackup19.11.2026_14.30.json"),
            mem.list("b").toSet(),
        )
        assertEquals("Fangen", store.listBackups().first().baseName)
        assertEquals(p, store.loadBackup("Fangenbackup19.11.2026_14.32.json"))
    }

    @Test
    fun saveLoadAndSanitize() {
        val store = ProfileStore(MemStorage()) { "01.01.2026_00.00" }
        val p = Templates.practiceJump()
        store.save("Mein: Spiel?", p)
        assertEquals(listOf("Mein_ Spiel_"), store.listProfiles())
        assertEquals(p, store.load("Mein: Spiel?"))
    }
}
