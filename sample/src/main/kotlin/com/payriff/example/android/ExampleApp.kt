package com.payriff.example.android

import android.app.Application
import com.payriff.android.Payriff

/**
 * Configures Payriff in Application.onCreate, so the configuration is available again if Android recreates the
 * process while the 3-D Secure sheet is open.
 */
class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ExampleSettings.forgetSavedSettings(this)
        Payriff.configure(ExampleSettings.config())
    }
}
