// language: Kotlin, file: MainActivity.kt
package com.vcam.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.vcam.core.CameraBroker
import com.vcam.core.NativeBridge
import com.vcam.core.VideoFeeder
import com.vcam.core.VirtualCamera
import com.vcam.sandbox.GuestPackage
import com.vcam.sandbox.SandboxCore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Control plane: media source selection, host sink preview, guest management.
 *
 * The preview uses the same native path a guest gets, so it doubles as the
 * health check for the whole pipeline.
 */
class MainActivity : Activity() {

    private enum class SourceKind { NONE, VIDEO, IMAGE }

    private lateinit var guestList: LinearLayout
    private lateinit var recentList: LinearLayout
    private lateinit var status: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var preview: SurfaceView

    private var previewCamera: VirtualCamera? = null
    private var feeder: VideoFeeder? = null

    private var selectedUri: Uri? = null
    private var sourceKind = SourceKind.NONE
    private var loop = true

    private val prefs by lazy { getSharedPreferences("vcam-media", MODE_PRIVATE) }

    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsTick = object : Runnable {
        override fun run() {
            val camera = previewCamera ?: return
            val s = camera.stats()
            status.text = "presented=${s[0]}  dropped=${s[1]}  errors=${s[2]}"
            statsHandler.postDelayed(this, 1000)
        }
    }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            previewCamera?.close()
            val camera = CameraBroker.attach(holder.surface, PREVIEW_W, PREVIEW_H)
            previewCamera = camera
            if (camera != null) {
                applySource(camera)
                statsHandler.removeCallbacks(statsTick)
                statsHandler.post(statsTick)
            } else {
                status.text = "preview refused (foreground check or EGL)"
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            statsHandler.removeCallbacks(statsTick)
            feeder?.stop()
            feeder = null
            previewCamera?.close()
            previewCamera = null
            status.text = "preview stopped"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedUri = prefs.getString(KEY_URI, null)?.let(Uri::parse)
        sourceKind = when (prefs.getInt(KEY_KIND, 0)) {
            1 -> SourceKind.VIDEO
            2 -> SourceKind.IMAGE
            else -> SourceKind.NONE
        }
        loop = prefs.getBoolean(KEY_LOOP, true)

        setContentView(buildUi())
        preview.holder.addCallback(holderCallback)
        refreshRecent()
        refresh()
    }

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsTick)
        feeder?.stop()
        feeder = null
        previewCamera?.close()
        previewCamera = null
        SandboxCore.stopAll()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "VCam Modern"
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = "Non-root container. Guests are loaded into this process and " +
                "receive frames only through the sandbox camera API."
            textSize = 12f
        })

        sourceLabel = TextView(this).apply {
            text = describeSource()
            textSize = 13f
        }
        root.addView(sourceLabel)

        val mediaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mediaRow.addView(Button(this).apply {
            text = "Video"
            setOnClickListener { pickMedia(REQ_VIDEO, "video/*") }
        })
        mediaRow.addView(Button(this).apply {
            text = "Image"
            setOnClickListener { pickMedia(REQ_IMAGE, "image/*") }
        })
        mediaRow.addView(Button(this).apply {
            text = "Test"
            setOnClickListener { selectSource(SourceKind.NONE, null) }
        })
        root.addView(mediaRow)

        recentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recentList)

        root.addView(CheckBox(this).apply {
            text = "Loop video"
            isChecked = loop
            setOnCheckedChangeListener { _, checked ->
                loop = checked
                prefs.edit().putBoolean(KEY_LOOP, checked).apply()
                previewCamera?.let { applySource(it) }
            }
        })

        status = TextView(this).apply {
            text = "idle"
            textSize = 12f
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Install guest APK"
            setOnClickListener { pickApk() }
        })

        guestList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(guestList)

        root.addView(TextView(this).apply {
            text = "Host sink preview"
            textSize = 14f
        })

        preview = SurfaceView(this)
        root.addView(preview, LinearLayout.LayoutParams(MATCH, 720))

        return ScrollView(this).apply { addView(root) }
    }

    // ------------------------------------------------------------- media flow

    private fun applySource(camera: VirtualCamera) {
        feeder?.stop()
        feeder = null

        when (sourceKind) {
            SourceKind.VIDEO -> {
                val uri = selectedUri
                val decodeSurface = camera.videoSurface()
                if (uri != null && decodeSurface != null) {
                    camera.setSource(NativeBridge.SOURCE_VIDEO)
                    feeder = VideoFeeder(this, uri, decodeSurface, loop) { w, h ->
                        camera.setSourceSize(w, h)
                    }.also { it.start() }
                } else {
                    camera.setSource(NativeBridge.SOURCE_TEST)
                    status.text = "video surface unavailable"
                }
            }

            SourceKind.IMAGE -> {
                val uri = selectedUri
                if (uri != null) {
                    camera.setSource(NativeBridge.SOURCE_IMAGE)
                    pushImage(camera, uri)
                } else {
                    camera.setSource(NativeBridge.SOURCE_TEST)
                }
            }

            SourceKind.NONE -> camera.setSource(NativeBridge.SOURCE_TEST)
        }
    }

    private fun selectSource(kind: SourceKind, uri: Uri?) {
        sourceKind = kind
        selectedUri = uri
        prefs.edit()
            .putInt(KEY_KIND, kind.ordinal)
            .putString(KEY_URI, uri?.toString())
            .apply()
        remember(kind, uri)
        refreshRecent()
        sourceLabel.text = describeSource()
        previewCamera?.let { applySource(it) }
    }

    private data class Recent(val kind: SourceKind, val uri: Uri)

    private fun remember(kind: SourceKind, uri: Uri?) {
        if (uri == null || kind == SourceKind.NONE) return
        val tag = if (kind == SourceKind.VIDEO) "v" else "i"
        val merged = buildList {
            add("$tag|$uri")
            recentEntries().forEach {
                add("${if (it.kind == SourceKind.VIDEO) "v" else "i"}|${it.uri}")
            }
        }.distinct().take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, merged.joinToString("\n")).apply()
    }

    private fun recentEntries(): List<Recent> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('|', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val kind = when (parts[0]) {
                "v" -> SourceKind.VIDEO
                "i" -> SourceKind.IMAGE
                else -> return@mapNotNull null
            }
            Recent(kind, Uri.parse(parts[1]))
        }.toList()
    }

    private fun refreshRecent() {
        recentList.removeAllViews()
        val entries = recentEntries()
        if (entries.isEmpty()) return

        recentList.addView(TextView(this).apply {
            text = "Recent media"
            textSize = 12f
        })
        entries.forEach { entry ->
            recentList.addView(Button(this).apply {
                text = (if (entry.kind == SourceKind.VIDEO) "\u25B8 " else "\u25AA ") +
                    (entry.uri.lastPathSegment ?: entry.uri.toString())
                setOnClickListener { selectSource(entry.kind, entry.uri) }
            })
        }
    }

    private fun pushImage(camera: VirtualCamera, uri: Uri) {
        Thread({
            runCatching {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false

                    val w = info.size.width
                    val h = info.size.height
                    val longEdge = maxOf(w, h)
                    if (longEdge > MAX_IMAGE_EDGE) {
                        val scale = MAX_IMAGE_EDGE.toFloat() / longEdge
                        decoder.setTargetSize(
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }

                val argb = if (decoded.config == Bitmap.Config.ARGB_8888) {
                    decoded
                } else {
                    val converted = decoded.copy(Bitmap.Config.ARGB_8888, false)
                    decoded.recycle()
                    converted ?: error("bitmap conversion failed")
                }

                val buffer = ByteBuffer.allocateDirect(argb.byteCount)
                    .order(ByteOrder.nativeOrder())
                argb.copyPixelsToBuffer(buffer)
                buffer.rewind()

                camera.setSourceSize(argb.width, argb.height)
                camera.pushImage(buffer, argb.width, argb.height)
                argb.recycle()
            }.onFailure {
                runOnUiThread { toast("image failed: ${it.message}") }
            }
        }, "vcam-image").start()
    }

    private fun describeSource(): String = when (sourceKind) {
        SourceKind.VIDEO -> "source: video ${selectedUri?.lastPathSegment ?: ""}"
        SourceKind.IMAGE -> "source: image ${selectedUri?.lastPathSegment ?: ""}"
        SourceKind.NONE -> "source: test pattern"
    }

    @Suppress("DEPRECATION")
    private fun pickMedia(request: Int, mime: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, request)
    }

    // -------------------------------------------------------------- guest flow

    private fun refresh() {
        guestList.removeAllViews()

        val guests = SandboxCore.list()
        if (guests.isEmpty()) {
            guestList.addView(TextView(this).apply { text = "no guests installed" })
            return
        }
        guests.forEach { guestList.addView(guestRow(it)) }
    }

    private fun guestRow(guest: GuestPackage): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 24)
        }

        row.addView(TextView(this).apply {
            text = "${guest.label}\n${guest.packageName}  v${guest.versionName}"
            textSize = 14f
        })

        val running = SandboxCore.host(guest.packageName)?.isRunning == true
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        buttons.addView(Button(this).apply {
            text = if (running) "Stop" else "Start"
            setOnClickListener {
                if (SandboxCore.host(guest.packageName)?.isRunning == true) {
                    SandboxCore.stop(guest.packageName)
                    toast("stopped ${guest.packageName}")
                } else {
                    SandboxCore.start(guest.packageName)
                        .onSuccess { toast("started ${guest.packageName}") }
                        .onFailure { toast("start failed: ${it.message}") }
                }
                refresh()
            }
        })

        if (!guest.builtin) {
            buttons.addView(Button(this).apply {
                text = "Uninstall"
                setOnClickListener {
                    SandboxCore.uninstall(guest.packageName)
                    refresh()
                }
            })
        }

        row.addView(buttons)
        return row
    }

    @Suppress("DEPRECATION")
    private fun pickApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        startActivityForResult(intent, REQ_APK)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return

        when (requestCode) {
            REQ_APK -> importApk(uri)
            REQ_VIDEO -> {
                persist(uri)
                selectSource(SourceKind.VIDEO, uri)
            }
            REQ_IMAGE -> {
                persist(uri)
                selectSource(SourceKind.IMAGE, uri)
            }
        }
    }

    private fun persist(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun importApk(uri: Uri) {
        val staging = File(cacheDir, "import.apk")

        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: error("cannot open $uri")
        }.onFailure {
            toast("import failed: ${it.message}")
        }.onSuccess {
            SandboxCore.install(staging)
                .onSuccess {
                    toast("installed ${it.packageName}")
                    refresh()
                }
                .onFailure { toast("install failed: ${it.message}") }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQ_APK = 1001
        const val REQ_VIDEO = 1002
        const val REQ_IMAGE = 1003

        const val PREVIEW_W = 1280
        const val PREVIEW_H = 720

        const val KEY_URI = "uri"
        const val KEY_KIND = "kind"
        const val KEY_LOOP = "loop"
        const val KEY_RECENT = "recent"
        const val MAX_RECENT = 8
        const val MAX_IMAGE_EDGE = 1920

        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
