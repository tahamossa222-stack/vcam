// language: Kotlin, file: NativeBridge.kt, target: NDK r28+
package com.vcam.hook

import android.view.Surface

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
}
