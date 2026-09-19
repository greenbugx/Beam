package com.beam.app.session

import android.content.ContentResolver
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
        val row = queryProviderRow(contentResolver, uri)
        return BeamSelectedFile(
            id = UUID.randomUUID().toString(),
            uri = uri,
            name = resolveName(uri, mimeType, row?.name),
            mimeType = mimeType,
            sizeBytes = row?.sizeBytes ?: measureOpenDescriptor(contentResolver, uri),
        )
    }

    /** Name and size from a single provider query. */
    private class ProviderRow(
        val name: String?,
        val sizeBytes: Long?,
    )

    private fun resolveName(
        uri: Uri,
        mimeType: String?,
        displayName: String?,
    ): String {
        val raw =
            displayName
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

    /**
     * Reads DISPLAY_NAME and SIZE in one query, positioned on the first row.
     * Null when the provider fails or returns no usable row.
     */
    private fun queryProviderRow(
        contentResolver: ContentResolver,
        uri: Uri,
    ): ProviderRow? =
        runCatching {
            contentResolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null

                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val name =
                        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                            cursor.getString(nameColumn)?.takeIf { it.isNotBlank() }
                        } else {
                            null
                        }

                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val sizeBytes =
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                            cursor.getLong(sizeColumn).takeIf { it >= 0 }
                        } else {
                            null
                        }

                    ProviderRow(name, sizeBytes)
                }
        }.getOrNull()

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
