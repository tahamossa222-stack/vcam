// language: Kotlin, file: DemoGuest.kt
package com.vcam.demo

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vcam.api.SandboxEntry
import com.vcam.api.SandboxEnv
import com.vcam.core.VirtualCamera

/**
 * Built-in guest. Consumes frames from a private ImageReader so the whole
 * container path is exercised without an external APK: guest thread, guest
 * classloader, guest Context, SandboxEnv, broker, native sink.
 */
class DemoGuest : SandboxEntry {

    private var reader: ImageReader? = null
    private var camera: VirtualCamera? = null
    private var frames = 0L

    override fun onCreate(env: SandboxEnv) {
        val imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.PRIVATE, 3)
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage()
            if (image != null) {
                frames++
                image.close()
            }
        }, Handler(Looper.myLooper()!!))

        reader = imageReader
        camera = env.attachCamera(imageReader.surface, WIDTH, HEIGHT, FPS)

        if (camera == null) {
            Log.w(TAG, "native session refused ${WIDTH}x$HEIGHT")
        } else {
            Log.i(TAG, "demo guest streaming ${WIDTH}x${HEIGHT}@$FPS")
        }
    }

    override fun onDestroy() {
        camera?.close()
        camera = null
        reader?.close()
        reader = null
        Log.i(TAG, "demo guest stopped after $frames frames")
    }

    private companion object {
        const val TAG = "vcam-sandbox"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 30
    }
}
