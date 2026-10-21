// language: Kotlin, file: SandboxEntry.kt
package com.vcam.api

import android.content.Context
import android.view.Surface
import com.vcam.core.CameraBroker
import com.vcam.core.EncodedCamera
import com.vcam.core.MediaCodecProxy
import com.vcam.core.VirtualCamera

/**
 * Contract a guest module implements to run inside the sandbox.
 *
 * The entry class is named by the `vcam.sandbox.ENTRY` meta-data in the guest
 * APK. It is instantiated reflectively through a dedicated classloader and
 * called on a thread with its own Looper, never on the host main thread.
 */
interface SandboxEntry {
    fun onCreate(env: SandboxEnv)
    fun onDestroy()
}

/**
 * Handles a guest receives instead of a raw Context.
 *
 * The Context is virtualized: storage, package name, classloader and resources
 * all resolve to the guest's private directory and APK. The camera is not the
 * platform camera; [attachCamera] and [createEncoder] are the only ways to
 * obtain frames, and both write through the native compositor.
 */
class SandboxEnv(
    val context: Context,
    val packageName: String,
) {

    /** Renders virtual frames into a Surface the guest already owns. */
    fun attachCamera(
        surface: Surface,
        width: Int,
        height: Int,
        fps: Int = 30,
    ): VirtualCamera? = CameraBroker.attach(surface, width, height, fps = fps)

    /** Encoder whose input surface is fed by the virtual camera. */
    fun createEncoder(
        width: Int,
        height: Int,
        mime: String = "video/avc",
        fps: Int = 30,
    ): EncodedCamera? = MediaCodecProxy.createEncoder(width, height, mime, fps = fps)}
