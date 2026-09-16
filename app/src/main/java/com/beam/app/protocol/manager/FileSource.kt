package com.beam.app.protocol.manager

import com.beam.app.protocol.transfer.FileVerifier
import java.io.InputStream

fun interface FileSource {
    fun open(): InputStream
}

fun FileSource.sha256(): String = open().use { FileVerifier.hash(it) }
