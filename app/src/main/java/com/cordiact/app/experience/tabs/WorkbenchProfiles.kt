package com.cordiact.app.experience.tabs

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.cordiact.core.security.SecurityDigests
import com.cordiact.core.workbench.PanelPlacement
import com.cordiact.core.workbench.WorkbenchAccent
import com.cordiact.core.workbench.WorkbenchPanel
import com.cordiact.core.workbench.WorkbenchProfile
import com.cordiact.core.workbench.WorkbenchTheme
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.util.UUID

internal data class WorkbenchProfiles(
    val selectedId: String = CLASSIC,
    val custom: Map<String, WorkbenchProfile> = emptyMap(),
) {
    init {
        require(custom.size <= MAX_CUSTOM && custom.keys.all { uuid.matches(it) })
        require(get(selectedId) != null)
    }

    val selected: WorkbenchProfile get() = checkNotNull(get(selectedId))

    fun get(id: String): WorkbenchProfile? = presets[id] ?: custom[id]

    fun apply(id: String?, profile: WorkbenchProfile): WorkbenchProfiles {
        if (id != null && get(id) == profile) return copy(selectedId = id)
        val updatedId = id?.takeIf { it in custom } ?: UUID.randomUUID().toString()
        return WorkbenchProfiles(updatedId, custom + (updatedId to profile))
    }

    fun remove(id: String): WorkbenchProfiles = copy(
        selectedId = if (id == selectedId && id in custom) CLASSIC else selectedId,
        custom = custom - id,
    )

    companion object {
        const val CLASSIC = "classic"
        const val MAX_CUSTOM = 12
        private val uuid = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        val presets = linkedMapOf(
            CLASSIC to WorkbenchProfile("经典"),
            "developer" to WorkbenchProfile(
                "开发", theme = WorkbenchTheme.SYSTEM, panels = listOf(
                    WorkbenchPanel(TraceTab.id, PanelPlacement.DOCK),
                    WorkbenchPanel(GitTreeTab.id, PanelPlacement.DOCK),
                    WorkbenchPanel(ArtifactsTab.id, PanelPlacement.TAB),
                ),
            ),
        )
    }
}

/** One file per workspace. A failed write leaves the last complete library readable. */
internal class WorkbenchProfileStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "workbench-profiles")

    fun load(workspaceId: String): WorkbenchProfiles = synchronized(lock) { read(file(workspaceId)) }

    fun save(workspaceId: String, previous: WorkbenchProfiles, next: WorkbenchProfiles) = synchronized(lock) {
        val file = file(workspaceId)
        check(read(file) == previous) { "Workbench profiles changed" }
        write(file, next)
    }

    fun restoreDefault(workspaceId: String) = synchronized(lock) {
        write(file(workspaceId), WorkbenchProfiles())
    }

    private fun file(workspaceId: String): AtomicFile =
        AtomicFile(File(directory, "${SecurityDigests.sha256Hex(workspaceId)}.json"))

    private fun read(file: AtomicFile): WorkbenchProfiles {
        val input = try {
            file.openRead()
        } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists()) throw missing
            return WorkbenchProfiles()
        }
        val root = JSONObject(input.use { readWorkbenchText(it, MAX_LIBRARY_BYTES) })
        check(root.getInt("version") == 1)
        val custom = root.getJSONObject("profiles")
        check(custom.length() <= WorkbenchProfiles.MAX_CUSTOM)
        return WorkbenchProfiles(
            selectedId = root.getString("selected"),
            custom = custom.keys().asSequence().associateWith { WorkbenchProfileFile.decode(custom.getString(it)) },
        )
    }

    private fun write(file: AtomicFile, library: WorkbenchProfiles) {
        val json = JSONObject()
            .put("version", 1)
            .put("selected", library.selectedId)
            .put("profiles", JSONObject(library.custom.mapValues { WorkbenchProfileFile.encode(it.value) }))
        val out = file.startWrite()
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
            file.finishWrite(out)
        } catch (failure: IOException) {
            file.failWrite(out)
            throw failure
        }
        check(read(file) == library) { "Workbench profiles could not be saved" }
    }

    private companion object {
        val lock = Any()
        const val MAX_LIBRARY_BYTES = 512 * 1024
    }
}

internal object WorkbenchProfileFile {
    private const val MAX_BYTES = 16 * 1024

    fun read(input: InputStream): WorkbenchProfile = decode(readWorkbenchText(input, MAX_BYTES))

    fun encode(profile: WorkbenchProfile): String = StringWriter().also { text ->
        JsonWriter(text).use { json ->
            json.setIndent("  ")
            json.beginObject()
            json.name("version").value(1L)
            json.name("name").value(profile.name)
            json.name("theme").value(profile.theme.name)
            json.name("accent").value(profile.accent.name)
            json.name("panels").beginArray()
            profile.panels.forEach { panel ->
                json.beginObject()
                json.name("id").value(panel.id)
                json.name("placement").value(panel.placement.name)
                json.endObject()
            }
            json.endArray()
            json.endObject()
        }
    }.toString()

    fun decode(text: String): WorkbenchProfile = JsonReader(StringReader(text)).use { json ->
        json.isLenient = false
        var version: String? = null
        var name: String? = null
        var theme: WorkbenchTheme? = null
        var accent: WorkbenchAccent? = null
        var panels: List<WorkbenchPanel>? = null
        json.readObject { field ->
            when (field) {
                "version" -> {
                    require(json.peek() == JsonToken.NUMBER)
                    version = json.nextString()
                }
                "name" -> name = json.requireString()
                "theme" -> theme = WorkbenchTheme.valueOf(json.requireString())
                "accent" -> accent = WorkbenchAccent.valueOf(json.requireString())
                "panels" -> panels = json.readPanels()
                else -> error("Unknown workbench profile field")
            }
        }
        require(version == "1" && json.peek() == JsonToken.END_DOCUMENT)
        WorkbenchProfile(requireNotNull(name), requireNotNull(theme), requireNotNull(accent), requireNotNull(panels))
    }

    private fun JsonReader.readPanels(): List<WorkbenchPanel> = buildList {
        beginArray()
        while (hasNext()) {
            require(size < WorkbenchProfile.MAX_PANELS)
            var id: String? = null
            var placement: PanelPlacement? = null
            readObject { field ->
                when (field) {
                    "id" -> id = requireString()
                    "placement" -> placement = PanelPlacement.valueOf(requireString())
                    else -> error("Unknown workbench panel field")
                }
            }
            add(WorkbenchPanel(requireNotNull(id), requireNotNull(placement)))
        }
        endArray()
    }

    private fun JsonReader.readObject(readField: (String) -> Unit) {
        val seen = mutableSetOf<String>()
        beginObject()
        while (hasNext()) {
            val field = nextName()
            require(seen.add(field))
            readField(field)
        }
        endObject()
    }

    private fun JsonReader.requireString(): String {
        require(peek() == JsonToken.STRING)
        return nextString()
    }
}

private fun readWorkbenchText(input: InputStream, limit: Int): String {
    val bytes = ByteArray(limit + 1)
    var length = 0
    while (length < bytes.size) {
        val read = input.read(bytes, length, bytes.size - length)
        if (read < 0) break
        check(read > 0)
        length += read
    }
    require(length <= limit) { "Workbench profile file is too large" }
    return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length)).toString()
}
