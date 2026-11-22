package com.example.data.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional Android AccessibilityService for user-authorized UI interactions.
 * - Disabled by default; requires explicit manual activation by the user in Android Settings.
 * - Never bypasses lock screens, grants permissions, or reads password fields.
 */
class AriaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AriaAccessibilityBridge.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AriaAccessibilityBridge.onWindowPackageChanged(event.packageName?.toString())
        }
    }

    override fun onInterrupt() {
        // No background audio/overlay tasks to interrupt.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AriaAccessibilityBridge.onServiceDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AriaAccessibilityBridge.onServiceDisconnected()
        super.onDestroy()
    }
}
