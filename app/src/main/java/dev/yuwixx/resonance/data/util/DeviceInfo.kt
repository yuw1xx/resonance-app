package dev.yuwixx.resonance.data.util

import android.content.Context
import android.os.Build

object DeviceInfo {

    fun isGrapheneOs(context: Context): Boolean =
        checkBuildStrings() || checkGraphenePackages(context)

    private fun checkBuildStrings(): Boolean {
        val lower = { s: String -> s.lowercase() }
        return lower(Build.DISPLAY).contains("grapheneos") ||
            lower(Build.FINGERPRINT).contains("grapheneos") ||
            lower(Build.HOST).contains("grapheneos") ||
            lower(Build.USER).contains("grapheneos")
    }

    private fun checkGraphenePackages(context: Context): Boolean {
        val pm = context.packageManager
        return GRAPHENE_PACKAGES.any { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        }
    }

    private val GRAPHENE_PACKAGES = listOf(
        "org.grapheneos.pdfviewer",
        "org.grapheneos.vanadium",
        "org.grapheneos.camera",
        "org.grapheneos.contacts",
        "org.grapheneos.gallery",
        "org.grapheneos.apps.client",
        "app.attestation.auditor",
    )
}
