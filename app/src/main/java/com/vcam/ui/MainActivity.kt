// language: Kotlin, file: MainActivity.kt
package com.vcam.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.vcam.core.CameraBroker
import com.vcam.core.VirtualCamera
import com.vcam.sandbox.GuestPackage
import com.vcam.sandbox.SandboxCore
import java.io.File

/**
 * Control plane: install guests, start and stop them, and watch the host sink.
 * The preview is the same native path a guest gets, so it doubles as the
 * health check for the whole pipeline.
 */
class MainActivity : Activity() {

    private lateinit var guestList: LinearLayout
    private lateinit var status: TextView
    private lateinit var preview: SurfaceView

    private var previewCamera: VirtualCamera? = null

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
            previewCamera = CameraBroker.attach(holder.surface, PREVIEW_W, PREVIEW_H)
            if (previewCamera != null) {
                statsHandler.removeCallbacks(statsTick)
                statsHandler.post(statsTick)
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            statsHandler.removeCallbacks(statsTick)
            previewCamera?.close()
            previewCamera = null
            status.text = "preview stopped"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        preview.holder.addCallback(holderCallback)
        refresh()
    }

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsTick)
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
            text = "VCam Sandbox"
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = "Non-root container. Guests are loaded into this process and " +
                "receive frames only through the sandbox camera API."
            textSize = 12f
        })

        root.addView(Button(this).apply {
            text = "Install guest APK"
            setOnClickListener { pickApk() }
        })

        status = TextView(this).apply {
            text = "idle"
            textSize = 12f
        }
        root.addView(status)

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

    @Deprecated("The result API is retained to avoid an AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_APK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        importApk(uri)
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
        const val PREVIEW_W = 1280
        const val PREVIEW_H = 720
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
