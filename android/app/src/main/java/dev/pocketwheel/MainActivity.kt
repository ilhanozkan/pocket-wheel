package dev.pocketwheel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.pocketwheel.core.*
import java.util.Locale
import kotlin.math.roundToInt

/* THESIS: Two thumbs drive while eyes stay on the road.
 * OWN-WORLD: Matte slate, white system type, red brake, blue gas, native Material controls.
 * STORY: Pair once; hold the phone at its natural angle; Center, then Arm.
 * FIRST VIEWPORT: Large left brake and right gas flank connection, angle, D/N/R,
 * sequential shifts, parking brake, Center and Arm. Setup and tuning stay in the header.
 * FORM: User-selected landscape controller from DESIGN.md; fixed inherited composition.
 */
class MainActivity : AppCompatActivity(), SensorEventListener {
    private val controls = ControlState()
    private val steering = SteeringMath()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sensors: SensorManager
    private var rotationSensor: Sensor? = null
    private var currentQuaternion: Quaternion? = null
    private var lastSensorTimestamp = 0L
    private var settings = SteeringSettings()
    private var connection = ConnectionStatus()
    private var client: UdpController? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastSavedTimeoutAtNanos = Long.MIN_VALUE
    private var resumed = false
    private var requestedGear: String? = null
    private var hint = "Pair your Mac, hold the phone like a wheel, then tap Center."
    private val prefs by lazy { getSharedPreferences("pocket-wheel", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var angle: TextView
    private lateinit var footer: TextView
    private lateinit var brake: PedalView
    private lateinit var gas: PedalView
    private lateinit var armButton: MaterialButton
    private lateinit var centerButton: MaterialButton
    private val gearButtons = mutableListOf<MaterialButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val hasHardware = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null &&
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        if (hasHardware) rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        settings = loadSettings()
        buildInterface()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun label(value: String, size: Float = 14f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.on_surface))
    }

    private fun button(value: String, action: () -> Unit) = MaterialButton(this).apply {
        text = value
        textSize = 13f
        isAllCaps = false
        minimumWidth = 0
        minWidth = 0
        minimumHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        setPadding(dp(4), 0, dp(4), 0)
        cornerRadius = dp(8)
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.surface_variant))
        setTextColor(getColor(R.color.on_surface))
        setOnClickListener { action() }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        // Target SDK 35 edge-to-edge requires explicit safe areas, including landscape cutouts.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int; val top: Int; val right: Int; val bottom: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom
            } else {
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(dp(12) + left, dp(4) + top, dp(12) + right, dp(4) + bottom)
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(label("Pocket Wheel", 20f).apply { setTypeface(typeface, Typeface.BOLD) })
        status = label("Disconnected", 12f).apply { setTextColor(getColor(R.color.on_surface_variant)) }
        title.addView(status)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Pair Mac") { showPairing() }, LinearLayout.LayoutParams(dp(90), dp(48)).apply { marginEnd = dp(8) })
        header.addView(button("Tune") { showTuning() }, LinearLayout.LayoutParams(dp(68), dp(48)))
        header.addView(button("Details") { showConnectionDetails() }.apply {
            contentDescription = "Connection details and last timeout"
        }, LinearLayout.LayoutParams(dp(78), dp(48)).apply { marginStart = dp(8) })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(52)))

        val driving = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isMotionEventSplittingEnabled = true
            gravity = Gravity.CENTER_VERTICAL
        }
        brake = PedalView(this, "BRAKE", getColor(R.color.brake)) { controls.pedals(brake = it) }
        gas = PedalView(this, "GAS", getColor(R.color.gas)) { controls.pedals(throttle = it) }
        driving.addView(brake, LinearLayout.LayoutParams(0, -1, 1f))
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        }
        angle = label("Hold phone level → Center", 16f).apply { gravity = Gravity.CENTER }
        center.addView(angle, LinearLayout.LayoutParams(-1, dp(36)))
        fun row(vararg children: View) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; isMotionEventSplittingEnabled = true }
            children.forEachIndexed { index, child ->
                row.addView(child, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) marginStart = dp(8) })
            }
            center.addView(row, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        }
        fun gear(text: String, bit: Int, description: String): MaterialButton = button(text) {
            if (bit in 0..2) controls.selectAutomaticGear(bit, System.nanoTime())
            else controls.pulse(bit, System.nanoTime())
            requestedGear = text
            hint = "Requested: $description. Gear buttons do not show the game's current gear."
            render()
        }.also { it.contentDescription = description; it.tag = bit; gearButtons += it }
        row(gear("D", 0, "Drive"), gear("N", 1, "Neutral"), gear("R", 2, "Reverse"))
        row(gear("−", 4, "Shift down"), gear("+", 3, "Shift up"), gear("Park", 5, "Toggle parking brake"))
        centerButton = button("Center") { calibrate() }.apply { contentDescription = "Calibrate steering center and disarm" }
        armButton = button("Arm") {
            if (controls.snapshot(System.nanoTime()).armed) {
                disarm()
                hint = "Disarmed. Tap Arm when ready."
            } else if (connection.connected && steering.calibrated && currentQuaternion != null) {
                gas.release(); brake.release()
                controls.arm()
                hint = "Hold and slide each pedal. Release your thumb to release the pedal."
            }
            render()
        }.apply {
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.primary))
            setTextColor(getColor(R.color.on_primary))
        }
        row(centerButton, armButton)
        driving.addView(center, LinearLayout.LayoutParams(0, -1, 1.7f))
        driving.addView(gas, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(driving, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        footer = label(hint, 12f).apply {
            setTextColor(getColor(R.color.on_surface_variant))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(36)))
        setContentView(root)
        root.requestApplyInsets()
        render()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        steering.clearCalibration()
        currentQuaternion = null
        lastSensorTimestamp = 0L
        disarm(DisarmReason.CONNECTING)
        if (rotationSensor == null) hint = "This phone needs a gyroscope, accelerometer, and rotation sensor. Try another phone."
        else if (!sensors.registerListener(this, rotationSensor, 10_000)) {
            rotationSensor = null
            hint = "Motion sensor could not start. Restart the app or try another phone."
        } else hint = "Hold your phone in its driving position and tap Center."
        startConnection()
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
        disarm(DisarmReason.PAUSED)
        steering.clearCalibration()
        sensors.unregisterListener(this)
        stopConnection()
        connection = ConnectionStatus(message = "Paused • return and calibrate again")
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        stopConnection()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::gas.isInitialized) { disarm(DisarmReason.FOCUS_LOST); render() }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            render()
            handler.postDelayed(this, 50)
        }
    }

    private fun disarm(reason: DisarmReason = DisarmReason.MANUAL) {
        controls.disarm(reason)
        requestedGear = null
        if (::gas.isInitialized) { gas.release(); brake.release() }
    }

    private fun startConnection() {
        stopConnection()
        disarm(DisarmReason.CONNECTING)
        if (!resumed) return
        val host = prefs.getString("host", "")!!.trim()
        val key = prefs.getString("key", "")!!.trim()
        if (host.isBlank() || !Protocol.validKey(key)) {
            connection = ConnectionStatus(message = "Pair Mac to connect")
            return
        }
        connection = ConnectionStatus(message = "Connecting to $host…")
        lateinit var newClient: UdpController
        newClient = UdpController(host, key, controls, { latest ->
            runOnUiThread {
                if (resumed && client === newClient) {
                    connection = latest
                    latest.diagnostics.lastTimeout?.let { timeout ->
                        if (timeout.transport.capturedAtNanos != lastSavedTimeoutAtNanos) {
                            lastSavedTimeoutAtNanos = timeout.transport.capturedAtNanos
                            prefs.edit().putString("last-timeout-report", TransportReport.timeout(timeout)).apply()
                        }
                    }
                    // This status is terminal; ordinary timeout/re-pairing statuses keep the worker alive.
                    if (!latest.connected && latest.message.startsWith("Network unavailable:")) stopConnection()
                    if (!latest.connected || !controls.snapshot(System.nanoTime()).armed) {
                        gas.release(); brake.release()
                    }
                    render()
                }
            }
        })
        client = newClient
        acquireWifiLock()
        try {
            newClient.start()
        } catch (_: RuntimeException) {
            stopConnection()
            connection = ConnectionStatus(message = "Could not start connection. Use Pair Mac to retry.")
            render()
        }
    }

    /** The foreground controller is a real-time Wi-Fi client; release the request with its lifecycle. */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (!resumed || client == null || wifiLock != null) return
        try {
            val manager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            // Store before acquire so a partial platform failure still reaches cleanup.
            val lock = manager.createWifiLock(mode, "PocketWheel:controls")
            wifiLock = lock
            lock.setReferenceCounted(false)
            lock.acquire()
        } catch (_: RuntimeException) {
            // Some devices cannot honor this optimization. The authenticated controller still works.
            releaseWifiLock()
        }
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        wifiLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    private fun stopConnection() {
        val previous = client
        client = null
        try {
            previous?.close()
        } finally {
            releaseWifiLock()
        }
    }

    private fun calibrate() {
        disarm(DisarmReason.CALIBRATING)
        val q = currentQuaternion
        if (q == null || System.nanoTime() - controls.lastMotionNanos > 250_000_000L) {
            hint = "No recent motion reading. Keep the app open and try Center again."
        } else if (steering.calibrate(q)) {
            hint = "Centered. Enable controls on your Mac, then tap Arm."
            requestedGear = null
        }
        render()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!resumed) return
        val values = FloatArray(4)
        SensorManager.getQuaternionFromVector(values, event.values)
        val q = Quaternion(values[0].toDouble(), values[1].toDouble(), values[2].toDouble(), values[3].toDouble())
        currentQuaternion = q.normalized()
        val elapsed = if (lastSensorTimestamp == 0L) 0.01 else (event.timestamp - lastSensorTimestamp) / 1e9
        lastSensorTimestamp = event.timestamp
        val value = steering.update(q, elapsed, settings)
        if (value == null) {
            disarm(DisarmReason.INVALID_MOTION)
            steering.clearCalibration()
            hint = "Hold the screen facing you, then Center again."
        } else controls.motion(value, System.nanoTime())
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun render() {
        if (!::status.isInitialized) return
        val currentControls = controls.snapshot(System.nanoTime())
        val active = currentControls.armed
        val stopReason = controls.disarmReason
        if (!active) requestedGear = null
        status.text = if (connection.connected && connection.repliesDelayed) {
            "${if (active) "Armed" else "Disarmed"} · Mac replies delayed"
        } else if (connection.connected) {
            val stateLabel = if (active) "Armed" else stopReason?.let { "Connected · disarmed · ${disarmLabel(it)}" } ?: "Connected · disarmed"
            "$stateLabel · ${connection.rttMs ?: "—"} ms RTT"
        } else connection.message
        status.maxLines = 1
        status.ellipsize = android.text.TextUtils.TruncateAt.END
        angle.text = if (steering.calibrated) String.format(Locale.getDefault(), "%+.0f°  /  ±%.0f°", steering.angleDegrees, settings.degreesEachSide)
            else "Hold phone → Center"
        armButton.text = if (active) "Disarm" else "Arm"
        val motionFresh = System.nanoTime() - controls.lastMotionNanos <= 250_000_000L
        armButton.isEnabled = active || (connection.connected && steering.calibrated && rotationSensor != null && motionFresh)
        armButton.alpha = if (armButton.isEnabled) 1f else 0.4f
        centerButton.isEnabled = currentQuaternion != null
        centerButton.alpha = if (centerButton.isEnabled) 1f else 0.4f
        val selectedAutomatic = when {
            currentControls.buttons and 1 != 0 -> 0
            currentControls.buttons and 4 != 0 -> 2
            else -> 1
        }
        gearButtons.forEach {
            it.isEnabled = active
            it.alpha = if (active) 1f else 0.5f
            val selected = active && (it.tag as Int) in 0..2 && it.tag == selectedAutomatic
            if (it.isSelected != selected) {
                it.isSelected = selected
                it.backgroundTintList = ColorStateList.valueOf(getColor(if (selected) R.color.primary else R.color.surface_variant))
                it.setTextColor(getColor(if (selected) R.color.on_primary else R.color.on_surface))
            }
        }
        gas.isEnabled = active
        brake.isEnabled = active
        if (!active && (gas.value > 0 || brake.value > 0)) { gas.release(); brake.release() }
        gas.invalidate(); brake.invalidate()
        footer.text = when {
            rotationSensor == null -> "This phone needs a gyroscope, accelerometer, and rotation sensor. Try another phone."
            active && connection.repliesDelayed -> "Still sending controls. Mac confirmation is delayed; its input-loss protection remains active."
            !active && stopReason != null -> disarmMessage(stopReason)
            !connection.connected && prefs.getString("host", "").orEmpty().isNotEmpty() -> connection.message
            else -> if (active && requestedGear != null) "Requested: $requestedGear. D/R stay selected until N, a sequential shift, or Disarm." else hint
        }
    }

    private fun disarmLabel(reason: DisarmReason): String = when (reason) {
        DisarmReason.MANUAL -> "manual"
        DisarmReason.CONNECTION_LOST -> "connection reset"
        DisarmReason.NETWORK_ERROR -> "network error"
        DisarmReason.RECEIVER_UNAVAILABLE -> "Mac unavailable"
        DisarmReason.MAC_DISABLED -> "Mac disarmed"
        DisarmReason.MOTION_PAUSED -> "motion paused"
        DisarmReason.INVALID_MOTION -> "motion invalid"
        DisarmReason.PAUSED -> "app paused"
        DisarmReason.FOCUS_LOST -> "focus lost"
        DisarmReason.CALIBRATING -> "centering"
        DisarmReason.SETTINGS_CHANGED -> "settings"
        DisarmReason.CONNECTING -> "pairing"
    }

    /** Keep the first stop cause visible after reconnect; Arm starts a new driving interval. */
    private fun disarmMessage(reason: DisarmReason): String = when (reason) {
        DisarmReason.MANUAL -> "Disarmed by you. Tap Arm when ready."
        DisarmReason.CONNECTION_LOST -> "The receiver connection was reset. Wait for Connected, then Arm again."
        DisarmReason.NETWORK_ERROR -> "The network connection failed. Check Wi-Fi, then use Pair Mac to reconnect."
        DisarmReason.RECEIVER_UNAVAILABLE -> "Mac receiver unavailable. Start the receiver, wait for Connected, then Arm."
        DisarmReason.MAC_DISABLED -> "Mac disarmed controls. Check Enable controls and the connection, then Arm again."
        DisarmReason.MOTION_PAUSED -> "Motion updates paused for 250 ms. Keep this app visible; hold steady, then Arm."
        DisarmReason.INVALID_MOTION -> "Motion reading was invalid. Hold the screen facing you, then Center and Arm."
        DisarmReason.PAUSED -> "The phone app was paused. Center the phone, then Arm."
        DisarmReason.FOCUS_LOST -> "The phone app lost focus. Close overlays, return here, then Arm."
        DisarmReason.CALIBRATING -> if (steering.calibrated) "Steering centered. Tap Arm when ready." else "Hold the phone steady, then Center and Arm."
        DisarmReason.SETTINGS_CHANGED -> "Controls stopped for steering settings. Center the phone, then Arm."
        DisarmReason.CONNECTING -> when {
            prefs.getString("host", "").isNullOrEmpty() -> "Pair your Mac, hold the phone like a wheel, then tap Center."
            !connection.connected -> connection.message
            !steering.calibrated -> "Connected. Hold the phone in its driving position, then Center and Arm."
            else -> "Connected and centered. Enable controls on the Mac, then tap Arm."
        }
    }

    private fun dialogColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
    }

    private fun showConnectionDetails() {
        disarm()
        render()
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        fun report(): String {
            val saved = prefs.getString("last-timeout-report", null) ?: "No timeout has been recorded."
            return "Pocket Wheel $version\n\n" +
                "LAST TIMEOUT · frozen at interruption\n$saved\n\n" +
                "CURRENT CONNECTION · latest sample\n${TransportReport.snapshot(connection.diagnostics.current)}\n\n" +
                "ACK means a valid reply confirming a control packet. Authenticated replies can still be discarded if outdated.\n" +
                "Counts and longest pauses include automatic reconnects. A longest pause may predate this timeout.\n" +
                "The timeout report stays saved after reconnect."
        }
        var visibleReport = report()
        val content = dialogColumn()
        content.addView(label("Copy this report after delayed Mac replies or an unexpected disarm. A reply timeout alone no longer disarms controls. It contains no addresses, pairing key, or packet contents.", 13f))
        val details = label(visibleReport, 13f).apply {
            setPadding(0, dp(16), 0, dp(12))
            setTextIsSelectable(true)
        }
        content.addView(details)
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Connection details")
            .setView(scroll).setPositiveButton("Close", null)
            .setNegativeButton("Copy", null).setNeutralButton("Refresh", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Pocket Wheel connection report", visibleReport))
                Toast.makeText(this, "Connection report copied", Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                visibleReport = report()
                details.text = visibleReport
            }
        }
        dialog.show()
    }

    private fun showPairing() {
        disarm(DisarmReason.CONNECTING)
        val content = dialogColumn()
        content.addView(label("Start Pocket Wheel on your Mac. Enter its Wi-Fi address and pairing key below. Both devices must use the same network."))
        content.addView(label("Mac Wi-Fi address").apply { setPadding(0, dp(16), 0, 0) })
        val host = EditText(this).apply {
            setText(prefs.getString("host", ""))
            hint = "192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            minimumHeight = dp(48)
            contentDescription = "Mac Wi-Fi address"
        }
        content.addView(host)
        content.addView(label("Pairing key · 16 characters").apply { setPadding(0, dp(12), 0, 0) })
        val key = EditText(this).apply {
            setText(prefs.getString("key", ""))
            hint = "Copy from the Mac companion"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            minimumHeight = dp(48)
            contentDescription = "Pairing key"
        }
        content.addView(key)
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Pair your Mac").setView(scroll)
            .setPositiveButton("Connect", null).setNegativeButton("Cancel", null)
            .setNeutralButton("Disconnect") { _, _ ->
                stopConnection(); disarm()
                connection = ConnectionStatus(message = "Disconnected • Pair Mac to reconnect")
                render()
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hostValue = host.text.toString().trim()
                val keyValue = key.text.toString().trim().lowercase(Locale.ROOT)
                if (!Regex("[A-Za-z0-9.-]{1,253}").matches(hostValue)) {
                    host.error = "Enter the Mac IP address shown in Pocket Wheel"; return@setOnClickListener
                }
                if (!Protocol.validKey(keyValue)) { key.error = "Use the 16-character pairing key from your Mac"; return@setOnClickListener }
                prefs.edit().putString("host", hostValue).putString("key", keyValue).apply()
                hint = "Hold your phone in its driving position and tap Center."
                startConnection()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun loadSettings() = SteeringSettings(
        prefs.getFloat("range", 90f).toDouble(), prefs.getFloat("deadZone", 2f).toDouble(),
        prefs.getFloat("smoothing", 45f).toDouble(), prefs.getFloat("response", 1f).toDouble(), prefs.getBoolean("invert", false),
    )

    private fun showTuning() {
        disarm(DisarmReason.SETTINGS_CHANGED)
        val content = dialogColumn()
        content.addView(label("Changes apply after you save and Center again. A larger wheel range gives finer control."))
        fun slider(title: String, min: Int, max: Int, initial: Int, format: (Int) -> String): SeekBar {
            val caption = label("$title: ${format(initial)}").apply { setPadding(0, dp(16), 0, 0) }
            content.addView(caption)
            return SeekBar(this).apply {
                this.min = min; this.max = max; progress = initial
                contentDescription = title
                minimumHeight = dp(48)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { caption.text = "$title: ${format(progress)}" }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
                content.addView(this)
            }
        }
        val range = slider("Rotation each side", 30, 160, settings.degreesEachSide.toInt()) { "$it°" }
        val dead = slider("Center dead zone", 0, 15, settings.deadZoneDegrees.toInt()) { "$it°" }
        val smoothing = slider("Smoothing", 0, 200, settings.smoothingMs.toInt()) { "$it ms" }
        val response = slider("Response curve", 50, 200, (settings.responseExponent * 100).roundToInt()) { String.format(Locale.getDefault(), "%.2f", it / 100.0) }
        content.addView(label("1.0 is linear. Higher values soften steering near center.", 12f))
        val inverted = SwitchMaterial(this).apply { text = "Invert steering"; isChecked = settings.inverted; minimumHeight = dp(48) }
        content.addView(inverted)
        val sensorName = rotationSensor?.name ?: "Unavailable"
        content.addView(label("Motion sensor: $sensorName", 12f))
        val scroll = ScrollView(this).apply { addView(content) }
        MaterialAlertDialogBuilder(this).setTitle("Steering settings").setView(scroll)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                settings = SteeringSettings(range.progress.toDouble(), dead.progress.toDouble(), smoothing.progress.toDouble(), response.progress / 100.0, inverted.isChecked)
                prefs.edit().putFloat("range", range.progress.toFloat()).putFloat("deadZone", dead.progress.toFloat())
                    .putFloat("smoothing", smoothing.progress.toFloat()).putFloat("response", response.progress / 100f)
                    .putBoolean("invert", inverted.isChecked).apply()
                steering.clearCalibration()
                hint = "Settings saved. Hold your phone like a wheel, then tap Center."
                render()
            }.show()
    }
}
