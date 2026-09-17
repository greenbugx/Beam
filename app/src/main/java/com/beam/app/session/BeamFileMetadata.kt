package com.beam.app.session

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.util.UUID

/**
 * Resolves display metadata for a document picked through the Storage
 * Access Framework.
 */
object BeamFileMetadata {
    const val UNKNOWN_SIZE = -1L

    fun read(
        contentResolver: ContentResolver,
        uri: Uri,
    ): BeamSelectedFile {
        val mimeType = readMimeType(contentResolver, uri)
        return BeamSelectedFile(
            id = UUID.randomUUID().toString(),
            uri = uri,
            name = readName(contentResolver, uri, mimeType),
            mimeType = mimeType,
            sizeBytes = readSizeBytes(contentResolver, uri),
        )
    }

    private fun readName(
        contentResolver: ContentResolver,
        uri: Uri,
        mimeType: String?,
    ): String {
        val raw =
            queryDisplayName(contentResolver, uri)
                ?: uri.lastPathSegment
                ?: "file"

        val stem = raw.trimEnd('.').ifBlank { "file" }
        val hasExtension = stem.substringAfterLast('.', "").isNotBlank()
        if (hasExtension) return raw

        return MimeTypeMap
            .getSingleton()
            .getExtensionFromMimeType(mimeType?.substringBefore(';'))
            ?.takeIf { it.isNotBlank() }
            ?.let { "$stem.$it" }
            ?: raw
    }

    private fun readMimeType(
        contentResolver: ContentResolver,
        uri: Uri,
    ): String? {
        val reportedType =
            runCatching { contentResolver.getType(uri) }.getOrNull()

        if (reportedType != null) {
            return reportedType
        }

        // Providers that do not report a MIME type still expose the
        // extension in the path, which maps to a MIME type locally.
        val extension =
            uri.lastPathSegment
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase()

        return extension
            ?.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    }

    private fun readSizeBytes(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Long = querySizeBytes(contentResolver, uri) ?: measureOpenDescriptor(contentResolver, uri)

    private fun queryDisplayName(
        contentResolver: ContentResolver,
        uri: Uri,
    ): String? =
        runCatching {
            queryRow(contentResolver, uri, OpenableColumns.DISPLAY_NAME)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                if (column >= 0 && !cursor.isNull(column)) {
                    cursor.getString(column)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }.getOrNull()

    private fun querySizeBytes(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Long? =
        runCatching {
            queryRow(contentResolver, uri, OpenableColumns.SIZE)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)

                if (column >= 0 && !cursor.isNull(column)) {
                    cursor.getLong(column).takeIf { it >= 0 }
                } else {
                    null
                }
            }
        }.getOrNull()

    private fun queryRow(
        contentResolver: ContentResolver,
        uri: Uri,
        column: String,
    ): Cursor? = contentResolver.query(uri, arrayOf(column), null, null, null)

    private fun measureOpenDescriptor(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Long =
        runCatching {
            contentResolver
                .openAssetFileDescriptor(uri, "r")
                ?.use { descriptor -> descriptor.length.takeIf { it >= 0 } }
        }.getOrNull() ?: UNKNOWN_SIZE
}
