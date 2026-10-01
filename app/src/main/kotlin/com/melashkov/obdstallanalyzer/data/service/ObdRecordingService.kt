package com.melashkov.obdstallanalyzer.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.melashkov.obdstallanalyzer.MainActivity
import com.melashkov.obdstallanalyzer.R
import com.melashkov.obdstallanalyzer.data.bluetooth.BluetoothObdRepository
import com.melashkov.obdstallanalyzer.domain.capture.StallEventRecorder
import com.melashkov.obdstallanalyzer.domain.repository.ObdDevice
import com.melashkov.obdstallanalyzer.domain.repository.ObdEvent
import com.melashkov.obdstallanalyzer.domain.session.CapturePhase
import com.melashkov.obdstallanalyzer.domain.session.ObdSessionController
import com.melashkov.obdstallanalyzer.domain.session.ObdSessionState
import com.melashkov.obdstallanalyzer.domain.session.SessionStatusKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ObdRecordingService : Service(), ObdSessionController {
    inner class LocalBinder : Binder() {
        val controller: ObdSessionController get() = this@ObdRecordingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ObdSessionState())
    override val state: StateFlow<ObdSessionState> = mutableState.asStateFlow()

    private lateinit var repository: BluetoothObdRepository
    private var recordingJob: Job? = null
    private var captureUntilMs = 0L
    private var captureReady = false

    override val isAvailable: Boolean get() = repository.isAvailable
    override val isEnabled: Boolean get() = repository.isEnabled

    override fun onCreate() {
        super.onCreate()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        repository = BluetoothObdRepository(adapter)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                if (deviceId == null) {
                    stopRecording("No Bluetooth adapter was selected.", SessionStatusKind.ERROR)
                } else {
                    startRecording(deviceId)
                }
            }
            ACTION_STOP -> stopRecording("Disconnected", SessionStatusKind.IDLE)
        }
        return START_NOT_STICKY
    }

    override fun pairedDevices(): List<ObdDevice> = repository.pairedDevices()
    override fun diagnosticLog(): String = repository.diagnosticLog()
    override fun analysisData(): String = repository.analysisData()

    private fun startRecording(deviceId: String) {
        promoteToForeground("Connecting to OBD adapter…")
        recordingJob?.cancel()
        repository.disconnect()
        captureUntilMs = 0L
        captureReady = false
        mutableState.value = ObdSessionState(
            status = "Connecting to OBD adapter…",
            statusKind = SessionStatusKind.WORKING,
            sessionActive = true,
        )
        recordingJob = serviceScope.launch {
            repository.observe(deviceId).collect(::handleEvent)
        }
    }

    private fun handleEvent(event: ObdEvent) {
        when (event) {
            is ObdEvent.Status -> {
                mutableState.update {
                    it.copy(status = event.message, statusKind = SessionStatusKind.WORKING)
                }
                updateNotification(event.message)
            }
            is ObdEvent.Connected -> {
                val status = "Recording · ${event.deviceName}"
                mutableState.update {
                    it.copy(status = status, statusKind = SessionStatusKind.CONNECTED)
                }
                updateNotification(status)
            }
            is ObdEvent.SampleReceived -> updateSample(event.sample)
            is ObdEvent.Failed -> {
                mutableState.update {
                    it.copy(
                        status = event.message,
                        statusKind = SessionStatusKind.ERROR,
                        sessionActive = false,
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ObdEvent.Disconnected -> {
                if (mutableState.value.sessionActive) {
                    mutableState.update {
                        it.copy(
                            status = "Disconnected",
                            statusKind = SessionStatusKind.IDLE,
                            sessionActive = false,
                        )
                    }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun updateSample(sample: com.melashkov.obdstallanalyzer.domain.model.ObdSample) {
        val previousPhase = mutableState.value.capturePhase
        val phase: CapturePhase
        var secondsRemaining = 0L
        when {
            sample.event == StallEventRecorder.STALL_EVENT -> {
                captureUntilMs = sample.timestampMs + POST_STALL_CAPTURE_MS
                captureReady = false
                phase = CapturePhase.CAPTURING
                secondsRemaining = 10L
            }
            captureUntilMs > 0L && sample.timestampMs < captureUntilMs -> {
                phase = CapturePhase.CAPTURING
                secondsRemaining = (captureUntilMs - sample.timestampMs + 999L) / 1_000L
            }
            captureUntilMs > 0L -> {
                captureUntilMs = 0L
                captureReady = true
                phase = CapturePhase.READY
            }
            captureReady -> phase = CapturePhase.READY
            sample.rpm >= 700.0f -> phase = CapturePhase.ARMED
            else -> phase = CapturePhase.WAITING
        }
        mutableState.update {
            it.copy(
                sample = sample,
                sessionActive = true,
                capturePhase = phase,
                captureSecondsRemaining = secondsRemaining,
            )
        }
        if (phase != previousPhase) updateNotification(notificationText(phase, secondsRemaining))
    }

    private fun stopRecording(message: String, kind: SessionStatusKind) {
        recordingJob?.cancel()
        recordingJob = null
        repository.disconnect()
        captureUntilMs = 0L
        captureReady = false
        mutableState.value = ObdSessionState(status = message, statusKind = kind)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (!mutableState.value.sessionActive) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(text),
        )
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ObdRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OBD Stall Analyzer")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop recording", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OBD recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when OBD stall recording is active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificationText(phase: CapturePhase, secondsRemaining: Long): String = when (phase) {
        CapturePhase.WAITING -> "Connected · waiting for engine start"
        CapturePhase.ARMED -> "Armed · retaining the previous 60 seconds"
        CapturePhase.CAPTURING -> "Stall detected · $secondsRemaining s remaining"
        CapturePhase.READY -> "Capture ready"
    }

    override fun onDestroy() {
        recordingJob?.cancel()
        repository.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.melashkov.obdstallanalyzer.action.CONNECT"
        const val ACTION_STOP = "com.melashkov.obdstallanalyzer.action.STOP"
        const val EXTRA_DEVICE_ID = "device_id"
        private const val CHANNEL_ID = "obd_recording"
        private const val NOTIFICATION_ID = 41
        private const val POST_STALL_CAPTURE_MS = 10_000L
    }
}
