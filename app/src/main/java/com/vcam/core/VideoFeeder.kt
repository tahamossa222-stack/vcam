// language: Kotlin, file: VideoFeeder.kt
package com.vcam.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.Surface

/**
 * Decodes a local video into the session's external texture.
 *
 * The decoder renders directly into a Surface owned by the native sink, so the
 * frame path is decoder -> gralloc buffer -> external OES texture -> shader.
 * No pixel data is copied through Kotlin or through the CPU.
 */
class VideoFeeder(
    private val context: Context,
    private val uri: Uri,
    private val surface: Surface,
    private val loop: Boolean,
    private val onSize: ((Int, Int) -> Unit)? = null,
) {

    @Volatile
    private var running = false

    private var thread: Thread? = null

    @Volatile
    var lastError: String? = null
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({ decodeLoop() }, "vcam-decode").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(1500) } }
        thread = null
    }

    private fun decodeLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            val ex = MediaExtractor()
            extractor = ex

            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("cannot open $uri")
            pfd.use { ex.setDataSource(it.fileDescriptor) }

            val track = selectVideoTrack(ex) ?: error("no video track in $uri")
            ex.selectTrack(track)

            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("track has no mime")

            val srcW = format.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (srcW > 0 && srcH > 0) onSize?.invoke(srcW, srcH)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, surface, null, 0)
            decoder.start()

            pump(ex, decoder)
        } catch (t: Throwable) {
            lastError = t.toString()
            Log.e(TAG, "decode failed: $t")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            Log.i(TAG, "feeder stopped")
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var basePtsUs = -1L
        var baseNs = 0L

        while (running && !outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)
                    if (buffer == null) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    } else {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outIndex >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size > 0

                    codec.releaseOutputBuffer(outIndex, render)

                    if (render) {
                        if (basePtsUs < 0L) {
                            basePtsUs = info.presentationTimeUs
                            baseNs = System.nanoTime()
                        }
                        val target = baseNs + (info.presentationTimeUs - basePtsUs) * 1000L
                        val waitNs = target - System.nanoTime()
                        if (waitNs > 0L) {
                            Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                        }
                    }

                    if (eos) {
                        if (loop) {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            codec.flush()
                            codec.start()
                            inputDone = false
                            basePtsUs = -1L
                        } else {
                            outputDone = true
                        }
                    }
                }
            }
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    private companion object {
        const val TAG = "vcam-native"
        const val TIMEOUT_US = 10_000L
    }
}
