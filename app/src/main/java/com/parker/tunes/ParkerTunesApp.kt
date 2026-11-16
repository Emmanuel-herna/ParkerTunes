package com.parker.tunes

import android.app.Application
import com.parker.tunes.data.AddonMusicApi
import com.parker.tunes.data.LibraryDatabase
import com.parker.tunes.data.MusicApi

class ParkerTunesApp : Application() {
    lateinit var musicApi: MusicApi
        private set
    val database by lazy { LibraryDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        musicApi = AddonMusicApi(this)
    }
}
