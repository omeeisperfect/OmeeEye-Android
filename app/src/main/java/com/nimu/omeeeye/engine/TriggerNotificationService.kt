package com.nimu.omeeeye.engine

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nimu.omeeeye.data.TriggerStore

/**
 * Notification triggers (feature 1): when a watched app posts a
 * notification, run the mapped workflow.
 *
 * The user enables this in Settings (notification-listener access);
 * until then nothing here fires. Own-package notifications are
 * ignored so the app's own foreground notification can never trigger
 * a workflow loop. Everything is guarded: a bad notification must
 * never crash the listener.
 *
 * Manifest entry (BIND_NOTIFICATION_LISTENER_SERVICE service) is
 * added by the coordinator, not here.
 */
class TriggerNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            // Ignore our own notifications (including the trigger
            // engine's foreground notification).
            if (sbn.packageName == packageName) return
            val triggers = TriggerStore.loadEnabled(this)
                .filter { it.kind == TriggerStore.TriggerKind.NOTIFICATION }
            for (trigger in triggers) {
                if (trigger.notifPackage.isNotBlank() &&
                    trigger.notifPackage == sbn.packageName
                ) {
                    TriggerEngine.runWorkflowForTrigger(this, trigger)
                }
            }
        } catch (e: Exception) {
            // A notification must never crash the listener.
        }
    }
}
