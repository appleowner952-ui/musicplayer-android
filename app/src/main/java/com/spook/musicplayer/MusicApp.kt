package com.spook.musicplayer

import android.app.Application

class MusicApp : Application() {
    lateinit var engine: PlayerEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = PlayerEngine(this)
    }
}
