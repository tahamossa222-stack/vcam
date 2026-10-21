// language: Kotlin, file: MediaCodecProxy.kt
package com.vcam.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * Encoder plus the virtual camera feeding its input surface.
 *
 * The codec input surface is a BufferQueue owned by MediaCodec; the native
 * sink renders into it exactly as it would into a SurfaceView. This replaces
 * the capture-side Surface a real camera would hand to the encoder.
 */
class EncodedCamera internal constructor(
    val codec: MediaCodec,
    val camera: VirtualCamera,
) : AutoCloseable {

    override fun close() {
        camera.close()
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

object MediaCodecProxy {

    private const val TAG = "vcam-native"

    fun createEncoder(
        width: Int,
        height: Int,
        mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        bitRate: Int = 8_000_000,
        fps: Int = 30,
        iFrameIntervalSeconds: Int = 2,
    ): EncodedCamera? {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds)
        }

        val codec = runCatching { MediaCodec.createEncoderByType(mime) }.getOrElse {
            Log.w(TAG, "no encoder for $mime: $it")
            return null
        }

        val input: Surface = try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            surface
        } catch (t: Throwable) {
            Log.w(TAG, "encoder configure failed: $t")
            runCatching { codec.release() }
            return null
        }

        val camera = CameraBroker.attach(input, width, height, fps = fps)
        if (camera == null) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            return null
        }
        return EncodedCamera(codec, camera)
    }
}
