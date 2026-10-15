package com.unrealstar.live

import android.content.Context
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Disk cache for feed video.
 *
 * The feed re-requests the same clip every time a moment scrolls back into
 * view. Without this each pass re-streams the whole file, which is slow and
 * fails outright once the local media host is gone. Cached bytes live in the
 * app's private cache dir, so the OS can reclaim them under pressure.
 *
 * [SimpleCache] is process-wide and holds a SQLite lock on its folder, so it
 * must be created once and shared by every player rather than per instance.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MediaCache {

    private const val TAG = "MediaCache"
    private const val DIR = "media-cache"

    /** Evicted least-recently-used once the cache grows past this. */
    private const val MAX_BYTES = 256L * 1024 * 1024

    /** Large enough that a seek does not force a re-read of the whole file. */
    private const val FRAGMENT_BYTES = 5L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    private fun dir(context: Context) = File(context.applicationContext.cacheDir, DIR)

    private fun requireCache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            val folder = dir(context)
            cache ?: SimpleCache(
                folder,
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { created ->
                cache = created
                Log.i(TAG, "opened cache at ${folder.absolutePath}")
            }
        }

    /**
     * Upstream HTTP wrapped in a cache. Cache errors fall back to the network
     * rather than surfacing as a playback failure.
     */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val resolved = requireCache(context)
        return CacheDataSource.Factory()
            .setCache(resolved)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context.applicationContext))
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(resolved).setFragmentSize(FRAGMENT_BYTES)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Human-readable cache size, walked straight off disk for diagnostics. */
    fun describe(context: Context): String {
        val target = dir(context)
        if (!target.exists()) return "0 B (no cache dir)"
        val bytes = target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}