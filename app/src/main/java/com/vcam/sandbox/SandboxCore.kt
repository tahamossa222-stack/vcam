// language: Kotlin, file: SandboxCore.kt
package com.vcam.sandbox

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Guest registry and lifecycle owner.
 *
 * Guests live under filesDir/guests/<package>/: the APK is stored read-only,
 * each gets a private data tree, an odex directory and an extracted native
 * library directory. Nothing here touches the system package manager, and no
 * step requires root.
 */
object SandboxCore {

    private const val TAG = "vcam-sandbox"
    private const val INDEX = "guests.json"
    private const val BUILTIN_ID = "com.vcam.demo"
    private const val BUILTIN_ENTRY = "com.vcam.demo.DemoGuest"

    @Volatile
    private var root: File? = null

    @Volatile
    private var appContext: Context? = null

    private val guests = LinkedHashMap<String, GuestPackage>()
    private val hosts = HashMap<String, GuestHost>()

    @Synchronized
    fun init(context: Context) {
        if (root != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val dir = File(ctx.filesDir, "guests").apply { mkdirs() }
        root = dir
        loadIndex(dir)
        ensureBuiltin()
    }

    /**
     * Registers the in-process demo guest. It uses the host APK as its own
     * archive, which proves the container loop without an external download.
     */
    @Synchronized
    fun ensureBuiltin() {
        val ctx = appContext ?: return
        if (guests.containsKey(BUILTIN_ID)) return

        val pm = ctx.packageManager
        val info = runCatching { pm.getPackageInfo(ctx.packageName, 0) }.getOrNull() ?: return
        val ai = info.applicationInfo ?: return
        ai.sourceDir = ctx.packageCodePath
        ai.publicSourceDir = ctx.packageCodePath

        guests[BUILTIN_ID] = GuestPackage(
            packageName = BUILTIN_ID,
            label = "Demo guest (built in)",
            versionName = info.versionName ?: "0",
            minSdk = 30,
            entryClass = BUILTIN_ENTRY,
            apkFile = File(ctx.packageCodePath),
            applicationInfo = ai,
            builtin = true,
        )
        saveIndex()
    }

    @Synchronized
    fun list(): List<GuestPackage> = guests.values.toList()

    @Synchronized
    fun host(packageName: String): GuestHost? = hosts[packageName]

    @Synchronized
    fun install(source: File): Result<GuestPackage> {
        val ctx = appContext
            ?: return Result.failure(IllegalStateException("SandboxCore.init was not called"))
        val pm = ctx.packageManager

        val probe = GuestPackage.parse(pm, source)
            ?: return Result.failure(
                IllegalArgumentException(
                    "not a VCam guest APK: missing ${GuestPackage.ENTRY_META} meta-data",
                ),
            )

        if (probe.minSdk > Build.VERSION.SDK_INT) {
            return Result.failure(
                IllegalArgumentException(
                    "guest needs API ${probe.minSdk}, this device is API ${Build.VERSION.SDK_INT}",
                ),
            )
        }

        return runCatching {
            val dir = File(root!!, probe.packageName).apply { mkdirs() }
            listOf("libs", "dex", "data").forEach { File(dir, it).mkdirs() }

            val dest = File(dir, "base.apk")
            source.copyTo(dest, overwrite = true)
            dest.setReadOnly()

            val installed = GuestPackage.parse(pm, dest)
                ?: error("installed APK could not be re-parsed")

            guests[installed.packageName] = installed
            saveIndex()
            Log.i(TAG, "installed ${installed.packageName} entry=${installed.entryClass}")
            installed
        }
    }

    @Synchronized
    fun uninstall(packageName: String) {
        stop(packageName)
        guests.remove(packageName)
        runCatching { File(root!!, packageName).deleteRecursively() }
        saveIndex()
        Log.i(TAG, "uninstalled $packageName")
    }

    @Synchronized
    fun start(packageName: String): Result<GuestHost> {
        val guest = guests[packageName]
            ?: return Result.failure(NoSuchElementException("guest $packageName is not installed"))

        hosts[packageName]?.let { existing ->
            if (existing.isRunning) return Result.success(existing)
        }

        val ctx = appContext
            ?: return Result.failure(IllegalStateException("SandboxCore.init was not called"))

        return runCatching {
            val dir = File(root!!, packageName)
            val loader: ClassLoader =
                if (guest.builtin) javaClass.classLoader
                else GuestClassLoader.create(guest, dir)

            val guestContext = GuestContext(ctx, guest, loader, File(dir, "data"))
            val host = GuestHost(guest, loader, guestContext)

            host.start()
            check(host.isRunning) { host.lastError ?: "guest failed to start" }

            hosts[packageName] = host
            Log.i(TAG, "started $packageName on ${Thread.currentThread().name}")
            host
        }
    }

    @Synchronized
    fun stop(packageName: String) {
        hosts.remove(packageName)?.stop()
    }

    @Synchronized
    fun stopAll() {
        hosts.values.toList().forEach { it.stop() }
        hosts.clear()
    }

    private fun saveIndex() {
        val dir = root ?: return
        val arr = JSONArray()
        for (guest in guests.values) {
            arr.put(
                JSONObject().apply {
                    put("packageName", guest.packageName)
                    put("builtin", guest.builtin)
                },
            )
        }
        runCatching { File(dir, INDEX).writeText(arr.toString()) }
            .onFailure { Log.w(TAG, "index write failed: $it") }
    }

    private fun loadIndex(dir: File) {
        val file = File(dir, INDEX)
        if (!file.isFile) return

        runCatching {
            val pm = appContext?.packageManager ?: return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optBoolean("builtin", false)) continue

                val pkg = obj.getString("packageName")
                val apk = File(File(dir, pkg), "base.apk")
                if (!apk.isFile) continue

                GuestPackage.parse(pm, apk)?.let { guests[it.packageName] = it }
            }
        }.onFailure { Log.w(TAG, "index unreadable: $it") }
    }
}
