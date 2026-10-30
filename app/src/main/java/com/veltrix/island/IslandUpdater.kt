package com.veltrix.island

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class IslandUpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

object IslandUpdater {
    private const val RELEASE_API = "https://api.github.com/repos/Nexora-Apparel/Veltrix-Dynamic-island-/releases/tags/latest"
    private const val EXPECTED_PACKAGE = "com.veltrix.island"
    private const val APK_NAME = "VeltrixIsland-update.apk"
    private const val PREFS = "veltrix_updater"

    suspend fun check(): IslandUpdateInfo? = withContext(Dispatchers.IO) {
        val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Veltrix-Island-Android")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("GitHub returned HTTP ${connection.responseCode}")
            }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val versionCode = Regex("(?m)^versionCode=(\\d+)\\s*$")
                .find(release.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IllegalStateException("Release is missing version metadata")
            val assets = release.optJSONArray("assets") ?: throw IllegalStateException("Release has no assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name") == "VeltrixIsland.apk") {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            val safeUrl = apkUrl?.takeIf {
                it.startsWith("https://github.com/Nexora-Apparel/Veltrix-Dynamic-island-/releases/download/")
            } ?: throw IllegalStateException("Official APK asset is missing")
            IslandUpdateInfo(versionCode, release.optString("name", "Veltrix Island update"), safeUrl)
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, update: IslandUpdateInfo): Long {
        require(update.apkUrl.startsWith("https://github.com/Nexora-Apparel/Veltrix-Dynamic-island-/releases/download/")) {
            "Rejected non-official update URL"
        }
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("Veltrix Island update")
            .setDescription("Downloading ${update.versionName}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("download_id", id)
            .putInt("expected_version", update.versionCode)
            .apply()
        return id
    }

    fun installDownloaded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong("download_id", -1L)
        if (id < 0) return false
        val manager = context.getSystemService(DownloadManager::class.java)
        val downloadedUri = manager.getUriForDownloadedFile(id) ?: return false
        val verifiedApk = File(context.cacheDir, APK_NAME)
        context.contentResolver.openInputStream(downloadedUri)?.use { input ->
            verifiedApk.outputStream().use { output -> input.copyTo(output) }
        } ?: return false

        val archiveInfo = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageArchiveInfo(
                verifiedApk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(
                verifiedApk.absolutePath,
                PackageManager.GET_SIGNATURES
            )
        } ?: throw IllegalStateException("Downloaded file is not a valid Android APK")

        require(archiveInfo.packageName == EXPECTED_PACKAGE) {
            "Package mismatch: expected $EXPECTED_PACKAGE, received ${archiveInfo.packageName}"
        }

        val installedInfo = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

        val installedSignatures = if (Build.VERSION.SDK_INT >= 28) {
            installedInfo.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            installedInfo.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        }
        val updateSignatures = if (Build.VERSION.SDK_INT >= 28) {
            archiveInfo.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        }
        require(installedSignatures.isNotEmpty() && installedSignatures == updateSignatures) {
            "Signing certificate mismatch. This APK cannot update the installed Veltrix Island."
        }

        val installedVersion = if (Build.VERSION.SDK_INT >= 28) installedInfo.longVersionCode else {
            @Suppress("DEPRECATION")
            installedInfo.versionCode.toLong()
        }
        val downloadedVersion = if (Build.VERSION.SDK_INT >= 28) archiveInfo.longVersionCode else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }
        require(downloadedVersion > installedVersion) {
            "Downloaded version $downloadedVersion is not newer than installed version $installedVersion."
        }
        val expectedVersion = prefs.getInt("expected_version", -1)
        require(expectedVersion < 0 || downloadedVersion == expectedVersion.toLong()) {
            "Downloaded APK version does not match the release metadata."
        }

        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            verifiedApk
        )
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(installIntent)
        return true
    }

    fun currentDownloadId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("download_id", -1L)

    fun downloadStatus(context: Context): Int? {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("download_id", -1L)
        if (id < 0) return null
        context.getSystemService(DownloadManager::class.java)
            .query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return null
                return cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
    }
}
