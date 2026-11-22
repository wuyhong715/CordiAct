package com.cordiact.core.plugin

import com.cordiact.core.tool.ToolManagerException
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.util.Locale

internal const val AUTHORED_TRANSFORM_MAX_BYTES = 4 * 1024

/** Host-implemented pure operation: it receives only the input and a closed operation enum. */
internal fun transformText(operation: AuthoredTextOperation, input: String): String {
    checkTextBytes(input, "Text input")
    val result = when (operation) {
        AuthoredTextOperation.TRIM -> input.trim()
        AuthoredTextOperation.UPPERCASE -> input.uppercase(Locale.ROOT)
        AuthoredTextOperation.LOWERCASE -> input.lowercase(Locale.ROOT)
    }
    checkTextBytes(result, "Text output")
    return result
}

/**
 * Public boundary for host adapters that publish bounded text tools (authored plugins and
 * declarative packages). The operation set stays closed; callers cannot supply code or a locale.
 */
fun applyBoundedTextTransform(operation: AuthoredTextOperation, input: String): String =
    transformText(operation, input)

/** Public bound for the same adapters, so their tool schema and error text agree. */
const val BOUNDED_TEXT_TRANSFORM_MAX_BYTES = AUTHORED_TRANSFORM_MAX_BYTES


private fun checkTextBytes(text: String, label: String) {
    // Every UTF-16 code unit costs at least one UTF-8 byte in a well-formed string. Check this
    // lower bound first to cap the encoder's work. Its default REPORT mode rejects lone surrogates.
    if (text.length > AUTHORED_TRANSFORM_MAX_BYTES) invalidTransform("$label exceeds $AUTHORED_TRANSFORM_MAX_BYTES UTF-8 bytes")
    val bytes = try {
        Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text)).remaining()
    } catch (_: CharacterCodingException) {
        invalidTransform("$label is not valid Unicode text")
    }
    if (bytes > AUTHORED_TRANSFORM_MAX_BYTES) invalidTransform("$label exceeds $AUTHORED_TRANSFORM_MAX_BYTES UTF-8 bytes")
}

internal fun invalidTransform(message: String): Nothing =
    throw ToolManagerException(ToolManagerException.Code.INVALID_ARGUMENT, message)
