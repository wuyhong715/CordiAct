package com.cordiact.app.plugin

import android.content.Context
import com.cordiact.core.plugin.PluginOwnedData
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Private, per-plugin storage roots. Do not expose these paths directly to untrusted JS plugins. */
class AndroidPluginStorage(context: Context) : PluginOwnedData {
    private val appFiles = context.applicationContext.filesDir.canonicalFile
    private val appCache = context.applicationContext.cacheDir.canonicalFile
    private val appNoBackup = context.applicationContext.noBackupFilesDir.canonicalFile

    private val dataRoot = File(appFiles, "cordiact/plugin-data")
    private val cacheRoot = File(appCache, "cordiact/plugin-cache")
    private val artifactsRoot = File(appNoBackup, "cordiact/plugin-artifacts")

    /** Host-side provisioning API; callers must scope all operations to the requesting plugin id. */
    fun dataDirectoryFor(pluginId: String): File = createPluginDirectory(dataRoot, pluginId)

    /** Host-side cache API; cache contents are disposable and are removed at uninstall. */
    fun cacheDirectoryFor(pluginId: String): File = createPluginDirectory(cacheRoot, pluginId)

    /** Host-side installation API for verified plugin artifacts only. */
    fun artifactsDirectoryFor(pluginId: String): File = createPluginDirectory(artifactsRoot, pluginId)

    override fun eraseAll(id: String) {
        deletePluginDirectory(dataRoot, id)
        deletePluginDirectory(cacheRoot, id)
    }

    fun removeArtifacts(id: String) {
        deletePluginDirectory(artifactsRoot, id)
    }

    private fun createPluginDirectory(root: File, pluginId: String): File {
        validatePluginId(pluginId)
        ensurePrivateRoot(root)
        val destination = File(root, pluginId)
        ensureContained(root.canonicalFile, destination.canonicalFile)
        if (!destination.exists() && !destination.mkdirs()) {
            throw IOException("Could not create plugin directory for '$pluginId'")
        }
        if (!destination.isDirectory) throw IOException("Plugin storage path is not a directory")
        ensureContained(root.canonicalFile, destination.canonicalFile)
        return destination
    }

    private fun deletePluginDirectory(root: File, pluginId: String) {
        validatePluginId(pluginId)
        ensurePrivateRoot(root)
        val destination = File(root, pluginId)
        if (!destination.exists() && !Files.isSymbolicLink(destination.toPath())) return
        deleteTree(destination, root.canonicalFile)
    }

    private fun ensurePrivateRoot(root: File) {
        if (!root.exists() && !root.mkdirs()) throw IOException("Could not create CordiAct plugin storage root")
        if (!root.isDirectory) throw IOException("CordiAct plugin storage root is not a directory")
        val canonical = root.canonicalFile
        val allowedBase = when {
            root.absolutePath.startsWith(appFiles.absolutePath) -> appFiles
            root.absolutePath.startsWith(appCache.absolutePath) -> appCache
            root.absolutePath.startsWith(appNoBackup.absolutePath) -> appNoBackup
            else -> throw IOException("Plugin storage root is outside CordiAct app-private storage")
        }
        ensureContained(allowedBase, canonical)
    }

    private fun deleteTree(file: File, root: File) {
        // Delete a symlink itself; never recurse through its target.
        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath())
            return
        }
        ensureContained(root, file.canonicalFile)
        if (file.isDirectory) {
            val children = file.listFiles() ?: throw IOException("Could not list plugin-owned directory")
            children.forEach { deleteTree(it, root) }
        }
        if (file.exists() && !file.delete()) throw IOException("Could not delete plugin-owned path: ${file.name}")
    }

    private fun ensureContained(root: File, candidate: File) {
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar)
        val candidatePath = candidate.canonicalPath
        if (candidatePath != rootPath && !candidatePath.startsWith(rootPath + File.separator)) {
            throw IOException("Refusing to access a plugin path outside its private storage root")
        }
    }

    private fun validatePluginId(pluginId: String) {
        require(pluginId.matches(PLUGIN_ID_PATTERN)) { "Invalid plugin id '$pluginId'" }
    }

    private companion object {
        val PLUGIN_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{1,127}")
    }
}
