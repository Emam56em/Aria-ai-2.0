package com.example.data.voice.alwayson

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dedicated foreground service (`foregroundServiceType="microphone"`) for user-authorized
 * Always-On Voice wake-word monitoring.
 *
 * - Requires explicit user activation and RECORD_AUDIO permission.
 * - Maintains a persistent notification with immediate Pause/Resume and Stop controls.
 * - Observes screen-off/screen-on broadcasts and audio recording interruptions safely.
 * - Handles Android 14+/15+ foreground service restrictions gracefully without bypassing OS rules.
 */
class VoiceMonitoringService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audioSessionManager: AudioSessionManager
    private lateinit var notificationManager: NotificationManager

    private var screenReceiver: BroadcastReceiver? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    override fun onCreate() {
        super.onCreate()
        val container = AppContainer.getInstance(applicationContext)
        audioSessionManager = container.audioSessionManager
        notificationManager = getSystemService(NotificationManager::class.java)

        createNotificationChannel()
        audioSessionManager.onScreenStateChanged(BatteryOptimizationHelper.isDeviceInteractive(this))
        registerScreenReceiver()
        registerAudioRecordingMonitor()

        serviceScope.launch {
            audioSessionManager.state.collectLatest { state ->
                if (state.isServiceRunning) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            audioSessionManager.stopMonitoring()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            audioSessionManager.stopWithPermissionDenied()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        val currentState = audioSessionManager.state.value
        val startedForeground = enterMicrophoneForeground(buildNotification(currentState))
        if (!startedForeground) {
            audioSessionManager.reportServiceStartBlocked(
                reasonEn = "Android prevented starting microphone monitoring from the background. Open Aria to restart.",
                reasonBn = "অ্যান্ড্রয়েড ব্যাকগ্রাউন্ড থেকে মাইক্রোফোন মনিটরিং চালু হতে দেয়নি। পুনরায় চালু করতে আরিয়া অ্যাপ খুলুন।"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_PAUSE -> audioSessionManager.pauseMonitoring()
            ACTION_RESUME, ACTION_START, ACTION_RESTART -> {
                audioSessionManager.startMonitoring(serviceRunning = true)
            }
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout triggered by Android (fgsType=$fgsType)")
        audioSessionManager.reportServiceStartBlocked(
            reasonEn = "Monitoring stopped due to Android foreground service time limit. Tap Restart to resume.",
            reasonBn = "অ্যান্ড্রয়েডের ফোরগ্রাউন্ড সময়সীমার কারণে মনিটরিং থামানো হয়েছে। আবার চালু করতে রিস্টার্ট চাপুন।"
        )
        stopForegroundAndSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterScreenReceiver()
        unregisterAudioRecordingMonitor()
        if (audioSessionManager.state.value.isServiceRunning) {
            audioSessionManager.stopMonitoring()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun enterMicrophoneForeground(notification: Notification): Boolean {
        return try {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting microphone FGS", e)
            false
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "ForegroundServiceStartNotAllowedException starting microphone FGS", e)
                false
            } else {
                Log.e(TAG, "Unexpected error starting microphone FGS", e)
                false
            }
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.voice_channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: VoiceMonitorState): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceMonitoringService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPaused = state.status == VoiceMonitorStatus.PAUSED
        val toggleActionIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceMonitoringService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (state.status) {
            VoiceMonitorStatus.PAUSED -> getString(R.string.voice_notif_paused_title)
            VoiceMonitorStatus.INTERRUPTED -> getString(R.string.voice_notif_interrupted_title)
            VoiceMonitorStatus.WAKE_DETECTED,
            VoiceMonitorStatus.CAPTURING_COMMAND,
            VoiceMonitorStatus.AWAITING_CONFIRMATION,
            VoiceMonitorStatus.THINKING,
            VoiceMonitorStatus.SPEAKING -> getString(R.string.voice_notif_active_turn_title)
            else -> getString(R.string.voice_notif_listening_title, state.wakeWord)
        }

        val toggleLabel = if (isPaused) {
            getString(R.string.voice_notif_resume)
        } else {
            getString(R.string.voice_notif_pause)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(state.statusMessageEn)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent)
            .addAction(0, toggleLabel, toggleActionIntent)
            .addAction(0, getString(R.string.voice_notif_stop), stopPendingIntent)
            .build()
    }

    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> audioSessionManager.onScreenStateChanged(false)
                    Intent.ACTION_SCREEN_ON -> audioSessionManager.onScreenStateChanged(true)
                }
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        screenReceiver = null
    }

    private fun registerAudioRecordingMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val am = getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val activeList = configs.orEmpty()
                if (audioSessionManager.isPhoneCallOrCommActive()) {
                    audioSessionManager.onAudioInterruptionStarted()
                } else if (activeList.isEmpty() &&
                    audioSessionManager.state.value.status == VoiceMonitorStatus.INTERRUPTED
                ) {
                    audioSessionManager.onAudioInterruptionEnded()
                }
            }
        }
        recordingCallback = callback
        runCatching {
            am.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    private fun unregisterAudioRecordingMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val am = getSystemService(AudioManager::class.java) ?: return
        recordingCallback?.let { cb ->
            runCatching { am.unregisterAudioRecordingCallback(cb) }
        }
        recordingCallback = null
    }

    companion object {
        private const val TAG = "VoiceMonitoringService"
        const val CHANNEL_ID = "aria_voice_monitoring"
        const val NOTIFICATION_ID = 2042

        const val ACTION_START = "com.example.action.VOICE_MONITOR_START"
        const val ACTION_PAUSE = "com.example.action.VOICE_MONITOR_PAUSE"
        const val ACTION_RESUME = "com.example.action.VOICE_MONITOR_RESUME"
        const val ACTION_STOP = "com.example.action.VOICE_MONITOR_STOP"
        const val ACTION_RESTART = "com.example.action.VOICE_MONITOR_RESTART"

        fun start(context: Context): Boolean = sendServiceCommand(context, ACTION_START)
        fun pause(context: Context): Boolean = sendServiceCommand(context, ACTION_PAUSE)
        fun resume(context: Context): Boolean = sendServiceCommand(context, ACTION_RESUME)
        fun restart(context: Context): Boolean = sendServiceCommand(context, ACTION_RESTART)

        fun stop(context: Context) {
            val container = AppContainer.getInstance(context.applicationContext)
            container.audioSessionManager.stopMonitoring()
            runCatching {
                context.stopService(Intent(context, VoiceMonitoringService::class.java))
            }
        }

        private fun sendServiceCommand(context: Context, commandAction: String): Boolean {
            val appCtx = context.applicationContext
            val sessionManager = AppContainer.getInstance(appCtx).audioSessionManager
            sessionManager.refreshEnvironmentState()

            if (commandAction != ACTION_STOP && !sessionManager.hasMicrophonePermission()) {
                sessionManager.stopWithPermissionDenied()
                return false
            }

            val intent = Intent(appCtx, VoiceMonitoringService::class.java).apply {
                action = commandAction
            }
            return try {
                ContextCompat.startForegroundService(appCtx, intent)
                true
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException starting VoiceMonitoringService", e)
                sessionManager.reportServiceStartBlocked(
                    reasonEn = "Android blocked microphone foreground service start without active permission.",
                    reasonBn = "সক্রিয় অনুমতি ছাড়া অ্যান্ড্রয়েড মাইক্রোফোন ফোরগ্রাউন্ড সার্ভিস চালু করতে দেয়নি।"
                )
                false
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    Log.e(TAG, "ForegroundServiceStartNotAllowedException", e)
                    sessionManager.reportServiceStartBlocked(
                        reasonEn = "Android 14+ prevents starting microphone services from background. Tap Start while Aria is open.",
                        reasonBn = "Android 14+ নীতিমালার কারণে ব্যাকগ্রাউন্ড থেকে মাইক্রোফোন সার্ভিস শুরু করা যায়নি। অ্যাপ খুলে আবার শুরু করুন।"
                    )
                } else {
                    Log.e(TAG, "Failed to start VoiceMonitoringService", e)
                    sessionManager.reportServiceStartBlocked(
                        reasonEn = e.localizedMessage ?: "Unable to start voice monitoring service.",
                        reasonBn = e.localizedMessage ?: "ভয়েস মনিটরিং সার্ভিস চালু করা যায়নি।"
                    )
                }
                false
            }
        }
    }
}
