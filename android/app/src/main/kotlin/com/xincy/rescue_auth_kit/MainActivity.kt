package com.xincy.rescue_auth_kit

import android.content.ContentValues
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream

class MainActivity : FlutterActivity() {
    private val backupChannel = "com.xincy.rescue_auth_kit/backups"
    private val storagePermissionRequestCode = 1842
    private val backupRelativePath = "Download/RescueAuthKit/Backups"
    private val legacyBackupPath = "RescueAuthKit/Backups"
    private var pendingLegacyBackup: PendingLegacyBackup? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            backupChannel,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "writeBackupFile" -> {
                    try {
                        val fileName = call.argument<String>("fileName")
                        val bytes = call.argument<ByteArray>("bytes")
                        val retentionPrefix = call.argument<String>("retentionPrefix")
                        val keep = call.argument<Int>("keep") ?: 5

                        if (fileName.isNullOrBlank() || bytes == null || retentionPrefix.isNullOrBlank()) {
                            result.error("invalid_args", "Missing backup file arguments.", null)
                            return@setMethodCallHandler
                        }

                        if (needsLegacyStoragePermission()) {
                            pendingLegacyBackup = PendingLegacyBackup(
                                fileName = fileName,
                                bytes = bytes,
                                retentionPrefix = retentionPrefix,
                                keep = keep,
                                result = result,
                            )
                            requestPermissions(
                                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                                storagePermissionRequestCode,
                            )
                            return@setMethodCallHandler
                        }

                        val path = writeBackupFile(fileName, bytes)
                        val cleanupFailureCount = pruneBackups(retentionPrefix, keep)
                        result.success(
                            mapOf(
                                "path" to path,
                                "cleanupFailureCount" to cleanupFailureCount,
                            ),
                        )
                    } catch (e: Exception) {
                        result.error("backup_failed", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != storagePermissionRequestCode) return

        val pending = pendingLegacyBackup ?: return
        pendingLegacyBackup = null

        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            pending.result.error(
                "permission_denied",
                "Storage permission is required to write backups to Downloads.",
                null,
            )
            return
        }

        try {
            val path = writeBackupFile(pending.fileName, pending.bytes)
            val cleanupFailureCount = pruneBackups(pending.retentionPrefix, pending.keep)
            pending.result.success(
                mapOf(
                    "path" to path,
                    "cleanupFailureCount" to cleanupFailureCount,
                ),
            )
        } catch (e: Exception) {
            pending.result.error("backup_failed", e.message, null)
        }
    }

    private fun writeBackupFile(fileName: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return writeLegacyBackupFile(fileName, bytes)
        }

        val resolver = applicationContext.contentResolver
        val collection = backupCollectionUri()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, backupRelativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Unable to create backup file.")

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(bytes)
                stream.flush()
            } ?: throw IllegalStateException("Unable to open backup output stream.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finished = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, finished, null, null)
            }
            return "$backupRelativePath/$fileName"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun writeLegacyBackupFile(fileName: String, bytes: ByteArray): String {
        val directory = legacyBackupDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create backup directory.")
        }

        val file = File(directory, fileName)
        FileOutputStream(file).use { stream ->
            stream.write(bytes)
            stream.flush()
        }
        return "Downloads/$legacyBackupPath/$fileName"
    }

    private fun pruneBackups(retentionPrefix: String, keep: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return pruneLegacyBackups(retentionPrefix, keep)
        }

        val resolver = applicationContext.contentResolver
        val collection = backupCollectionUri()
        val entries = mutableListOf<Pair<Uri, String>>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
        )
        val selection =
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$backupRelativePath/", "$retentionPrefix%.rakvault")

        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                entries.add(ContentUris.withAppendedId(collection, id) to name)
            }
        }

        entries.sortByDescending { it.second }
        var failures = 0
        entries.drop(keep).forEach { (uri, _) ->
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
                failures++
            }
        }

        return failures
    }

    private fun pruneLegacyBackups(retentionPrefix: String, keep: Int): Int {
        val directory = legacyBackupDirectory()
        val files = directory.listFiles { file ->
            file.isFile &&
                file.name.startsWith(retentionPrefix) &&
                file.name.endsWith(".rakvault")
        }?.toMutableList() ?: return 0

        files.sortByDescending { it.name }
        var failures = 0
        files.drop(keep).forEach { file ->
            if (!file.delete()) failures++
        }
        return failures
    }

    private fun backupCollectionUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }
    }

    private fun legacyBackupDirectory(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            legacyBackupPath,
        )
    }

    private fun needsLegacyStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT in Build.VERSION_CODES.M until Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
    }

    private data class PendingLegacyBackup(
        val fileName: String,
        val bytes: ByteArray,
        val retentionPrefix: String,
        val keep: Int,
        val result: MethodChannel.Result,
    )
}
