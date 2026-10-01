package com.melashkov.obdstallanalyzer.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import com.melashkov.obdstallanalyzer.domain.repository.ObdDevice
import com.melashkov.obdstallanalyzer.presentation.theme.Action
import com.melashkov.obdstallanalyzer.presentation.theme.Blue
import com.melashkov.obdstallanalyzer.presentation.theme.Muted
import com.melashkov.obdstallanalyzer.presentation.theme.Navy
import com.melashkov.obdstallanalyzer.presentation.theme.Panel
import com.melashkov.obdstallanalyzer.presentation.theme.PanelBorder
import com.melashkov.obdstallanalyzer.presentation.theme.PanelDark
import com.melashkov.obdstallanalyzer.presentation.theme.Purple
import com.melashkov.obdstallanalyzer.presentation.theme.Red
import com.melashkov.obdstallanalyzer.presentation.theme.ShareAction
import com.melashkov.obdstallanalyzer.presentation.theme.StallAnalyzerTheme
import com.melashkov.obdstallanalyzer.presentation.theme.Teal
import com.melashkov.obdstallanalyzer.presentation.theme.Yellow
import java.util.Locale

@Composable
internal fun StallAnalyzerApp(
    state: DashboardUiState,
    onConnect: () -> Unit,
    onToggleDemo: () -> Unit,
    onDiagnostics: () -> Unit,
    onShare: () -> Unit,
    onDeviceSelected: (ObdDevice) -> Unit,
    onDismissDevices: () -> Unit,
    onDismissDiagnostics: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var showSafetyDialog by rememberSaveable { mutableStateOf(true) }

    StallAnalyzerTheme {
        Dashboard(
            state = state,
            onConnect = onConnect,
            onToggleDemo = onToggleDemo,
            onDiagnostics = onDiagnostics,
            onShare = onShare,
        )

        state.pairedDevices?.let { devices ->
            DevicePicker(devices, onDeviceSelected, onDismissDevices)
        }
        state.diagnostics?.let { diagnostics ->
            DiagnosticsDialog(
                diagnostics = diagnostics,
                onDismiss = onDismissDiagnostics,
                onCopy = onCopyDiagnostics,
                onShare = onShare,
            )
        }
        state.message?.let { message ->
            AlertDialog(
                onDismissRequest = onDismissMessage,
                title = { Text("OBD Stall Analyzer") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } },
            )
        }
        if (showSafetyDialog) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Drive safely") },
                text = {
                    Text(
                        "Do not operate this app while driving. Set up the adapter and start " +
                            "recording while parked. If the app needs attention, pull over safely " +
                            "or ask a passenger to operate it.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showSafetyDialog = false }) {
                        Text("I understand")
                    }
                },
            )
        }
    }
}

@Composable
private fun Dashboard(
    state: DashboardUiState,
    onConnect: () -> Unit,
    onToggleDemo: () -> Unit,
    onDiagnostics: () -> Unit,
    onShare: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
        DashboardContent(state, onConnect, onToggleDemo, onDiagnostics, onShare)
    }
}

@Composable
private fun DashboardContent(
    state: DashboardUiState,
    onConnect: () -> Unit,
    onToggleDemo: () -> Unit,
    onDiagnostics: () -> Unit,
    onShare: () -> Unit,
) {
    val sample = state.sample
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        ) {
            Text(
                "OBD Stall Analyzer",
                color = Teal,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Text(
                        "⋮",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 28.sp,
                        modifier = Modifier.semantics { contentDescription = "More options" },
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (state.sessionActive) "Disconnect" else "Connect adapter") },
                        onClick = {
                            menuExpanded = false
                            onConnect()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.demoRunning) "Stop demo" else "Demo") },
                        onClick = {
                            menuExpanded = false
                            onToggleDemo()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Diagnostics") },
                        onClick = {
                            menuExpanded = false
                            onDiagnostics()
                        },
                    )
                }
            }
        }

        StatusPanel(state.status, state.statusTone)
        Spacer(Modifier.height(14.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        ) {
            MetricCard(
                label = "ENGINE SPEED",
                value = format(sample?.rpm, "%.0f rpm"),
                detail = "Recorder arms above 700 rpm",
                accent = Teal,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = MetricCardMinHeight),
            )
            MetricCard(
                label = "ECU VOLTAGE",
                value = format(sample?.ecuVoltage, "%.3f V"),
                detail = "Adapter ${format(sample?.adapterVoltage, "%.2f V")}",
                accent = Blue,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = MetricCardMinHeight),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        ) {
            MetricCard(
                label = "THROTTLE",
                value = format(sample?.throttlePercent, "%.1f %%"),
                detail = "Relative ${format(sample?.relativeThrottlePercent, "%.1f %%")} · " +
                    "accelerator ${format(sample?.acceleratorPercent, "%.1f %%")}",
                accent = Yellow,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = MetricCardMinHeight),
            )
            MetricCard(
                label = "MANIFOLD PRESSURE",
                value = format(sample?.mapKpa, "%.0f kPa"),
                detail = "Barometric ${format(sample?.barometricKpa, "%.0f kPa")}",
                accent = Purple,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = MetricCardMinHeight),
            )
        }
        Spacer(Modifier.height(10.dp))

        Surface(
            color = Panel,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, PanelBorder),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                MetricRow("SHORT-TERM FUEL TRIM", format(sample?.shortTermFuelTrim, "%+.1f %%"))
                MetricRow(
                    "OXYGEN SENSORS",
                    "O₂ 1 ${format(sample?.oxygen1Voltage, "%.2f V")} · " +
                        "O₂ 2 ${format(sample?.oxygen2Voltage, "%.2f V")}",
                )
                MetricRow(
                    "TEMPERATURES",
                    "Coolant ${format(sample?.coolantC, "%.0f °C")} · " +
                        "Intake ${format(sample?.intakeAirC, "%.0f °C")}",
                )
                MetricRow(
                    "IGNITION / PURGE",
                    "Timing ${format(sample?.ignitionTiming, "%+.1f°")} · " +
                        "Purge ${format(sample?.purgePercent, "%.0f %%")}",
                )
                MetricRow("FUEL SYSTEM", sample?.fuelSystemStatus ?: "Waiting for ECU")
            }
        }

        Text(
            state.captureStatus,
            color = toneColor(state.captureTone),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
        )

        AppButton(
            "Share recording to ChatGPT / AI",
            onShare,
            Modifier.fillMaxWidth(),
            containerColor = ShareAction,
        )

        Text(
            "Pair a Bluetooth Classic ELM-compatible adapter first, connect with ignition ON, " +
                "then start the engine. The analyzer retains only the latest 60 seconds until RPM " +
                "drops below 300, marks STALL_DETECTED, and records 10 seconds afterward. Review " +
                "the data before explicitly sharing it with an AI. Set up while parked. Do not " +
                "operate the app while driving; pull over safely or ask a passenger.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
        )
        Text(
            "STANDARD OBD-II READS ONLY\nNo programming, adaptation, reset, actuator or fault-clear commands",
            color = Teal,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatusPanel(message: String, tone: UiTone) {
    Surface(
        color = PanelDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, PanelBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Surface(color = toneColor(tone), shape = RoundedCornerShape(50), modifier = Modifier.size(8.dp)) {}
            Text(
                message,
                color = toneColor(tone),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, detail: String, accent: Color, modifier: Modifier) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent),
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
    ) {
        Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f),
        )
    }
}

@Composable
private fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    containerColor: Color = Action,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = Color.White),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.heightIn(min = 50.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DevicePicker(
    devices: List<ObdDevice>,
    onDeviceSelected: (ObdDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose paired adapter") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                devices.forEach { device ->
                    TextButton(onClick = { onDeviceSelected(device) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(device.name, color = Color.White)
                            Text(device.id, color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DiagnosticsDialog(
    diagnostics: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OBD stall analyzer diagnostics") },
        text = {
            Text(
                diagnostics,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Analyze with AI") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

private fun toneColor(tone: UiTone): Color = when (tone) {
    UiTone.MUTED -> Muted
    UiTone.ACCENT -> Teal
    UiTone.WARNING -> Yellow
    UiTone.ERROR -> Red
}

private fun format(value: Float?, pattern: String): String =
    if (value == null || value.isNaN()) "—" else String.format(Locale.US, pattern, value)

private val MetricCardMinHeight = 130.dp
