// language: Kotlin, file: VirtualCamera.kt
package com.vcam.core

import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * A live render session writing composited frames into [surface].
 *
 * Creation arms the native sink; [close] disarms and destroys it. Instances are
 * confined to whoever created them and are not safe to share across threads.
 */
class VirtualCamera internal constructor(
    val surface: Surface,
    val width: Int,
    val height: Int,
    private val handle: Long,
) : AutoCloseable {

    @Volatile
    private var closed = false

    /** [presented, dropped, errors] since the session was created. */
    fun stats(): LongArray =
        if (closed || handle == 0L) LongArray(3) else NativeBridge.stats(handle)

    fun setFps(fps: Int) {
        if (!closed && handle != 0L) NativeBridge.setFps(handle, fps)
    }

    fun pause() {
        if (!closed && handle != 0L) NativeBridge.setActive(handle, false)
    }

    fun resume() {
        if (!closed && handle != 0L) NativeBridge.setActive(handle, true)
    }

    /**
     * Surface a MediaCodec decoder can render into. Frames never touch the
     * CPU: the decoder writes into the BufferQueue and the sink samples the
     * external texture.
     */
    fun videoSurface(): Surface? =
        if (closed || handle == 0L) null else NativeBridge.videoSurface(handle)

    fun setSource(mode: Int) {
        if (!closed && handle != 0L) NativeBridge.setSource(handle, mode)
    }

    fun setSourceSize(width: Int, height: Int) {
        if (!closed && handle != 0L) NativeBridge.setSourceSize(handle, width, height)
    }

    fun pushImage(rgba: ByteBuffer, width: Int, height: Int) {
        if (!closed && handle != 0L) NativeBridge.pushImage(handle, rgba, width, height)
    }

    fun pushNv21(data: ByteBuffer, width: Int, height: Int) {
        if (!closed && handle != 0L) NativeBridge.pushNv21(handle, data, width, height)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (handle != 0L) {
            runCatching { NativeBridge.setActive(handle, false) }
            runCatching { NativeBridge.destroySession(handle) }
        }
        Log.i(TAG, "closed ${width}x$height")
    }

    private companion object {
        const val TAG = "vcam-native"
    }
}
