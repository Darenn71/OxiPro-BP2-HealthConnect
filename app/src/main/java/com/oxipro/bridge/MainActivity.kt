package com.oxipro.bridge

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.oxipro.bridge.ble.BleManager
import com.oxipro.bridge.ble.BloodPressureParser
import com.oxipro.bridge.health.BpEvaluator
import com.oxipro.bridge.health.HealthConnectManager
import kotlinx.coroutines.launch

/**
 * Single-screen UI: request permissions -> scan -> connect -> every parsed
 * reading gets written to Health Connect and shown as a colour-coded result
 * card (systolic / diastolic / pulse / pulse pressure, each traffic-lighted
 * against published reference ranges — see BpEvaluator for sources).
 *
 * Still programmatic (no layout XML) so the whole flow stays in one file;
 * swap in a real layout/ViewModel as you flesh this out further.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var healthConnectManager: HealthConnectManager

    private lateinit var statusView: TextView
    private lateinit var liveDataSection: LinearLayout
    private lateinit var livePressureValue: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var categoryView: TextView
    private lateinit var feedbackView: TextView
    private lateinit var systolicValue: TextView
    private lateinit var diastolicValue: TextView
    private lateinit var pulseValue: TextView
    private lateinit var pulsePressureValue: TextView
    private lateinit var startButton: Button
    private lateinit var saveButton: Button
    private lateinit var closeButton: Button

    /** The most recent parsed reading, held until the user taps Save. */
    private var pendingResult: BloodPressureParser.ParsedPacket.FinalResult? = null

    private val bluetoothPermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val requestBlePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) startScanning() else setStatus("Bluetooth permissions denied")
        }

    private val requestHealthConnectPermissions =
        registerForActivityResult(
            androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
        ) { _ ->
            lifecycleScope.launch {
                if (healthConnectManager.hasAllPermissions()) {
                    setStatus("Health Connect authorized. Requesting Bluetooth permissions...")
                    requestBlePermissions.launch(bluetoothPermissions)
                } else {
                    setStatus("Health Connect permissions denied")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = BleManager(this)
        healthConnectManager = HealthConnectManager(this)

        setContentView(buildLayout())
        observeBle()
    }

    // ---- Layout construction --------------------------------------------

    private fun buildLayout(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }

        val appTitle = TextView(this).apply {
            text = "OxiPro Bridge"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(appTitle)

        val appSubtitle = TextView(this).apply {
            text = "OxiPro BP2 → Health Connect"
            textSize = 13f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(appSubtitle)

        statusView = TextView(this).apply {
            text = "Ready"
            textSize = 15f
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(statusView)

        startButton = Button(this).apply {
            text = "Connect & Sync"
            setOnClickListener { beginFlow() }
        }
        root.addView(startButton)

        // Live-data section — hidden until cuff-pressure notifications start
        // arriving, hidden again once a final result comes in.
        liveDataSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(24))
            visibility = View.GONE
        }
        liveDataSection.addView(TextView(this).apply {
            text = "Getting live data"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        livePressureValue = TextView(this).apply {
            textSize = 64f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        }
        liveDataSection.addView(livePressureValue)
        liveDataSection.addView(TextView(this).apply {
            text = "mmHg — cuff deflating"
            textSize = 13f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER
        })
        root.addView(liveDataSection)

        // Result card — hidden until the first reading arrives.
        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            visibility = View.GONE
        }

        categoryView = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        resultCard.addView(categoryView)

        val readingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        systolicValue = bigValueView()
        val slash = TextView(this).apply {
            text = "/"
            textSize = 48f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        diastolicValue = bigValueView()
        readingsRow.addView(systolicValue)
        readingsRow.addView(slash)
        readingsRow.addView(diastolicValue)
        resultCard.addView(readingsRow)

        labelView("mmHg (systolic / diastolic)", center = true)

        pulseValue = statLine("Pulse")
        pulsePressureValue = statLine("Pulse pressure (systolic − diastolic)")

        feedbackView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(20), 0, 0)
        }
        resultCard.addView(feedbackView)

        saveButton = Button(this).apply {
            text = "Save to Health Connect"
            setOnClickListener { saveCurrentReading() }
        }
        resultCard.addView(saveButton)
        (saveButton.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = dp(16)
            saveButton.layoutParams = it
        }

        val sourceNote = TextView(this).apply {
            text = "Reference ranges: American Heart Association / American College " +
                "of Cardiology (2017 guideline, reaffirmed 2025) for blood pressure " +
                "and heart rate; Cleveland Clinic for pulse pressure. General " +
                "reference information, not medical advice."
            textSize = 10f
            setTextColor(Color.parseColor("#777777"))
            setPadding(0, dp(20), 0, 0)
        }
        resultCard.addView(sourceNote)

        root.addView(resultCard)

        closeButton = Button(this).apply {
            text = "Close App"
            setOnClickListener { closeApp() }
        }
        root.addView(closeButton)
        (closeButton.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = dp(32)
            closeButton.layoutParams = it
        }

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun bigValueView(): TextView = TextView(this).apply {
        textSize = 56f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun labelView(text: String, center: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#999999"))
        if (center) gravity = Gravity.CENTER
    }.also { resultCard.addView(it) }

    /** Adds a "Label   Value" row to the card and returns the value TextView to update later. */
    private fun statLine(label: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(value)
        resultCard.addView(row)
        return value
    }

    // ---- Flow --------------------------------------------------------------

    private fun beginFlow() {
        if (!healthConnectManager.isAvailable()) {
            setStatus("Health Connect app not installed / not available on this device")
            return
        }
        lifecycleScope.launch {
            if (healthConnectManager.hasAllPermissions()) {
                setStatus("Requesting Bluetooth permissions...")
                requestBlePermissions.launch(bluetoothPermissions)
            } else {
                requestHealthConnectPermissions.launch(healthConnectManager.permissions)
            }
        }
    }

    private fun startScanning() {
        setStatus("Scanning for OxiPro BP2...")
        bleManager.startScan { device: BluetoothDevice ->
            setStatus("Found ${device.name}, connecting...")
            bleManager.connect(device)
        }
    }

    private fun observeBle() {
        lifecycleScope.launch {
            bleManager.connectionState.collect { setStatus(it) }
        }
        lifecycleScope.launch {
            bleManager.isConnected.collect { connected ->
                startButton.visibility = if (connected) View.GONE else View.VISIBLE
                if (!connected) liveDataSection.visibility = View.GONE
            }
        }
        lifecycleScope.launch {
            bleManager.packets.collect { packet ->
                when (packet) {
                    is BloodPressureParser.ParsedPacket.LivePressure -> {
                        liveDataSection.visibility = View.VISIBLE
                        livePressureValue.text = packet.cuffPressureMmHg.toString()
                    }
                    is BloodPressureParser.ParsedPacket.FinalResult -> {
                        liveDataSection.visibility = View.GONE
                        setStatus("Reading captured")
                        pendingResult = packet
                        showResult(packet)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun saveCurrentReading() {
        val result = pendingResult ?: return
        saveButton.isEnabled = false
        setStatus("Writing to Health Connect...")
        lifecycleScope.launch {
            runCatching { healthConnectManager.writeReading(result) }
                .onSuccess {
                    setStatus("Saved to Health Connect ✔")
                    saveButton.text = "Saved ✔"
                }
                .onFailure {
                    setStatus("Health Connect write failed: ${it.message}")
                    saveButton.isEnabled = true
                }
        }
    }

    private fun showResult(result: BloodPressureParser.ParsedPacket.FinalResult) {
        val assessment = BpEvaluator.evaluate(result.systolicMmHg, result.diastolicMmHg, result.pulseBpm)

        resultCard.visibility = View.VISIBLE
        categoryView.text = assessment.overallCategory

        systolicValue.text = result.systolicMmHg.toString()
        systolicValue.setTextColor(Color.parseColor(assessment.systolic.colorHex))

        diastolicValue.text = result.diastolicMmHg.toString()
        diastolicValue.setTextColor(Color.parseColor(assessment.diastolic.colorHex))

        pulseValue.text = assessment.pulse.value
        pulseValue.setTextColor(Color.parseColor(assessment.pulse.colorHex))

        pulsePressureValue.text = assessment.pulsePressure.value
        pulsePressureValue.setTextColor(Color.parseColor(assessment.pulsePressure.colorHex))

        feedbackView.text = assessment.feedback

        saveButton.isEnabled = true
        saveButton.text = "Save to Health Connect"
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun closeApp() {
        bleManager.disconnect()
        finishAffinity()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}
