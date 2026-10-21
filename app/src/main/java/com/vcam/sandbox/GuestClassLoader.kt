// language: Kotlin, file: GuestClassLoader.kt
package com.vcam.sandbox

import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipFile

/**
 * Isolates a guest's code and native libraries.
 *
 * Guest classes load child-first only in the sense that the guest dex is
 * searched by this loader; framework classes still come from the boot loader,
 * which is why the platform camera cannot be shadowed here. Host internals
 * outside the published sandbox API are refused explicitly.
 */
class GuestClassLoader private constructor(
    apkPath: String,
    dexDir: String,
    libDir: String?,
    parent: ClassLoader,
) : DexClassLoader(apkPath, dexDir, libDir, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith(HOST_PREFIX) && ALLOWED.none { name.startsWith(it) }) {
            throw ClassNotFoundException("$name is outside the sandbox API")
        }
        return super.loadClass(name, resolve)
    }

    companion object {
        private const val TAG = "vcam-sandbox"
        private const val HOST_PREFIX = "com.vcam."
        private const val ABI = "arm64-v8a"

        private val ALLOWED = listOf(
            "com.vcam.api.",
            "com.vcam.core.CameraBroker",
            "com.vcam.core.MediaCodecProxy",
            "com.vcam.core.VirtualCamera",
            "com.vcam.core.EncodedCamera",
            "com.vcam.core.NativeBridge",
        )

        fun create(guest: GuestPackage, dir: File): GuestClassLoader {
            val libs = File(dir, "libs").apply { mkdirs() }
            extractNativeLibraries(guest.apkFile, libs)

            val dexOut = File(dir, "dex").apply { mkdirs() }
            return GuestClassLoader(
                guest.apkFile.absolutePath,
                dexOut.absolutePath,
                libs.absolutePath,
                GuestClassLoader::class.java.classLoader,
            )
        }

        /**
         * Copies shared objects under lib/<abi>/ out of the guest APK. Guests
         * without native code, or with an ABI this device does not support,
         * load fine; their native calls simply fail at use time.
         */
        private fun extractNativeLibraries(apk: File, out: File) {
            runCatching {
                ZipFile(apk).use { zip ->
                    val prefix = "lib/$ABI/"
                    for (entry in zip.entries()) {
                        if (!entry.name.startsWith(prefix)) continue
                        if (!entry.name.endsWith(".so")) continue

                        val target = File(out, entry.name.substringAfterLast('/'))
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                        target.setReadOnly()
                    }
                }
            }.onFailure { Log.w(TAG, "native extraction failed for ${apk.name}: $it") }
        }
    }
}
