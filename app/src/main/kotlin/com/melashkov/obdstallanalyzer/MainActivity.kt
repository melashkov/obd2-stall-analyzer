package com.melashkov.obdstallanalyzer

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.melashkov.obdstallanalyzer.data.bluetooth.BluetoothObdRepository
import com.melashkov.obdstallanalyzer.presentation.StallAnalyzerApp
import com.melashkov.obdstallanalyzer.presentation.StallAnalyzerViewModel
import com.melashkov.obdstallanalyzer.presentation.UiEffect

class MainActivity : ComponentActivity() {
    private val viewModel: StallAnalyzerViewModel by viewModels {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        StallAnalyzerViewModel.Factory(BluetoothObdRepository(adapter))
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        viewModel.onBluetoothPermissionResult(results.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            is UiEffect.Share -> share(effect.text)
            is UiEffect.Copy -> copy(effect.text)
        }
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
