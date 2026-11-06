package com.cordiact.app.display

import android.os.Bundle
import com.cordiact.core.automation.ScreenElement
import com.cordiact.core.automation.ScreenError
import com.cordiact.core.automation.ScreenRect
import com.cordiact.core.automation.ScreenSnapshot

/**
 * Transport for a [ScreenSnapshot] across the AIDL boundary between the app
 * process and the privileged (Shizuku shell) process.
 *
 * Elements go over as flat Bundles: fingerprint strings are what node taps and
 * typing use later, so they must survive the hop verbatim.
 */
object SnapshotBundle {

    private const val KEY_PACKAGE = "package"
    private const val KEY_TITLE = "title"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_CAPTURED = "capturedAt"
    private const val KEY_TRUNCATED = "truncated"
    private const val KEY_ERROR_CODE = "errorCode"
    private const val KEY_ERROR_MESSAGE = "errorMessage"
    private const val KEY_ERROR_HINT = "errorHint"
    private const val KEY_ELEMENTS = "elements"

    private const val KEY_INDEX = "i"
    private const val KEY_FINGERPRINT = "f"
    private const val KEY_ROLE = "r"
    private const val KEY_LABEL = "l"
    private const val KEY_LEFT = "x1"
    private const val KEY_TOP = "y1"
    private const val KEY_RIGHT = "x2"
    private const val KEY_BOTTOM = "y2"
    private const val KEY_CLICKABLE = "c"
    private const val KEY_EDITABLE = "e"
    private const val KEY_SCROLLABLE = "s"
    private const val KEY_CHECKABLE = "k"
    private const val KEY_CHECKED = "k2"
    private const val KEY_ENABLED = "en"
    private const val KEY_PASSWORD = "p"
    private const val KEY_SENSITIVE = "se"

    fun encode(snapshot: ScreenSnapshot): Bundle = Bundle().apply {
        putString(KEY_PACKAGE, snapshot.packageName)
        putString(KEY_TITLE, snapshot.title)
        putInt(KEY_WIDTH, snapshot.width)
        putInt(KEY_HEIGHT, snapshot.height)
        putLong(KEY_CAPTURED, snapshot.capturedAtMillis)
        putBoolean(KEY_TRUNCATED, snapshot.truncated)
        snapshot.error?.let { error ->
            putString(KEY_ERROR_CODE, error.code)
            putString(KEY_ERROR_MESSAGE, error.message)
            putString(KEY_ERROR_HINT, error.recovery)
        }
        putParcelableArrayList(
            KEY_ELEMENTS,
            ArrayList(snapshot.elements.map(::encodeElement)),
        )
    }

    fun decode(handle: String, bundle: Bundle): ScreenSnapshot {
        val code = bundle.getString(KEY_ERROR_CODE)
        val error = code?.let {
            ScreenError(
                code = it,
                message = bundle.getString(KEY_ERROR_MESSAGE).orEmpty(),
                recovery = bundle.getString(KEY_ERROR_HINT).orEmpty(),
            )
        }
        @Suppress("DEPRECATION")
        val raw = bundle.getParcelableArrayList<Bundle>(KEY_ELEMENTS).orEmpty()
        val elements = raw.mapNotNull(::decodeElement)
        return ScreenSnapshot(
            handle = handle,
            packageName = bundle.getString(KEY_PACKAGE).orEmpty(),
            title = bundle.getString(KEY_TITLE),
            width = bundle.getInt(KEY_WIDTH),
            height = bundle.getInt(KEY_HEIGHT),
            elements = elements,
            capturedAtMillis = bundle.getLong(KEY_CAPTURED).takeIf { it > 0 } ?: System.currentTimeMillis(),
            truncated = bundle.getBoolean(KEY_TRUNCATED),
            error = error,
        )
    }

    private fun encodeElement(element: ScreenElement): Bundle = Bundle().apply {
        putInt(KEY_INDEX, element.index)
        putString(KEY_FINGERPRINT, element.fingerprint)
        putString(KEY_ROLE, element.role)
        putString(KEY_LABEL, element.label)
        putInt(KEY_LEFT, element.bounds.left)
        putInt(KEY_TOP, element.bounds.top)
        putInt(KEY_RIGHT, element.bounds.right)
        putInt(KEY_BOTTOM, element.bounds.bottom)
        putBoolean(KEY_CLICKABLE, element.clickable)
        putBoolean(KEY_EDITABLE, element.editable)
        putBoolean(KEY_SCROLLABLE, element.scrollable)
        putBoolean(KEY_CHECKABLE, element.checkable)
        putBoolean(KEY_CHECKED, element.checked)
        putBoolean(KEY_ENABLED, element.enabled)
        putBoolean(KEY_PASSWORD, element.password)
        putBoolean(KEY_SENSITIVE, element.sensitive)
    }

    private fun decodeElement(bundle: Bundle): ScreenElement? {
        val fingerprint = bundle.getString(KEY_FINGERPRINT)
        val role = bundle.getString(KEY_ROLE)
        if (fingerprint == null || role == null) return null
        return ScreenElement(
            index = bundle.getInt(KEY_INDEX),
            fingerprint = fingerprint,
            role = role,
            label = bundle.getString(KEY_LABEL).orEmpty(),
            bounds = ScreenRect(
                left = bundle.getInt(KEY_LEFT),
                top = bundle.getInt(KEY_TOP),
                right = bundle.getInt(KEY_RIGHT),
                bottom = bundle.getInt(KEY_BOTTOM),
            ),
            clickable = bundle.getBoolean(KEY_CLICKABLE),
            editable = bundle.getBoolean(KEY_EDITABLE),
            scrollable = bundle.getBoolean(KEY_SCROLLABLE),
            checkable = bundle.getBoolean(KEY_CHECKABLE),
            checked = bundle.getBoolean(KEY_CHECKED),
            enabled = bundle.getBoolean(KEY_ENABLED, true),
            password = bundle.getBoolean(KEY_PASSWORD),
            sensitive = bundle.getBoolean(KEY_SENSITIVE),
        )
    }
}
