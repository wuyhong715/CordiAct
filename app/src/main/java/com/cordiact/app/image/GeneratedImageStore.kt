package com.cordiact.app.image

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Durable local display index; the authoritative deliverable lives in the workspace. */
class GeneratedImageStore(context: Context) {
    private val root = File(context.filesDir, "generated-image-previews")
    data class Entry(val id: String, val workspace: String, val session: String, val at: Long, val path: String, val model: String, val preview: File)
    @Synchronized fun record(workspace: String, session: String, id: String, path: String, model: String, bytes: ByteArray) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        check(root.isDirectory || root.mkdirs())
        File(root, "$id.png").writeBytes(bytes)
        val target = File(root, "$id.json")
        val temp = File(root, "$id.tmp")
        temp.writeText(JSONObject().put("id", id).put("workspace", workspace).put("session", session)
            .put("at", System.currentTimeMillis()).put("path", path).put("model", model).toString())
        check(temp.renameTo(target))
    }
    fun list(workspace: String, session: String): List<Entry> = root.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { file ->
        runCatching {
            val o = JSONObject(file.readText())
            if (o.getString("workspace") != workspace || o.getString("session") != session) return@runCatching null
            val id = file.nameWithoutExtension
            val preview = File(root, "$id.png")
            if (!preview.isFile) return@runCatching null
            Entry(id, workspace, session, o.getLong("at"), o.getString("path"), o.getString("model"), preview)
        }.getOrNull()
    }.sortedBy { it.at }
}
