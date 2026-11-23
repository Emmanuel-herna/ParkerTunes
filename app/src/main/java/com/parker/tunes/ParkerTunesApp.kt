package com.parker.tunes

import android.app.Application
import com.parker.tunes.data.AddonMusicApi
import com.parker.tunes.data.LibraryDatabase
import com.parker.tunes.data.MusicApi
import com.parker.tunes.data.home.YtMusicHomeApi
import com.parker.tunes.data.SongDownloadManager
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

class ParkerTunesApp : Application() {
    lateinit var musicApi: MusicApi
        private set
    val database by lazy { LibraryDatabase.get(this) }
    val homeApi by lazy { YtMusicHomeApi() }
    val downloads by lazy { SongDownloadManager(this, database.libraryDao(), musicApi) }

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .crossfade(true)
                .memoryCache {
                    MemoryCache.Builder(this@ParkerTunesApp)
                        .maxSizePercent(0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("parker_tunes_image_cache"))
                        .maxSizeBytes(256L * 1024L * 1024L)
                        .build()
                }
                .build()
        )
        musicApi = AddonMusicApi(this)
    }
}
