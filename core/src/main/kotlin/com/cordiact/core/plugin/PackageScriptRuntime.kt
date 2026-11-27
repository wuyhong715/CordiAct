package com.cordiact.core.plugin

import com.cordiact.core.tool.ToolManagerException
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException

/** Host adapter for isolated evaluation. Each session belongs to one live package registration. */
fun interface PackageScriptRuntime {
    fun open(owner: PluginInstanceId): PackageScriptSession
}

interface PackageScriptSession : AutoCloseable {
    fun evaluate(source: ByteArray, input: ByteArray): ByteArray
}

const val PACKAGE_SCRIPT_MAX_SOURCE_BYTES = 16 * 1024
const val PACKAGE_SCRIPT_MAX_TEXT_BYTES = 4 * 1024

internal fun encodeScriptInput(text: String): ByteArray {
    if (text.length > PACKAGE_SCRIPT_MAX_TEXT_BYTES) invalidScriptInput()
    val encoded = try {
        Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text))
    } catch (_: CharacterCodingException) {
        invalidScriptInput()
    }
    if (encoded.remaining() > PACKAGE_SCRIPT_MAX_TEXT_BYTES) invalidScriptInput()
    return ByteArray(encoded.remaining()).also { encoded.get(it) }
}

private fun invalidScriptInput(): Nothing = throw ToolManagerException(
    ToolManagerException.Code.INVALID_ARGUMENT, "Script input must be valid UTF-8 within $PACKAGE_SCRIPT_MAX_TEXT_BYTES bytes",
)
