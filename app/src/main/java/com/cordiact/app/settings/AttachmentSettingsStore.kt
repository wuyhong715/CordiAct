package com.cordiact.app.settings

import android.content.Context
import com.cordiact.core.attachment.*

/** Missing data uses safe defaults; malformed stored values fail closed. */
class AttachmentSettingsStore(context: Context) : AttachmentPolicyProvider {
    private val prefs = context.getSharedPreferences("attachment_policy_v1", Context.MODE_PRIVATE)
    @Synchronized override fun limits(): AttachmentLimits = AttachmentPolicy.validate(AttachmentLimits(
        maxAttachmentsPerMessage = prefs.getInt("count", 10),
        maxImageBytes = prefs.getLong("imageBytes", 10L * 1024 * 1024),
        maxImagePixels = prefs.getLong("pixels", 20_000_000),
        maxImageEdge = prefs.getInt("edge", 8192),
        maxPdfBytes = prefs.getLong("pdfBytes", 10L * 1024 * 1024),
        maxPdfPages = prefs.getInt("pages", 100),
        maxTextBytes = prefs.getLong("textBytes", 256L * 1024),
        maxTextTotalBytes = prefs.getLong("textTotal", 512L * 1024),
    ))
    @Synchronized fun revision(): Long = prefs.getLong("revision", 0)
    @Synchronized fun save(value: AttachmentLimits) {
        AttachmentPolicy.validate(value)
        check(prefs.edit().putInt("count", value.maxAttachmentsPerMessage)
            .putLong("imageBytes", value.maxImageBytes).putLong("pixels", value.maxImagePixels)
            .putInt("edge", value.maxImageEdge).putLong("pdfBytes", value.maxPdfBytes)
            .putInt("pages", value.maxPdfPages).putLong("textBytes", value.maxTextBytes)
            .putLong("textTotal", value.maxTextTotalBytes).putLong("revision", revision() + 1).commit()) { "ATTACHMENT_POLICY_WRITE_FAILED" }
    }
}
