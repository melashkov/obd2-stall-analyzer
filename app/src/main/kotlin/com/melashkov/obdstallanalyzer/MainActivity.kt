package com.melashkov.obdstallanalyzer

import android.Manifest
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.melashkov.obdstallanalyzer.data.service.ObdRecordingService
import com.melashkov.obdstallanalyzer.domain.session.ObdSessionController
import com.melashkov.obdstallanalyzer.presentation.StallAnalyzerApp
import com.melashkov.obdstallanalyzer.presentation.StallAnalyzerViewModel
import com.melashkov.obdstallanalyzer.presentation.UiEffect

class MainActivity : ComponentActivity() {
    private val viewModel: StallAnalyzerViewModel by viewModels { StallAnalyzerViewModel.Factory() }
    private var sessionController: ObdSessionController? = null
    private var serviceBindingActive = false
    private var pendingRecordingDeviceId: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val controller = (binder as ObdRecordingService.LocalBinder).controller
            sessionController = controller
            viewModel.attachController(controller)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.detachController(sessionController)
            sessionController = null
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        viewModel.onBluetoothPermissionResult(results.values.all { it })
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingRecordingDeviceId?.let(::startRecordingService)
        pendingRecordingDeviceId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsState()

            LaunchedEffect(viewModel) {
                viewModel.effects.collect(::handleEffect)
            }

            StallAnalyzerApp(
                state = state,
                onConnect = { viewModel.onConnectClicked(hasBluetoothPermissions()) },
                onToggleDemo = viewModel::toggleDemo,
                onDiagnostics = viewModel::showDiagnostics,
                onShare = viewModel::shareAnalysis,
                onDeviceSelected = viewModel::connect,
                onDismissDevices = viewModel::dismissDevicePicker,
                onDismissDiagnostics = viewModel::dismissDiagnostics,
                onCopyDiagnostics = viewModel::copyDiagnostics,
                onDismissMessage = viewModel::dismissMessage,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        serviceBindingActive = bindService(
            Intent(this, ObdRecordingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        if (serviceBindingActive) {
            viewModel.detachController(sessionController)
            unbindService(serviceConnection)
            sessionController = null
            serviceBindingActive = false
        }
        super.onStop()
    }

    private fun handleEffect(effect: UiEffect) {
        when (effect) {
            UiEffect.RequestBluetoothPermissions -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothPermissionLauncher.launch(
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                    )
                } else {
                    viewModel.onBluetoothPermissionResult(granted = true)
                }
            }
            UiEffect.OpenBluetoothSettings -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            is UiEffect.StartRecording -> requestRecordingStart(effect.deviceId)
            UiEffect.StopRecording -> stopRecordingService()
            is UiEffect.Share -> share(effect.text)
            is UiEffect.Copy -> copy(effect.text)
        }
    }

    private fun requestRecordingStart(deviceId: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingRecordingDeviceId = deviceId
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startRecordingService(deviceId)
        }
    }

    private fun startRecordingService(deviceId: String) {
        startForegroundService(
            Intent(this, ObdRecordingService::class.java)
                .setAction(ObdRecordingService.ACTION_CONNECT)
                .putExtra(ObdRecordingService.EXTRA_DEVICE_ID, deviceId),
        )
    }

    private fun stopRecordingService() {
        startService(
            Intent(this, ObdRecordingService::class.java)
                .setAction(ObdRecordingService.ACTION_STOP),
        )
    }

    private fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OBD stall diagnostic data")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Analyze stall data with"))
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OBD Stall Analyzer diagnostics", text))
        Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
}
