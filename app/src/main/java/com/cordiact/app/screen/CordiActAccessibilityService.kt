package com.cordiact.app.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The only accessibility service CordiAct declares.
 *
 * It deliberately does very little: it publishes itself to [ScreenServiceBridge]
 * and keeps a last-package hint. All tree walking and actions happen in
 * [MainScreenDriver] on the tool worker thread, never on the service's main
 * thread, so a slow page cannot trigger an ANR against the service.
 */
class CordiActAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        ScreenServiceBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        ScreenServiceBridge.notePackage(event?.packageName?.toString())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ScreenServiceBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ScreenServiceBridge.detach(this)
        super.onDestroy()
    }
}
