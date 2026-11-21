package com.anisync.android.cloudstream

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native runtime shared by settings, Watch and the application lifecycle. */
object CloudStreamRuntime {
    @Volatile private var host: PluginHost? = null
    private val lock = Any()
    private val ready = CountDownLatch(1)

    fun initialize(context: Context) {
        if (host != null) return
        synchronized(lock) {
            if (host != null) return
            val appContext = context.applicationContext
            val pluginHost = PluginHost(appContext)
            host = pluginHost
            Thread({
                try {
                    val directory = File(appContext.filesDir, "cloudstream/extensions").apply { mkdirs() }
                    val extensions = directory.listFiles()?.filter { it.isFile && it.extension == "cs3" }.orEmpty()
                    var loaded = 0
                    extensions.forEach { file ->
                        if (runCatching { pluginHost.loadPlugin(file, appContext) }.getOrDefault(false)) loaded++
                    }
                    pluginHost.firePluginsLoaded()
                    val activityLoads = pluginHost.pendingActivityLoads()
                    val activity = context as? Activity
                    if (activityLoads.isNotEmpty() && activity != null) {
                        activity.runOnUiThread {
                            runCatching {
                                activity.startActivity(
                                    Intent(activity, CsPluginLoaderActivity::class.java)
                                        .putExtra(CsPluginLoaderActivity.EXTRA_PATHS, activityLoads.toTypedArray())
                                )
                            }
                        }
                    }
                    Log.i("AniSyncCloudStream", "Loaded $loaded of ${extensions.size} CloudStream extensions")
                } finally {
                    ready.countDown()
                }
            }, "AniSync-CloudStream-Loader").apply { isDaemon = true }.start()
        }
    }

    private fun awaitReady() {
        ready.await(60, TimeUnit.SECONDS)
    }

    fun load(file: File, context: Context): Boolean {
        initialize(context)
        awaitReady()
        return host?.loadPlugin(file, context) == true
    }

    fun providers(): List<Map<String, Any?>> {
        awaitReady()
        return host?.installedApis().orEmpty()
    }

    fun search(sourceName: String, query: String): List<Map<String, Any?>> {
        awaitReady()
        return host?.search(sourceName, query).orEmpty()
    }

    fun loadAnime(sourceName: String, url: String): Map<String, Any?>? {
        awaitReady()
        return host?.load(sourceName, url)
    }

    fun resolve(sourceName: String, episodeData: String, fast: Boolean = true): Map<String, Any?> {
        awaitReady()
        return host?.loadLinks(sourceName, episodeData, fast)
            ?: mapOf("sources" to emptyList<Any?>(), "subtitles" to emptyList<Any?>())
    }

    fun pollLinks(sourceName: String, episodeData: String): Map<String, Any?> {
        awaitReady()
        return host?.polledLinks(sourceName, episodeData).orEmpty()
    }

    fun cancelResolve(sourceName: String, episodeData: String) {
        host?.cancelSession(sourceName, episodeData)
    }

    fun uninstall(file: File) {
        host?.deleteByFiles(setOf(file.nameWithoutExtension))
        file.setWritable(true)
        file.delete()
    }
}
