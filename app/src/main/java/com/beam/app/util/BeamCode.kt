package com.beam.app.util

import kotlin.random.Random

object BeamCode {
    const val CODE_LENGTH = 6

    private const val CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val ENDPOINT_NAME_PREFIX = "Beam · "

    fun generateCode(length: Int = CODE_LENGTH): String =
        buildString(length) {
            repeat(length) {
                append(CHARACTERS[Random.nextInt(CHARACTERS.length)])
            }
        }

    fun sanitize(raw: String): String =
        raw
            .uppercase()
            .filter { it in CHARACTERS }
            .take(CODE_LENGTH)

    fun isValid(code: String): Boolean = code.length == CODE_LENGTH && code.all { it in CHARACTERS }

    fun endpointNameForCode(code: String): String = ENDPOINT_NAME_PREFIX + code

    fun codeFromEndpointName(endpointName: String): String? {
        val code = sanitize(endpointName.removePrefix(ENDPOINT_NAME_PREFIX))

        return if (
            isValid(code) &&
            endpointName == endpointNameForCode(code)
        ) {
            code
        } else {
            null
        }
    }
}
