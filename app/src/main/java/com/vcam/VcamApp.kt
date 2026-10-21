// language: Kotlin, file: VcamApp.kt
package com.vcam

import android.app.Application
import com.vcam.core.CameraBroker
import com.vcam.sandbox.SandboxCore

class VcamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CameraBroker.init(this)
        SandboxCore.init(this)
    }

    override fun onTerminate() {
        SandboxCore.stopAll()
        super.onTerminate()
    }
}
