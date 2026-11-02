package com.nimu.omeeeye.engine

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.HideStore
import com.nimu.omeeeye.data.TriggerStore
import com.nimu.omeeeye.data.WorkflowStore
import java.util.Calendar
import kotlin.math.sqrt

/**
 * Trigger engine (feature 1) + Hide Mode watchers (feature 2).
 *
 * A low-importance foreground service, so Android does not kill the
 * watchers while the app is in the background. Its notification is
 * silent and low priority, but it WILL appear in the notification
 * shade — the Hide screen says so honestly instead of promising
 * invisibility.
 *
 * What it watches, and when each watcher is registered:
 * - ACTION_SCREEN_OFF: always, while the service runs. This is the
 *   phone-lock = FULL emergency stop: the running workflow is stopped
 *   with no resume, and the phone vibrates briefly as the stop signal.
 *   A screen-off caused by the app itself (SCREEN_OFF step) is
 *   ignored: AutomationService marks it in intentionalScreenOffAtMs,
 *   and any screen-off within 5 seconds of that mark is skipped.
 * - Battery level: only while at least one BATTERY trigger is enabled.
 *   Fires once per downward crossing of the threshold; going back
 *   above the threshold re-arms it.
 * - Accelerometer shake: only while a SHAKE trigger is enabled or
 *   shake-unhide is enabled. The detection threshold is the most
 *   sensitive (lowest) threshold among the enabled SHAKE triggers,
 *   or Normal when only unhide uses shake. Debounced to one event
 *   per 1500 ms.
 * - Volume Up,Up,Down: via AutomationService.volumeComboListener,
 *   which only fires while the accessibility service is running.
 *   That is why volume unhide honestly requires the accessibility
 *   service enabled (stated on the Hide screen).
 *
 * Time triggers and auto-unhide do NOT need this service to stay
 * awake: they are AlarmManager alarms that fire their receivers, which
 * then call back here. Alarms are re-created on boot (see
 * BootTriggerReceiver) because alarms do not survive a reboot.
 *
 * No coroutines (plain Service + receivers, per the project contract).
 * Nothing here ever crashes on a missing service or workflow: when
 * the accessibility service is not enabled, [runWorkflowForTrigger]
 * does nothing — the Triggers screen warns about this, and this
 * service never pretends a run happened.
 */
class TriggerEngine : Service() {

    companion object {
        private const val CHANNEL_ID = "omeeeye_triggers"
        private const val NOTIF_ID = 2001
        private const val SHAKE_DEBOUNCE_MS = 1500L
        private const val INTENTIONAL_SCREEN_OFF_WINDOW_MS = 5000L

        // Shake thresholds on g-force above rest (magnitude / 9.81).
        // Sensitivity 1 = Gentle (fires on a light shake),
        // 2 = Normal, 3 = Firm (needs a strong shake).
        private fun shakeThreshold(sensitivity: Int): Float = when (sensitivity) {
            1 -> 1.6f
            3 -> 3.0f
            else -> 2.2f
        }

        /**
         * Starts the service (and re-schedules time + auto-unhide
         * alarms) whenever anything needs watching: enabled triggers,
         * armed unhide triggers, or simply after boot / after the user
         * edited Triggers or Hide settings. Starting an already-running
         * service is harmless: onStartCommand just re-evaluates which
         * watchers are needed. The service is started unconditionally
         * (not only when a trigger exists) so the phone-lock emergency
         * stop watcher is active after boot even with zero triggers.
         */
        fun startIfNeeded(context: Context) {
            try {
                val intent = Intent(context, TriggerEngine::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Foreground-service start can be refused from the
                // background on newer Android; alarms and receivers
                // still work, and the next UI visit retries the start.
            }
            rescheduleTimeTriggers(context)
            rescheduleAutoUnhide(context)
        }

        /** One exact alarm per enabled TIME trigger, for its next occurrence. */
        fun rescheduleTimeTriggers(context: Context) {
            val triggers = try {
                TriggerStore.loadEnabled(context)
                    .filter { it.kind == TriggerStore.TriggerKind.TIME }
            } catch (e: Exception) {
                emptyList()
            }
            for (trigger in triggers) {
                if (trigger.timeMinutes < 0) continue
                scheduleDailyAlarm(
                    context, trigger.id, trigger.timeMinutes,
                    TimeTriggerReceiver::class.java
                )
            }
        }

        /** One exact alarm for auto-unhide, or cancels it when off. */
        fun rescheduleAutoUnhide(context: Context) {
            val minutes = try {
                HideStore.get(context).autoUnhideMinutes
            } catch (e: Exception) {
                -1
            }
            if (minutes < 0) {
                cancelAutoUnhideAlarm(context)
                return
            }
            scheduleDailyAlarm(
                context, "auto_unhide", minutes,
                AutoUnhideReceiver::class.java
            )
        }

        private fun scheduleDailyAlarm(
            context: Context,
            key: String,
            timeMinutes: Int,
            receiverClass: Class<*>
        ) {
            try {
                val now = Calendar.getInstance()
                val target = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, timeMinutes / 60)
                    set(Calendar.MINUTE, timeMinutes % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now.timeInMillis) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }
                val intent = Intent(context, receiverClass)
                val pending = PendingIntent.getBroadcast(
                    context,
                    key.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        target.timeInMillis,
                        pending
                    )
                } catch (e: SecurityException) {
                    // Exact-alarm permission not granted (Android 12+):
                    // fall back to an inexact alarm rather than failing.
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        target.timeInMillis,
                        pending
                    )
                }
            } catch (e: Exception) {
                // A failed alarm schedules nothing; the trigger simply
                // does not fire until the next reschedule. Never crash.
            }
        }

        private fun cancelAutoUnhideAlarm(context: Context) {
            try {
                val intent = Intent(context, AutoUnhideReceiver::class.java)
                val pending = PendingIntent.getBroadcast(
                    context,
                    "auto_unhide".hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(pending)
            } catch (e: Exception) {
                // Nothing to cancel, or no alarm service: ignore.
            }
        }

        /**
         * Runs the workflow mapped to [trigger]. Does nothing (never
         * crashes, never fakes a run) when the accessibility service
         * is not enabled, when the workflow no longer exists, or when
         * another run is already active (WorkflowRunner.start is
         * ignored while a run is active — existing behaviour, kept).
         */
        fun runWorkflowForTrigger(context: Context, trigger: TriggerStore.TriggerConfig) {
            try {
                if (AutomationService.instance == null) return
                val workflow = WorkflowStore.get(context, trigger.workflowId)
                    ?: return
                WorkflowRunner.init(context)
                WorkflowRunner.start(workflow)
            } catch (e: Exception) {
                // A trigger must never crash the service or receiver.
            }
        }

        /**
         * Unhides the app, but only when it is actually hidden, so a
         * stray shake / volume / SMS event never pops the app open
         * while it is already visible.
         */
        fun triggerUnhide(context: Context) {
            try {
                if (HideManager.isHidden(context)) {
                    HideManager.unhide(context)
                }
            } catch (e: Exception) {
                // Unhide is best-effort from a background event.
            }
        }
    }

    // ------------------------------------------------------------------
    // Service lifecycle + watchers
    // ------------------------------------------------------------------

    private var screenOffReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var sensorManager: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private var volumeListener: (() -> Unit)? = null

    // Battery crossing state: trigger ids already fired at/below their
    // threshold. Cleared per trigger when the level rises above it.
    private val batteryFired = mutableSetOf<String>()
    private var batteryBaselineSeen = false

    private var lastShakeMs = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            // If foreground start fails here, onStartCommand retries
            // nothing: the service still registers its receivers below
            // and the system may stop it; the next startIfNeeded call
            // retries. Never crash on create.
        }
        registerScreenOff()
        volumeListener = {
            onVolumeCombo()
        }
        AutomationService.volumeComboListener = volumeListener
        refreshWatchers()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            // Already foreground, or start refused: keep going.
        }
        refreshWatchers()
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiverSafe(screenOffReceiver)
            screenOffReceiver = null
        } catch (e: Exception) {
            // Ignore cleanup failures.
        }
        try {
            unregisterReceiverSafe(batteryReceiver)
            batteryReceiver = null
        } catch (e: Exception) {
            // Ignore cleanup failures.
        }
        try {
            shakeListener?.let { sensorManager?.unregisterListener(it) }
        } catch (e: Exception) {
            // Ignore cleanup failures.
        }
        shakeListener = null
        if (AutomationService.volumeComboListener == volumeListener) {
            AutomationService.volumeComboListener = null
        }
        volumeListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun unregisterReceiverSafe(receiver: BroadcastReceiver?) {
        if (receiver == null) return
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Was not registered: nothing to undo.
        }
    }

    /**
     * Dynamic-receiver registration with an explicit exported flag
     * on Android 13+ (targetSdk is 34, and the flag is required
     * there). RECEIVER_NOT_EXPORTED still receives system
     * broadcasts (SCREEN_OFF, BATTERY_CHANGED) — it only blocks
     * broadcasts from other apps. On older Android the plain
     * two-argument call is used.
     */
    private fun registerReceiverCompat(
        receiver: BroadcastReceiver,
        filter: IntentFilter
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    /**
     * (Re-)registers the battery and shake watchers to match the
     * current settings. Called on create and on every start, so
     * editing Triggers or Hide settings takes effect without the user
     * restarting anything by hand.
     */
    private fun refreshWatchers() {
        val enabledTriggers = try {
            TriggerStore.loadEnabled(this)
        } catch (e: Exception) {
            emptyList()
        }
        val hide = try {
            HideStore.get(this)
        } catch (e: Exception) {
            HideStore.HideSettings()
        }

        // Battery: only while a BATTERY trigger is enabled.
        val needsBattery = enabledTriggers.any {
            it.kind == TriggerStore.TriggerKind.BATTERY
        }
        if (needsBattery && batteryReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    onBatteryChanged(intent)
                }
            }
            batteryReceiver = receiver
            try {
                registerReceiverCompat(
                    receiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
            } catch (e: Exception) {
                batteryReceiver = null
            }
        } else if (!needsBattery && batteryReceiver != null) {
            unregisterReceiverSafe(batteryReceiver)
            batteryReceiver = null
            batteryFired.clear()
            batteryBaselineSeen = false
        }

        // Shake: while a SHAKE trigger is enabled or shake-unhide is on.
        val needsShake = enabledTriggers.any {
            it.kind == TriggerStore.TriggerKind.SHAKE
        } || hide.shakeEnabled
        if (needsShake && shakeListener == null) {
            val manager =
                getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sensor != null) {
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        onAccelerometer(event)
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int
                    ) {
                    }
                }
                try {
                    manager.registerListener(
                        listener,
                        sensor,
                        SensorManager.SENSOR_DELAY_UI
                    )
                    sensorManager = manager
                    shakeListener = listener
                } catch (e: Exception) {
                    shakeListener = null
                }
            }
        } else if (!needsShake && shakeListener != null) {
            try {
                sensorManager?.unregisterListener(shakeListener)
            } catch (e: Exception) {
                // Ignore cleanup failures.
            }
            shakeListener = null
        }
    }

    private fun registerScreenOff() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                onScreenOff()
            }
        }
        screenOffReceiver = receiver
        try {
            registerReceiverCompat(
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF)
            )
        } catch (e: Exception) {
            screenOffReceiver = null
        }
    }

    // ------------------------------------------------------------------
    // Watcher callbacks
    // ------------------------------------------------------------------

    /**
     * Phone-lock = FULL emergency stop, no resume. A screen-off that
     * the app itself caused (SCREEN_OFF step) is skipped when it
     * happened within the last 5 seconds. The vibration is the stop
     * signal and is only sent when a run was actually stopped, so a
     * normal lock with nothing running does not buzz the phone.
     */
    private fun onScreenOff() {
        try {
            val sinceIntentional = SystemClock.elapsedRealtime() -
                AutomationService.intentionalScreenOffAtMs
            if (sinceIntentional < INTENTIONAL_SCREEN_OFF_WINDOW_MS) return
            val wasRunning = WorkflowRunner.isRunning()
            WorkflowRunner.stop()
            if (wasRunning) {
                AutomationService.instance?.vibrate(200)
            }
        } catch (e: Exception) {
            // Emergency stop must never crash the service.
        }
    }

    private fun onBatteryChanged(intent: Intent?) {
        if (intent == null) return
        try {
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", 100)
            if (level < 0 || scale <= 0) return
            val pct = (level * 100) / scale
            if (!batteryBaselineSeen) {
                // First (sticky) broadcast only sets the baseline:
                // registering must never fire a trigger by itself.
                batteryBaselineSeen = true
                return
            }
            val triggers = TriggerStore.loadEnabled(this)
                .filter { it.kind == TriggerStore.TriggerKind.BATTERY }
            for (trigger in triggers) {
                if (pct <= trigger.batteryPct) {
                    if (batteryFired.add(trigger.id)) {
                        runWorkflowForTrigger(this, trigger)
                    }
                } else {
                    batteryFired.remove(trigger.id)
                }
            }
        } catch (e: Exception) {
            // Battery watcher must never crash the service.
        }
    }

    private fun onAccelerometer(event: SensorEvent?) {
        if (event == null || event.values.size < 3) return
        try {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            val enabledTriggers = TriggerStore.loadEnabled(this)
                .filter { it.kind == TriggerStore.TriggerKind.SHAKE }
            val hide = HideStore.get(this)
            // Most sensitive threshold among the enabled SHAKE
            // triggers, or Normal when only unhide uses shake.
            val threshold = enabledTriggers
                .minOfOrNull { shakeThreshold(it.shakeSensitivity) }
                ?: shakeThreshold(2)
            if (gForce <= threshold) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastShakeMs < SHAKE_DEBOUNCE_MS) return
            lastShakeMs = now
            for (trigger in enabledTriggers) {
                runWorkflowForTrigger(this, trigger)
            }
            if (hide.shakeEnabled) {
                triggerUnhide(this)
            }
        } catch (e: Exception) {
            // Shake watcher must never crash the service.
        }
    }

    private fun onVolumeCombo() {
        try {
            val hide = HideStore.get(this)
            if (hide.volumeEnabled) {
                triggerUnhide(this)
            }
            // Note: there is no VOLUME workflow-trigger kind in the
            // approved trigger set (TIME / SMS / NOTIFICATION / SHAKE /
            // BATTERY), so the volume combo only unhides. It never
            // starts a workflow on its own.
        } catch (e: Exception) {
            // Volume watcher must never crash the service.
        }
    }

    // ------------------------------------------------------------------
    // Foreground notification (silent, low importance)
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.trigger_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.trigger_channel_desc)
                    setSound(null, null)
                    enableVibration(false)
                }
                val manager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            } catch (e: Exception) {
                // Channel creation failure: notification still builds
                // below; the service start may then fail and retry.
            }
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_nav_triggers)
            .setContentTitle(getString(R.string.trigger_service_title))
            .setContentText(getString(R.string.trigger_service_text))
            .setOngoing(true)
            .build()
    }
}
