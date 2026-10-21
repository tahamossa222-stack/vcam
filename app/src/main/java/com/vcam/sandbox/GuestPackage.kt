// language: Kotlin, file: GuestPackage.kt
package com.vcam.sandbox

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File

/**
 * One installed guest, described entirely by its APK plus the sandbox entry
 * point declared in its manifest.
 */
data class GuestPackage(
    val packageName: String,
    val label: String,
    val versionName: String,
    val minSdk: Int,
    val entryClass: String,
    val apkFile: File,
    val applicationInfo: ApplicationInfo,
    val builtin: Boolean = false,
) {
    companion object {
        const val ENTRY_META = "vcam.sandbox.ENTRY"

        /**
         * Reads package identity and the entry class from an APK on disk.
         * Returns null when the archive is unreadable or declares no entry.
         */
        fun parse(pm: PackageManager, apk: File): GuestPackage? {
            val info = runCatching {
                pm.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES,
                )
            }.getOrNull() ?: return null

            val ai = info.applicationInfo ?: return null
            // Required before label resolution on an uninstalled archive.
            ai.sourceDir = apk.absolutePath
            ai.publicSourceDir = apk.absolutePath

            val entry = ai.metaData?.getString(ENTRY_META) ?: return null

            return GuestPackage(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(ai).toString() }
                    .getOrDefault(info.packageName),
                versionName = info.versionName ?: "0",
                minSdk = ai.minSdkVersion,
                entryClass = entry,
                apkFile = apk,
                applicationInfo = ai,
            )
        }
    }
}
