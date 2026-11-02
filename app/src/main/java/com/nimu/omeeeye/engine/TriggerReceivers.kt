package com.nimu.omeeeye.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import com.nimu.omeeeye.data.HideStore
import com.nimu.omeeeye.data.TriggerStore
import java.util.Calendar

/**
 * Broadcast receivers for Triggers (feature 1) and Hide Mode unhide
 * (feature 2). Every receiver is wrapped in try/catch: a trigger or
 * unhide event must never crash the app, and a receiver must never
 * claim success it did not achieve.
 *
 * Manifest entries for these receivers are NOT in this file — the
 * coordinator adds them (see the handover report). Until then, the
 * receivers below do not fire.
 */
class TimeTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val now = Calendar.getInstance()
            val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 +
                now.get(Calendar.MINUTE)
            val triggers = TriggerStore.loadEnabled(context)
                .filter { it.kind == TriggerStore.TriggerKind.TIME }
            for (trigger in triggers) {
                if (trigger.timeMinutes < 0) continue
                // Match the current minute, ±1 minute, wrapping at
                // midnight, so a slightly late alarm still fires.
                val rawDiff = kotlin.math.abs(nowMinutes - trigger.timeMinutes)
                val diff = minOf(rawDiff, 1440 - rawDiff)
                if (diff <= 1) {
                    TriggerEngine.runWorkflowForTrigger(context, trigger)
                }
            }
            // Time triggers repeat daily: schedule tomorrow's alarms.
            // (A stale alarm from a since-deleted/disabled trigger
            // fires once, matches nothing, and is not rescheduled.)
            TriggerEngine.rescheduleTimeTriggers(context)
        } catch (e: Exception) {
            // Never crash on an alarm broadcast.
        }
    }
}

class SmsTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != "android.provider.Telephony.SMS_RECEIVED") return
            val bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format")
            val body = StringBuilder()
            for (pdu in pdus) {
                val bytes = pdu as? ByteArray ?: continue
                val message = SmsMessage.createFromPdu(bytes, format)
                body.append(message?.messageBody ?: "")
            }
            val text = body.toString()
            if (text.isBlank()) return
            // SMS workflow triggers: body contains the keyword.
            val triggers = TriggerStore.loadEnabled(context)
                .filter { it.kind == TriggerStore.TriggerKind.SMS }
            for (trigger in triggers) {
                if (trigger.smsKeyword.isNotBlank() &&
                    text.contains(trigger.smsKeyword, ignoreCase = true)
                ) {
                    TriggerEngine.runWorkflowForTrigger(context, trigger)
                }
            }
            // SMS unhide: body contains the secret keyword, and the
            // app is currently hidden. Never aborts the broadcast:
            // other messaging apps still receive the SMS.
            val hideKeyword = HideStore.get(context).smsKeyword
            if (hideKeyword.isNotBlank() &&
                text.contains(hideKeyword, ignoreCase = true)
            ) {
                TriggerEngine.triggerUnhide(context)
            }
        } catch (e: Exception) {
            // Never crash on an SMS broadcast.
        }
    }
}

class OutgoingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != Intent.ACTION_NEW_OUTGOING_CALL) return
            val dialed = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                ?.trim() ?: return
            val dialerCode = HideStore.get(context).dialerCode.trim()
            if (dialerCode.isBlank() || dialed != dialerCode) return
            // Cancel the call so the secret code is never actually
            // dialed, then unhide. This is an ordered broadcast, so
            // setResultData(null) is the documented way to cancel it.
            //
            // HONEST LIMIT: on Android 10+ this broadcast is
            // restricted and may not reach a normal app at all, so
            // dialer-code unhide may simply not fire on newer phones.
            // It is never claimed to work on all versions, and the
            // other unhide triggers (volume, shake, SMS, time) are
            // the fallback. This receiver never crashes either way.
            setResultData(null)
            TriggerEngine.triggerUnhide(context)
        } catch (e: Exception) {
            // Restricted broadcast, missing permission, or anything
            // else: never crash, never claim the unhide happened.
        }
    }
}

class BootTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
            // Alarms do not survive a reboot, so both the service
            // (screen-off / shake / battery watchers) and the daily
            // alarms are recreated here.
            TriggerEngine.startIfNeeded(context)
            TriggerEngine.rescheduleTimeTriggers(context)
        } catch (e: Exception) {
            // Never crash on boot.
        }
    }
}

/** Fires when the auto-unhide alarm goes off. One-shot; no reschedule. */
class AutoUnhideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            TriggerEngine.triggerUnhide(context)
        } catch (e: Exception) {
            // Never crash on an alarm broadcast.
        }
    }
}
