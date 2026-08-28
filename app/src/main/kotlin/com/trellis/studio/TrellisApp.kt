package com.trellis.studio

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Keeps memory in check.
 *
 * Coil defaults to ~25% of the heap for its bitmap cache, and generated
 * artwork is 1024x1024, so a few images alone were a large share of the app's
 * footprint. Capping the cache and releasing it on memory pressure keeps usage
 * far lower without hurting scrolling.
 */
class TrellisApp : Application(), ImageLoaderFactory {

    private var loader: ImageLoader? = null

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.10)          // was ~0.25 by default
                    .build()
            }
            .diskCache {
                // Disk is cheap; keeping bitmaps here instead of RAM is the trade.
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
            .also { loader = it }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Hand the bitmaps back as soon as the system asks for room.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            loader?.memoryCache?.clear()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        loader?.memoryCache?.clear()
    }
}
