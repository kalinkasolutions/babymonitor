package ch.kalinka.babymonitor.media

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.PowerManager
import java.util.Timer
import java.util.TimerTask
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ch.kalinka.babymonitor.MainActivity
import ch.kalinka.babymonitor.R
import ch.kalinka.babymonitor.net.LightMode

/**
 * Keeps this phone able to film and listen with its screen off and the app out of sight.
 *
 * Android stops a background process using the camera or the microphone, so a phone on a shelf in
 * a nursery needs a foreground service of these two types for the capture to survive the screen
 * going off. The service holds nothing itself — the call lives above it — it exists to keep the
 * process in the foreground and to put a notification where the person can see the phone is on.
 *
 * It has to be started while the app is visible. From Android 14 a camera or microphone service
 * cannot be started from the background at all, which is exactly why the nursery phone is armed
 * by hand before the night rather than woken into filming from cold.
 */
class MonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            Log.i(Tag, "Stopped from the notification; this phone is off duty")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ActionAcknowledge) {
            // Somebody is awake and has seen it. That is the only thing the alarm was waiting for.
            Log.i(Tag, "Alarm acknowledged")
            silenceAlarm(this)
            return START_STICKY
        }

        Log.i(Tag, "On duty: holding the process up for the camera and the microphone")
        isRunning = true

        createChannel()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationId, notification(), type)
        } else {
            startForeground(NotificationId, notification())
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(Tag, "Off duty")
        isRunning = false
        stopAlarmSound()

        // The light is this phone's screen, and it must never outlive the reason it was on.
        LightActivity.dismiss(this)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitorService::class.java).setAction(ActionStop),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ChannelId)
            .setContentTitle("Babymonitor is on")
            .setContentText("This phone can be listened to and watched.")
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            ChannelId,
            "Monitoring",
            // Quiet: this notification says a thing is running, and a phone in a nursery
            // announcing itself all night is the opposite of what it is for.
            NotificationManager.IMPORTANCE_LOW
        )

        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    companion object {
        /** Whether the process is currently held up by this service. */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val Tag = "MonitorService"

        /** How long to hold the screen awake for a light with no end of its own. */
        private const val WakeSeconds = 30
        private const val ChannelId = "monitoring"
        private const val AlarmChannelId = "noise-alarm"
        private const val NotificationId = 1
        private const val AlarmNotificationId = 3
        private const val LostNotificationId = 4
        private const val LightRequestCode = 2
        private const val ActionStop = "ch.kalinka.babymonitor.STOP_MONITORING"
        private const val ActionAcknowledge = "ch.kalinka.babymonitor.ACKNOWLEDGE_ALARM"

        /** However asleep somebody is, an alarm ringing this long has not been heard. */
        private const val RingLimit = 10 * 60 * 1000L

        @Volatile
        private var player: MediaPlayer? = null

        @Volatile
        private var silence: Timer? = null

        /**
         * Must be called while the app is visible, and says so by failing rather than throwing
         * when it is not: from Android 12 starting a service from the background is an exception,
         * and from 14 a camera or microphone one is refused outright. Whether this phone is armed
         * is a decision somebody makes before putting it down, not one another phone makes for it.
         */
        fun arm(context: Context): Boolean = runCatching {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }.onFailure {
            // Android 14 refuses a camera or microphone service started from the background, and
            // this is the line that says a phone put down for the night never went on duty.
            Log.e(Tag, "Refused permission to go on duty", it)
        }.isSuccess

        /**
         * Drawing over other apps is what the system treats as consent to show a screen from the
         * background, and is the first of the two routes [light] takes to reach a locked phone.
         */
        fun canStartFromBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

        /**
         * Whether Android has been told to leave this app alone. Without it Doze and the standby
         * buckets suspend the process hours in — the monitor looks fine at bedtime and is dead
         * by the small hours, which is the failure nobody notices until it matters.
         */
        fun runsUnrestricted(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true

        /** Whether the alarm clock route is open: an alarm that fires may show a screen. */
        fun canScheduleExactAlarm(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

        fun disarm(context: Context) {
            Log.i(Tag, "Standing down")
            context.stopService(Intent(context, MonitorService::class.java))
        }

        /**
         * Wakes somebody, which is the one thing this app exists to do.
         *
         * On the alarm channel with an alarm's audio attributes, so it is heard through a phone
         * that has been silenced for the night — the volume people turn down is the ring volume,
         * and an alarm is deliberately not on it.
         */
        fun alarm(context: Context, room: String) {
            raise(
                context,
                id = AlarmNotificationId,
                title = "Noise in the room",
                text = "$room has been loud for a while."
            )
        }

        /** The room answered again, so the alarm about it going away has nothing left to say. */
        fun clearLostAlarm(context: Context) {
            silenceAlarm(context)
        }

        /**
         * Keeps sounding until somebody says they have seen it.
         *
         * A notification plays its tone once. That is right for a message and wrong for the one
         * event this app exists to report: an alarm nobody hears is an alarm that did not happen,
         * and the phone in the room does not raise a second one when it has simply gone.
         */
        private fun startAlarmSound(context: Context) {
            if (player != null) {
                return
            }

            val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return

            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context, tone)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()

            // A cap, because an alarm in an empty house should not ring until the battery is flat.
            // Long enough that sleeping through it is the only way to reach it.
            silence?.cancel()
            silence = Timer().also {
                it.schedule(
                    object : TimerTask() {
                        override fun run() = stopAlarmSound()
                    },
                    RingLimit
                )
            }
        }

        private fun stopAlarmSound() {
            silence?.cancel()
            silence = null
            runCatching { player?.stop() }
            player?.release()
            player = null
        }

        /** Stops the noise and takes the notification down with it. */
        fun silenceAlarm(context: Context) {
            stopAlarmSound()
            NotificationManagerCompat.from(context).cancel(AlarmNotificationId)
            NotificationManagerCompat.from(context).cancel(LostNotificationId)
        }

        private fun raise(context: Context, id: Int, title: String, text: String) {
            val channel = NotificationChannel(
                AlarmChannelId,
                "Noise alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
            }

            val notifications = NotificationManagerCompat.from(context)
            notifications.createNotificationChannel(channel)

            val open = PendingIntent.getActivity(
                context,
                3,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val acknowledge = PendingIntent.getService(
                context,
                4,
                Intent(context, MonitorService::class.java).setAction(ActionAcknowledge),
                PendingIntent.FLAG_IMMUTABLE
            )

            Log.w(Tag, "Raising an alarm: $title — $text")
            val notification = NotificationCompat.Builder(context, AlarmChannelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_monitor)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open)
                .addAction(0, "I'm awake", acknowledge)
                // Stays until acknowledged, and silent because the sound is played and looped
                // here rather than handed to the notification, which would play it once.
                .setOngoing(true)
                .setSilent(true)
                .build()

            runCatching { notifications.notify(id, notification) }
            startAlarmSound(context)
        }

        /**
         * The room has stopped answering. The important one: a monitor that has died is silent in
         * exactly the way a sleeping baby is, and nothing else will tell you which you have.
         */
        fun lostAlarm(context: Context, room: String) {
            raise(
                context,
                id = LostNotificationId,
                title = "Lost the room",
                text = "$room stopped answering. It may be off, asleep or out of signal."
            )
        }

        /**
         * Turns this phone's screen into a lamp, or puts it out.
         *
         * A locked phone will not let a background app start an activity — that exemption went
         * away in Android 10 — so there are two ways in: permission to draw over other apps, and
         * failing that the alarm clock route. When neither is open the room stays dark and the
         * phone watching is told why, which beats a notification nobody is awake to tap.
         */
        fun light(context: Context, mode: LightMode, brightness: Float, seconds: Int): Boolean {
            if (mode == LightMode.Off) {
                Log.i(Tag, "Putting the light out")
                LightActivity.dismiss(context)
                return true
            }

            // Already lit: just retune it. A slider being dragged must not wake the screen again
            // or set another alarm ten times a second — nor write a log line that often, which is
            // why only raising the light is recorded and not every adjustment to it.
            if (LightActivity.update(mode, brightness, seconds)) {
                return true
            }

            Log.i(Tag, "Raising the light: $mode at $brightness for ${seconds}s")

            // Turns a dark screen on by itself. Deprecated for a decade and still the only thing
            // that wakes a phone nobody is touching; without it the light lands on a screen that
            // is off, which is no light at all.
            wake(context, if (seconds > 0) seconds else WakeSeconds)

            if (canStartFromBackground(context)) {
                LightActivity.show(context, mode, brightness, seconds)
                return true
            }

            // Otherwise, do what an alarm clock does. An alarm that is going off is allowed to put
            // a screen in front of somebody — that is the whole point of one — and the exemption
            // is granted to the app whose alarm it is, for as long as it is firing.
            return raiseLikeAnAlarm(context, mode, brightness, seconds)
        }

        /**
         * The alarm clock route: the reason a phone that is face down and locked still shows you
         * an alarm going off. Needs an exact alarm, which is why this app asks for one.
         */
        private fun raiseLikeAnAlarm(
            context: Context,
            mode: LightMode,
            brightness: Float,
            seconds: Int
        ): Boolean {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
            if (!canScheduleExactAlarm(context)) {
                Log.w(Tag, "No exact alarms: the alarm clock route to the light is closed")
                return false
            }

            val show = PendingIntent.getActivity(
                context,
                LightRequestCode,
                LightActivity.intent(context, mode, brightness, seconds),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            return runCatching {
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis(), show), show)
            }.onFailure { Log.w(Tag, "The alarm clock route to the light was refused", it) }.isSuccess
        }

        @Suppress("DEPRECATION")
        private fun wake(context: Context, seconds: Int) {
            runCatching {
                context.getSystemService(PowerManager::class.java)
                    ?.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "babymonitor:light"
                    )
                    // Timed, and never held: released by the system when it runs out, so a light
                    // that goes wrong cannot pin the screen on all night.
                    ?.acquire(seconds.coerceAtLeast(1) * 1000L)
            }
        }
    }
}
