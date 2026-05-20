package com.abuzahra.manager

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Firebase
        FirebaseApp.initializeApp(this)
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)

        // Load saved config
        val prefs = getSharedPreferences("abuzahra", MODE_PRIVATE)
        Config.SERVER_DOMAIN = prefs.getString("server_domain", Config.SERVER_DOMAIN)!!
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
