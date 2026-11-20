package com.anisync.android.cloudstream

import android.content.Context
import android.util.Log
import java.io.File

/** Native runtime shared by settings, Watch and the application lifecycle. */
object CloudStreamRuntime {
    @Volatile private var host: PluginHost? = null
    private val lock = Any()

    fun initialize(context: Context) {
        if (host != null) return
        synchronized(lock) {
            if (host != null) return
            val appContext = context.applicationContext
            val pluginHost = PluginHost(appContext)
            host = pluginHost
            val directory = File(appContext.filesDir, "cloudstream/extensions").apply { mkdirs() }
            val extensions = directory.listFiles()?.filter { it.isFile && it.extension == "cs3" }.orEmpty()
            var loaded = 0
            extensions.forEach { file ->
                if (pluginHost.loadPlugin(file, context)) loaded++
            }
            pluginHost.firePluginsLoaded()
            Log.i("AniSyncCloudStream", "Loaded $loaded of ${extensions.size} CloudStream extensions")
        }
    }

    fun load(file: File, context: Context): Boolean {
        initialize(context)
        return host?.loadPlugin(file, context) == true
    }

    fun providers(): List<Map<String, Any?>> = host?.installedApis().orEmpty()

    fun search(sourceName: String, query: String): List<Map<String, Any?>> =
        host?.search(sourceName, query).orEmpty()

    fun loadAnime(sourceName: String, url: String): Map<String, Any?>? =
        host?.load(sourceName, url)

    fun resolve(sourceName: String, episodeData: String, fast: Boolean = true): Map<String, Any?> =
        host?.loadLinks(sourceName, episodeData, fast)
            ?: mapOf("sources" to emptyList<Any?>(), "subtitles" to emptyList<Any?>())

    fun uninstall(file: File) {
        host?.deleteByFiles(setOf(file.nameWithoutExtension))
        file.delete()
    }
}
