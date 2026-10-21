// language: Kotlin, file: CameraBroker.kt
package com.vcam.core

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import android.view.Surface

/**
 * The single entry point for producing virtual camera frames.
 *
 * Callers own the Surface they want frames delivered to: a SurfaceView, a
 * MediaCodec input surface, an ImageReader, or a SurfaceTexture. The broker
 * enforces the Android 14/15 rule that camera work only happens while the
 * process is visible, so a sandboxed guest cannot stream from the background.
 */
object CameraBroker {

    private const val TAG = "vcam-native"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Attaches a sink to [surface] and starts presenting immediately.
     * Returns null when the surface is invalid, the process is backgrounded,
     * or the native session refuses the geometry.
     */
    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        matrix: Int = NativeBridge.MATRIX_BT709,
        limitedRange: Boolean = true,
        fps: Int = 30,
    ): VirtualCamera? {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "attach refused: geometry ${width}x$height")
            return null
        }
        if (!surface.isValid) {
            Log.w(TAG, "attach refused: invalid surface")
            return null
        }
        if (!isForeground()) {
            Log.w(TAG, "attach refused: process not foreground")
            return null
        }

        val handle = NativeBridge.createSession(surface, width, height, matrix, limitedRange)
        if (handle == 0L) {
            Log.w(TAG, "attach refused: native session failed ${width}x$height")
            return null
        }

        NativeBridge.setFps(handle, fps)
        NativeBridge.setActive(handle, true)
        Log.i(TAG, "attached ${width}x$height fps=$fps handle=$handle")
        return VirtualCamera(surface, width, height, handle)
    }

    fun isForeground(): Boolean {
        val ctx = appContext ?: return true
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val processes = runCatching { am.runningAppProcesses }.getOrNull() ?: return true
        val self = processes.firstOrNull { it.pid == Process.myPid() } ?: return true
        return self.importance <=
               ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }
}
