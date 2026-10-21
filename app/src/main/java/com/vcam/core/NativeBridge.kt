// language: Kotlin, file: NativeBridge.kt, target: NDK r28+
package com.vcam.core

import android.view.Surface
import java.nio.ByteBuffer

/**
 * Allocation-free binding to libvcam_hook. Every method tolerates handle 0;
 * the native side no-ops on an invalid or already-freed pointer.
 */
internal object NativeBridge {

    init {
        System.loadLibrary("vcam_hook")
    }

    const val MATRIX_BT601 = 0
    const val MATRIX_BT709 = 1
    const val MATRIX_BT2020 = 2

    const val SOURCE_TEST = 0
    const val SOURCE_VIDEO = 1
    const val SOURCE_IMAGE = 2
    const val SOURCE_NV21 = 3

    /**
     * Spins the EGL render thread for [surface]. The sink starts disarmed and
     * emits nothing until [setActive] is called. Returns 0 on init failure.
     */
    external fun createSession(
        surface: Surface,
        width: Int,
        height: Int,
        matrix: Int,
        limitedRange: Boolean,
    ): Long

    external fun destroySession(handle: Long)

    /** Arms or disarms the present loop without tearing down EGL or the ring. */
    external fun setActive(handle: Long, active: Boolean)

    /** [width, height] of the buffer backing [surface], or null when unknown. */
    external fun surfaceSize(surface: Surface): IntArray?

    /** [presented, dropped, errors] */
    external fun stats(handle: Long): LongArray

    external fun setFps(handle: Long, fps: Int)

    /** Decoder input surface backed by the session's external OES texture. */
    external fun videoSurface(handle: Long): Surface?

    external fun setSource(handle: Long, mode: Int)

    /** Intrinsic size of the feed, used for center-crop scaling. */
    external fun setSourceSize(handle: Long, width: Int, height: Int)

    /** RGBA8888, tightly packed, direct buffer. */
    external fun pushImage(handle: Long, rgba: ByteBuffer, width: Int, height: Int)

    /** NV21, tightly packed, direct buffer. */
    external fun pushNv21(handle: Long, data: ByteBuffer, width: Int, height: Int)
}
