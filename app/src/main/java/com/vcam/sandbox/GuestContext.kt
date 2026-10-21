// language: Kotlin, file: GuestContext.kt
package com.vcam.sandbox

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import java.io.File

/**
 * A Context whose identity, storage and classloader belong to the guest.
 *
 * Everything else — services, permission checks, package manager — stays with
 * the host process, because a guest runs under the host UID and cannot obtain
 * permissions the host does not hold.
 */
class GuestContext(
    base: Context,
    val guest: GuestPackage,
    private val loader: ClassLoader,
    private val dataDir: File,
) : ContextWrapper(base) {

    private val guestResources: Resources by lazy { loadResources(base) }

    private fun loadResources(base: Context): Resources = runCatching {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
        addAssetPath.isAccessible = true
        val cookie = addAssetPath.invoke(assets, guest.apkFile.absolutePath) as? Int ?: -1
        check(cookie != 0) { "addAssetPath rejected ${guest.apkFile.name}" }
        Resources(assets, base.resources.displayMetrics, base.resources.configuration)
    }.getOrElse {
        Log.w(TAG, "resources unavailable for ${guest.packageName}: $it")
        base.resources
    }

    override fun getAssets(): AssetManager = guestResources.assets

    override fun getResources(): Resources = guestResources

    override fun getClassLoader(): ClassLoader = loader

    override fun getPackageName(): String = guest.packageName

    override fun getApplicationInfo(): ApplicationInfo = guest.applicationInfo

    override fun getFilesDir(): File = ensure("files")

    override fun getCacheDir(): File = ensure("cache")

    override fun getNoBackupFilesDir(): File = ensure("no_backup")

    override fun getCodeCacheDir(): File = ensure("code_cache")

    override fun getDir(name: String, mode: Int): File = ensure(name)

    override fun getExternalFilesDir(type: String?): File? {
        val base = super.getExternalFilesDir(null) ?: return null
        val dir = File(base, "guest-${guest.packageName}").apply { mkdirs() }
        return if (type == null) dir else File(dir, type).apply { mkdirs() }
    }

    /**
     * The platform camera is deliberately unavailable. A guest that asks for
     * it is told to use the sandbox API rather than silently receiving a real
     * camera it could not virtualize.
     */
    override fun getSystemService(name: String): Any? {
        if (name == Context.CAMERA_SERVICE) {
            throw SecurityException(
                "Camera2 is not available in the sandbox; use SandboxEnv.attachCamera()",
            )
        }
        return super.getSystemService(name)
    }

    private fun ensure(name: String): File = File(dataDir, name).apply { mkdirs() }

    private companion object {
        const val TAG = "vcam-sandbox"
    }
}
