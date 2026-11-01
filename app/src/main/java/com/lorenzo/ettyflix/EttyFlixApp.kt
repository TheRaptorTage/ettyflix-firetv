package com.lorenzo.ettyflix

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lorenzo.ettyflix.data.Http

class EttyFlixApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Http.client)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L * 1024 * 1024).build() }
            .crossfade(250)
            .build()

    companion object {
        lateinit var instance: EttyFlixApp
            private set
        val ctx: Context get() = instance
        val prefs: SharedPreferences by lazy { instance.getSharedPreferences("ettyflix", Context.MODE_PRIVATE) }
    }
}
