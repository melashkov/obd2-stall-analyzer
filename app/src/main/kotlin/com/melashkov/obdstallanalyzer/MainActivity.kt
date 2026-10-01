package com.melashkov.obdstallanalyzer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.sin

@SuppressLint("SetTextI18n")
class MainActivity : Activity(), ElmBluetoothClient.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var client: ElmBluetoothClient? = null
    private var lastDiagnosticLog = "No connection has been attempted."
    private var lastAnalysisData = ""
    private var demoRunning = false
    private var demoStart = 0L
    private var captureUiUntil = 0L
    private var captureReady = false

    private lateinit var statusView: TextView
    private lateinit var rpmValue: TextView
    private lateinit var ecuVoltageValue: TextView
    private lateinit var adapterVoltageValue: TextView
    private lateinit var throttleValue: TextView
    private lateinit var throttleDetail: TextView
    private lateinit var mapValue: TextView
    private lateinit var mapDetail: TextView
    private lateinit var fuelTrimValue: TextView
    private lateinit var oxygenValue: TextView
    private lateinit var temperatureValue: TextView
    private lateinit var timingValue: TextView
    private lateinit var fuelStatusValue: TextView
    private lateinit var captureStatusView: TextView
    private lateinit var connectButton: Button
    private lateinit var demoButton: Button

    private val demoRunnable = object : Runnable {
        override fun run() {
            if (!demoRunning) return
            val seconds = (System.currentTimeMillis() - demoStart) / 1000.0f
            val rpm = 900.0f + (sin((seconds * 2.0f).toDouble()) * 35.0).toFloat()
            renderSample(
                ObdSample(
                    rpm = rpm,
                    mapKpa = 42.0f,
                    ecuVoltage = 14.18f,
                    adapterVoltage = 14.22f,
                    throttlePercent = 2.4f,
                    relativeThrottlePercent = 1.9f,
                    acceleratorPercent = 0.0f,
                    shortTermFuelTrim = 1.6f,
                    oxygen1Voltage = 0.72f,
                    oxygen1Trim = 1.6f,
                    oxygen2Voltage = 0.69f,
                    oxygen2Trim = 0.8f,
                    coolantC = 91.0f,
                    intakeAirC = 28.0f,
                    ignitionTiming = 8.5f,
                    purgePercent = 0.0f,
                    barometricKpa = 101.0f,
                    fuelSystemStatus = "Closed loop",
                    timestampMs = System.currentTimeMillis(),
                ),
            )
            mainHandler.postDelayed(this, 350L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        buildInterface()
    }

    private fun buildInterface() {
        val horizontal = dp(18f)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(7, 19, 30))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, dp(20f), horizontal, dp(24f))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(
                    horizontal + bars.left,
                    dp(20f) + bars.top,
                    horizontal + bars.right,
                    dp(24f) + bars.bottom,
                )
                insets
            }
        } else {
            root.fitsSystemWindows = true
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(text("GENERIC OBD-II • READ ONLY", 12f, color(66, 217, 200), true))
        root.addView(text("AI-ready Stall Analyzer", 34f, Color.WHITE, true).apply {
            setPadding(0, dp(2f), 0, 0)
        })
        root.addView(text("Cars and motorcycles with compatible OBD-II", 16f, color(169, 188, 201), false).apply {
            setPadding(0, 0, 0, dp(14f))
        })

        statusView = text("Not connected", 14f, color(169, 188, 201), true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = rounded(color(13, 33, 48), 12f, color(32, 66, 85))
        }
        root.addView(statusView, matchWrap())

        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(cards, matchWrap().apply { topMargin = dp(14f) })
        val rpmCard = createMetricCard(
            "ENGINE SPEED",
            "— rpm",
            "Recorder arms above 700 rpm",
            color(66, 217, 200),
        )
        rpmValue = rpmCard.getChildAt(1) as TextView
        cards.addView(rpmCard, weightedCard(0))

        val voltageCard = createMetricCard(
            "ECU VOLTAGE",
            "— V",
            "Adapter — V",
            color(99, 169, 255),
        )
        ecuVoltageValue = voltageCard.getChildAt(1) as TextView
        adapterVoltageValue = voltageCard.getChildAt(2) as TextView
        cards.addView(voltageCard, weightedCard(dp(10f)))

        val airCards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(airCards, matchWrap().apply { topMargin = dp(10f) })
        val throttleCard = createMetricCard(
            "THROTTLE",
            "— %",
            "Relative — · accelerator —",
            color(255, 200, 87),
        )
        throttleValue = throttleCard.getChildAt(1) as TextView
        throttleDetail = throttleCard.getChildAt(2) as TextView
        airCards.addView(throttleCard, weightedCard(0))

        val mapCard = createMetricCard(
            "MANIFOLD PRESSURE",
            "— kPa",
            "Barometric — kPa",
            color(191, 132, 255),
        )
        mapValue = mapCard.getChildAt(1) as TextView
        mapDetail = mapCard.getChildAt(2) as TextView
        airCards.addView(mapCard, weightedCard(dp(10f)))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = rounded(color(18, 43, 61), 14f, color(32, 66, 85))
        }
        root.addView(details, matchWrap().apply { topMargin = dp(10f) })
        fuelTrimValue = addMetricRow(details, "SHORT-TERM FUEL TRIM", "— %")
        oxygenValue = addMetricRow(details, "OXYGEN SENSORS", "O₂ 1 — V · O₂ 2 — V")
        temperatureValue = addMetricRow(details, "TEMPERATURES", "Coolant — °C · Intake — °C")
        timingValue = addMetricRow(details, "IGNITION / PURGE", "Timing —° · Purge — %")
        fuelStatusValue = addMetricRow(details, "FUEL SYSTEM", "Waiting for ECU")

        captureStatusView = text(
            "Waiting for engine start · retains 60 s before and 10 s after",
            13f,
            color(169, 188, 201),
            true,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        root.addView(captureStatusView, matchWrap().apply { topMargin = dp(10f) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(buttons, matchWrap().apply { topMargin = dp(16f) })
        connectButton = button("Connect adapter").apply { setOnClickListener { onConnectPressed() } }
        buttons.addView(connectButton, weightedButton(0))
        demoButton = button("Demo").apply { setOnClickListener { toggleDemo() } }
        buttons.addView(demoButton, weightedButton(dp(10f)))

        root.addView(button("Diagnostics").apply { setOnClickListener { showDiagnostics() } },
            matchWrap().apply { topMargin = dp(8f) })
        root.addView(
            button("Share recording to ChatGPT / AI").apply {
                setOnClickListener { shareWithAi() }
                backgroundTintList = ColorStateList.valueOf(color(31, 119, 104))
            },
            matchWrap().apply { topMargin = dp(8f) },
        )

        root.addView(text(
            "Pair a Bluetooth Classic ELM-compatible adapter first, connect with ignition ON, " +
                "then start the engine. The analyzer retains only the latest 60 seconds until RPM " +
                "drops below 300, marks STALL_DETECTED, and records 10 seconds afterward. Review " +
                "the data before explicitly sharing it with an AI. Do not operate the device while driving.",
            13f,
            color(169, 188, 201),
            false,
        ).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(18f), 0, dp(8f))
        })
        root.addView(text(
            "STANDARD OBD-II READS ONLY\nNo programming, adaptation, reset, actuator or fault-clear commands",
            11f,
            color(66, 217, 200),
            true,
        ).apply { gravity = Gravity.CENTER })

        setContentView(scroll)
    }

    private fun createMetricCard(label: String, initialValue: String, detail: String, accent: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = rounded(color(18, 43, 61), 14f, accent)
            addView(text(label, 11f, accent, true))
            addView(text(initialValue, 27f, Color.WHITE, true).apply {
                setPadding(0, dp(4f), 0, 0)
            })
            addView(text(detail, 12f, color(169, 188, 201), false))
        }

    private fun addMetricRow(parent: LinearLayout, label: String, initialValue: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7f), 0, dp(7f))
        }
        row.addView(
            text(label, 11f, color(169, 188, 201), true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val value = text(initialValue, 14f, Color.WHITE, true).apply { gravity = Gravity.END }
        row.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        parent.addView(row, matchWrap())
        return value
    }

    private fun onConnectPressed() {
        if (client != null) {
            disconnectClient("Disconnected")
            return
        }
        stopDemo()
        val adapter = bluetoothAdapter
        if (adapter == null) {
            showMessage("Bluetooth is not available on this device.")
            return
        }
        if (!hasBluetoothPermissions()) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                REQUEST_BLUETOOTH,
            )
            return
        }
        if (!adapter.isEnabled) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            showMessage("Enable Bluetooth and pair your adapter, then return here.")
            return
        }
        showPairedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevices() {
        val devices = bluetoothAdapter?.bondedDevices.orEmpty().sortedBy {
            (it.name ?: it.address).lowercase(Locale.US)
        }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No paired adapters")
                .setMessage("Pair a Bluetooth Classic ELM327, OBDLink or vLinker adapter in Android Bluetooth settings first.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Bluetooth settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .show()
            return
        }
        val labels = devices.map { "${it.name ?: "Bluetooth device"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose paired adapter")
            .setItems(labels) { _, which -> connectTo(devices[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        disconnectClient(null)
        clearReadings()
        val adapter = bluetoothAdapter ?: return
        status("Connecting to ${device.name ?: device.address}…", color(255, 200, 87))
        connectButton.text = "Disconnect"
        client = ElmBluetoothClient(adapter, device, this).also { it.connect() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH && hasBluetoothPermissions()) {
            showPairedDevices()
        } else if (requestCode == REQUEST_BLUETOOTH) {
            showMessage("Nearby-device Bluetooth scan and connect permissions are required.")
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    private fun toggleDemo() {
        if (demoRunning) {
            stopDemo()
            clearReadings()
            status("Not connected", color(169, 188, 201))
            return
        }
        disconnectClient(null)
        demoRunning = true
        demoStart = System.currentTimeMillis()
        demoButton.text = "Stop demo"
        status("Demo data — no ECU connection", color(255, 200, 87))
        mainHandler.post(demoRunnable)
    }

    private fun stopDemo() {
        demoRunning = false
        mainHandler.removeCallbacks(demoRunnable)
        if (::demoButton.isInitialized) demoButton.text = "Demo"
    }

    private fun renderSample(sample: ObdSample) {
        rpmValue.text = format(sample.rpm, "%.0f rpm")
        ecuVoltageValue.text = format(sample.ecuVoltage, "%.3f V")
        adapterVoltageValue.text = "Adapter ${format(sample.adapterVoltage, "%.2f V")}"
        throttleValue.text = format(sample.throttlePercent, "%.1f %%")
        throttleDetail.text = "Relative ${format(sample.relativeThrottlePercent, "%.1f %%")}" +
            " · accelerator ${format(sample.acceleratorPercent, "%.1f %%")}"
        mapValue.text = format(sample.mapKpa, "%.0f kPa")
        mapDetail.text = "Barometric ${format(sample.barometricKpa, "%.0f kPa")}"
        fuelTrimValue.text = format(sample.shortTermFuelTrim, "%+.1f %%")
        oxygenValue.text = "O₂ 1 ${format(sample.oxygen1Voltage, "%.2f V")}" +
            " · O₂ 2 ${format(sample.oxygen2Voltage, "%.2f V")}"
        temperatureValue.text = "Coolant ${format(sample.coolantC, "%.0f °C")}" +
            " · Intake ${format(sample.intakeAirC, "%.0f °C")}"
        timingValue.text = "Timing ${format(sample.ignitionTiming, "%+.1f°")}" +
            " · Purge ${format(sample.purgePercent, "%.0f %%")}"
        fuelStatusValue.text = sample.fuelSystemStatus

        when {
            sample.event == StallEventRecorder.STALL_EVENT -> {
                captureUiUntil = sample.timestampMs + 10_000L
                captureReady = false
                captureStatusView.text = "STALL_DETECTED · recording 10 seconds after event"
                captureStatusView.setTextColor(color(255, 107, 107))
            }
            captureUiUntil > 0L && sample.timestampMs < captureUiUntil -> {
                captureStatusView.text = "CAPTURING · ${((captureUiUntil - sample.timestampMs + 999) / 1000)} s remaining"
                captureStatusView.setTextColor(color(255, 200, 87))
            }
            captureUiUntil > 0L -> {
                captureUiUntil = 0L
                captureReady = true
                captureStatusView.text = "CAPTURE READY · share with ChatGPT / AI"
                captureStatusView.setTextColor(color(66, 217, 200))
            }
            captureReady -> {
                captureStatusView.text = "CAPTURE READY · share with ChatGPT / AI"
                captureStatusView.setTextColor(color(66, 217, 200))
            }
            sample.rpm >= 700.0f -> {
                captureStatusView.text = "ARMED · retaining the previous 60 seconds"
                captureStatusView.setTextColor(color(66, 217, 200))
            }
            else -> {
                captureStatusView.text = "Waiting for engine start · retains 60 s before and 10 s after"
                captureStatusView.setTextColor(color(169, 188, 201))
            }
        }
    }

    private fun clearReadings() {
        rpmValue.text = "— rpm"
        ecuVoltageValue.text = "— V"
        adapterVoltageValue.text = "Adapter — V"
        throttleValue.text = "— %"
        throttleDetail.text = "Relative — · accelerator —"
        mapValue.text = "— kPa"
        mapDetail.text = "Barometric — kPa"
        fuelTrimValue.text = "— %"
        oxygenValue.text = "O₂ 1 — V · O₂ 2 — V"
        temperatureValue.text = "Coolant — °C · Intake — °C"
        timingValue.text = "Timing —° · Purge — %"
        fuelStatusValue.text = "Waiting for ECU"
        captureStatusView.text = "Waiting for engine start · retains 60 s before and 10 s after"
        captureStatusView.setTextColor(color(169, 188, 201))
        captureUiUntil = 0L
        captureReady = false
    }

    private fun showDiagnostics() {
        val log = client?.getDiagnosticLog() ?: lastDiagnosticLog
        val logView = text(log, 11f, color(244, 248, 251), false).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("OBD stall analyzer diagnostics")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setNeutralButton("Copy", null)
            .setPositiveButton("Analyze with AI", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { copyLog(log) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { shareWithAi() }
        }
        dialog.show()
    }

    private fun copyLog(log: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OBD Stall Analyzer diagnostics", log))
        Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareWithAi() {
        val data = client?.getAnalysisData() ?: lastAnalysisData
        if (data.isBlank()) {
            showMessage("Connect to a vehicle and record some data before starting AI analysis.")
            return
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OBD stall diagnostic data")
            putExtra(Intent.EXTRA_TEXT, AiDiagnosticReport.build(data))
        }
        startActivity(Intent.createChooser(share, "Analyze stall data with"))
    }

    private fun disconnectClient(newStatus: String?) {
        val current = client
        client = null
        if (current != null) {
            lastDiagnosticLog = current.getDiagnosticLog()
            lastAnalysisData = current.getAnalysisData()
            current.disconnect()
        }
        if (::connectButton.isInitialized) connectButton.text = "Connect adapter"
        if (newStatus != null) {
            status(newStatus, color(169, 188, 201))
            clearReadings()
        }
    }

    override fun onStatus(message: String) {
        mainHandler.post { status(message, color(255, 200, 87)) }
    }

    override fun onConnected(deviceName: String) {
        mainHandler.post { status("Recording · $deviceName", color(66, 217, 200)) }
    }

    override fun onSample(sample: ObdSample) {
        mainHandler.post { renderSample(sample) }
    }

    override fun onError(message: String, error: Throwable) {
        mainHandler.post {
            client?.let {
                lastDiagnosticLog = it.getDiagnosticLog()
                lastAnalysisData = it.getAnalysisData()
            }
            status(message, color(255, 107, 107))
            connectButton.text = "Connect adapter"
            showMessage("$message\n\nOpen Diagnostics for the command log.")
        }
    }

    override fun onDisconnected() {
        mainHandler.post {
            val current = client
            if (current != null && current.isRunning) return@post
            current?.let {
                lastDiagnosticLog = it.getDiagnosticLog()
                lastAnalysisData = it.getAnalysisData()
            }
            client = null
            connectButton.text = "Connect adapter"
            if (!demoRunning && !statusView.text.toString().startsWith("Live data stopped")) {
                status("Disconnected", color(169, 188, 201))
            }
        }
    }

    private fun status(message: String, textColor: Int) {
        statusView.text = "●  $message"
        statusView.setTextColor(textColor)
    }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this)
            .setTitle("OBD Stall Analyzer")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        stopDemo()
        disconnectClient(null)
        super.onDestroy()
    }

    private fun text(value: String, sizeSp: Float, textColor: Int, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(textColor)
            typeface = Typeface.create("sans", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun button(label: String) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        backgroundTintList = ColorStateList.valueOf(color(24, 72, 91))
        minHeight = dp(50f)
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1f), stroke)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun weightedCard(leftMargin: Int) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f,
    ).apply { this.leftMargin = leftMargin }

    private fun weightedButton(leftMargin: Int) = LinearLayout.LayoutParams(0, dp(52f), 1f).apply {
        this.leftMargin = leftMargin
    }

    private fun format(value: Float, pattern: String): String =
        if (value.isNaN()) "—" else String.format(Locale.US, pattern, value)

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun color(red: Int, green: Int, blue: Int): Int = Color.rgb(red, green, blue)

    companion object {
        private const val REQUEST_BLUETOOTH = 41
    }
}
