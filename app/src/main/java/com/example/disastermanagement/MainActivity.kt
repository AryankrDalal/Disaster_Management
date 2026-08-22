package com.example.disastermanagement

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager

    private lateinit var packetStore: PacketStore

    private lateinit var locationManager: LocationManager

    private lateinit var statusText: TextView

    private lateinit var signalText: TextView

    private lateinit var sosSpinner: Spinner

    private val signalStrengths =
        linkedMapOf<Short, Int>()

    private var signalTrackingPaused =
        false

    private val messageCounter =
        AtomicInteger(1000)

    companion object {

        private const val PERMISSION_REQUEST_CODE = 100

        const val ACTION_SOS_RECEIVED =
            "com.example.disastermanagement.SOS_RECEIVED"

        const val ACTION_SIGNAL_UPDATE =
            "com.example.disastermanagement.SIGNAL_UPDATE"
    }

    // ==================================================
    // RECEIVE SOS FROM MESH SERVICE
    // ==================================================

    private val sosReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (intent?.action) {

                    ACTION_SOS_RECEIVED -> {

                        val message =
                            intent.getStringExtra(
                                "message"
                            )
                                ?: "SOS received"

                        runOnUiThread {

                            statusText.text =
                                message

                            Toast.makeText(
                                this@MainActivity,
                                "🚨 SOS RECEIVED",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    ACTION_SIGNAL_UPDATE -> {

                        val nodeId =
                            intent.getShortExtra(
                                "nodeId",
                                0
                            )

                        val rssi =
                            intent.getIntExtra(
                                "rssi",
                                0
                            )

                        runOnUiThread {

                            updateSignalDisplay(
                                nodeId,
                                rssi
                            )
                        }
                    }
                }
            }
        }

    // ==================================================
    // ON CREATE
    // ==================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        bleManager =
            BleManager(this)

        packetStore =
            PacketStore(this)

        locationManager =
            LocationManager(this)

        createUI()

        requestPermissions()
    }

    // ==================================================
    // REGISTER RECEIVER
    // ==================================================

    override fun onStart() {

        super.onStart()

        val filter =
            IntentFilter(
                ACTION_SOS_RECEIVED
            )

        filter.addAction(
            ACTION_SIGNAL_UPDATE
        )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            registerReceiver(
                sosReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            registerReceiver(
                sosReceiver,
                filter
            )
        }

        startPassiveSignalScanning()
    }

    // ==================================================
    // UNREGISTER RECEIVER
    // ==================================================

    override fun onStop() {

        bleManager.stopScanning()

        unregisterReceiver(
            sosReceiver
        )

        super.onStop()
    }

    // ==================================================
    // CREATE USER INTERFACE
    // ==================================================

    private fun createUI() {

        val layout =
            LinearLayout(this)

        layout.orientation =
            LinearLayout.VERTICAL

        layout.setPadding(
            40,
            60,
            40,
            40
        )

        // ==================================================
        // TITLE
        // ==================================================

        val title =
            TextView(this)

        title.text =
            "MESH SOS"

        title.textSize =
            28f

        layout.addView(
            title
        )

        // ==================================================
        // DEVICE ID
        // ==================================================

        val deviceIdText =
            TextView(this)

        deviceIdText.text =
            """
            DEVICE ID:
            ${nodeIdToString(packetStore.getNodeId())}
            """.trimIndent()

        deviceIdText.textSize =
            16f

        layout.addView(
            deviceIdText
        )

        // ==================================================
        // STATUS
        // ==================================================

        statusText =
            TextView(this)

        statusText.text =
            """
            STATUS:
            READY
            """.trimIndent()

        statusText.textSize =
            18f

        layout.addView(
            statusText
        )

        // ==================================================
        // LIVE SIGNAL STRENGTH
        // ==================================================

        signalText =
            TextView(this)

        signalText.text =
            "SIGNAL STRENGTH:\nNo nearby SOS / relay devices"

        signalText.textSize =
            16f

        signalText.setPadding(
            0,
            20,
            0,
            20
        )

        layout.addView(
            signalText
        )

        // ==================================================
        // MESH CONTROLS
        // ==================================================

        val meshTitle =
            TextView(this)

        meshTitle.text =
            "MESH CONTROLS"

        meshTitle.textSize =
            21f

        layout.addView(
            meshTitle
        )

        // --------------------------------------------------
        // START MESH RELAY
        // --------------------------------------------------

        val startButton =
            Button(this)

        startButton.text =
            "START MESH RELAY"

        startButton.setOnClickListener {

            signalTrackingPaused =
                false

            startMeshService()

            startPassiveSignalScanning()
        }

        layout.addView(
            startButton
        )

        // --------------------------------------------------
        // SEND SOS
        // --------------------------------------------------

        val sendSosButton =
            Button(this)

        sendSosButton.text =
            "SEND SOS"

        sendSosButton.setOnClickListener {

            signalTrackingPaused =
                false

            startPassiveSignalScanning()

            sendSOS()
        }

        layout.addView(
            sendSosButton
        )

        // --------------------------------------------------
        // STOP MESH
        // --------------------------------------------------

        val stopButton =
            Button(this)

        stopButton.text =
            "STOP MESH"

        stopButton.setOnClickListener {

            stopService(
                Intent(
                    this,
                    MeshService::class.java
                )
            )

            statusText.text =
                """
                STATUS:
                MESH STOPPED
                """.trimIndent()

            signalTrackingPaused =
                true

            bleManager.stopScanning()

            clearSignalDisplay()
        }

        layout.addView(
            stopButton
        )

        // ==================================================
        // SOS SECTION
        // ==================================================

        val sosTitle =
            TextView(this)

        sosTitle.text =
            "SOS:"

        sosTitle.textSize =
            22f

        layout.addView(
            sosTitle
        )

        // --------------------------------------------------
        // SOS KEYWORD SELECTOR
        // --------------------------------------------------

        sosSpinner =
            Spinner(this)

        val sosOptions =
            arrayOf(

                "🚑 Medical Emergency",

                "🔥 Fire",

                "🆘 Trapped",

                "⚠️ Accident",

                "💧 Need Water",

                "🏠 Evacuation Required"
            )

        val adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                sosOptions
            )

        adapter.setDropDownViewResource(
            android.R.layout
                .simple_spinner_dropdown_item
        )

        sosSpinner.adapter =
            adapter

        layout.addView(
            sosSpinner
        )

        // --------------------------------------------------
        // SEND SELECTED SOS
        // --------------------------------------------------

        val sendSelectedButton =
            Button(this)

        sendSelectedButton.text =
            "SEND SELECTED SOS"

        sendSelectedButton.setOnClickListener {

            val selected =
                sosSpinner.selectedItemPosition

            val sosType =
                when (selected) {

                    0 ->
                        SosPacket.MEDICAL.toByte()

                    1 ->
                        SosPacket.FIRE.toByte()

                    2 ->
                        SosPacket.TRAPPED.toByte()

                    3 ->
                        SosPacket.ACCIDENT.toByte()

                    4 ->
                        SosPacket.NEED_WATER.toByte()

                    5 ->
                        SosPacket.EVACUATION.toByte()

                    else ->
                        SosPacket.MEDICAL.toByte()
                }

            sendSOSWithType(
                sosType
            )
        }

        layout.addView(
            sendSelectedButton
        )

        setContentView(
            layout
        )
    }

    // ==================================================
    // START MESH SERVICE
    // ==================================================

    private fun startMeshService() {

        val intent =
            Intent(
                this,
                MeshService::class.java
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            startForegroundService(
                intent
            )

        } else {

            startService(intent)
        }

        statusText.text =
            """
            MESH SOS
            
            STATUS:
            RELAY ACTIVE
            
            DEVICE:
            ${
                nodeIdToString(
                    packetStore.getNodeId()
                )
            }
            
            LISTENING FOR SOS...
            """.trimIndent()
    }

    // ==================================================
    // DEFAULT SEND SOS
    // ==================================================

    private fun sendSOS() {

        sendSOSWithType(
            SosPacket.MEDICAL.toByte()
        )
    }

    // ==================================================
    // SEND SELECTED SOS
    // ==================================================

    private fun sendSOSWithType(
        sosType: Byte
    ) {

        if (!checkBluetooth()) {
            return
        }

        val sosName =
            SosPacket.getSosTypeName(
                sosType
            )

        statusText.text =
            """
            MESH SOS
            
            STATUS:
            GETTING GPS...
            
            SOS:
            $sosName
            """.trimIndent()

        locationManager.getCurrentLocation(

            onLocationReceived = { gps ->

                runOnUiThread {

                    val messageId =
                        messageCounter
                            .incrementAndGet()

                    val sourceId =
                        packetStore
                            .getNodeId()

                    val packet =
                        SosPacket(

                            messageId =
                                messageId,

                            sourceId =
                                sourceId,

                            relayId =
                                sourceId,

                            sourceLatitude =
                                gps.latitude,

                            sourceLongitude =
                                gps.longitude,

                            ttl =
                                5,

                            sosType =
                                sosType
                        )

                    // ----------------------------------
                    // SEND THROUGH BLE
                    // ----------------------------------

                    bleManager.advertise(
                        packet
                    )

                    statusText.text =
                        """
                        🚨 SOS TRANSMITTING
                        
                        TYPE:
                        ${
                            SosPacket
                                .getSosTypeName(
                                    sosType
                                )
                        }
                        
                        SOURCE DEVICE:
                        ${
                            nodeIdToString(
                                sourceId
                            )
                        }
                        
                        SOURCE LOCATION:
                        ${gps.latitude},
                        ${gps.longitude}
                        
                        TTL:
                        ${packet.ttl}
                        
                        MESSAGE ID:
                        $messageId
                        
                        TRANSPORT:
                        BLUETOOTH BLE
                        """.trimIndent()
                }
            },

            onError = { error ->

                runOnUiThread {

                    statusText.text =
                        """
                        GPS ERROR
                        
                        $error
                        """.trimIndent()

                    Toast.makeText(
                        this,
                        error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    // ==================================================
    // BLUETOOTH CHECK
    // ==================================================

    private fun checkBluetooth(): Boolean {

        val bluetoothManager =
            getSystemService(
                BluetoothManager::class.java
            )

        val adapter =
            bluetoothManager.adapter

        if (
            adapter == null ||
            !adapter.isEnabled
        ) {

            Toast.makeText(
                this,
                "Please turn Bluetooth ON",
                Toast.LENGTH_LONG
            ).show()

            return false
        }

        return true
    }

    // ==================================================
    // PERMISSIONS
    // ==================================================

    private fun requestPermissions() {

        val permissions =
            mutableListOf<String>()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            permissions.add(
                Manifest.permission.BLUETOOTH_SCAN
            )

            permissions.add(
                Manifest.permission.BLUETOOTH_ADVERTISE
            )

            permissions.add(
                Manifest.permission.BLUETOOTH_CONNECT
            )
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        permissions.add(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        permissions.add(
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missing =
            permissions.filter {

                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) !=
                        PackageManager
                            .PERMISSION_GRANTED
            }

        if (missing.isNotEmpty()) {

            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == PERMISSION_REQUEST_CODE) {

            startPassiveSignalScanning()
        }
    }

    // ==================================================
    // PASSIVE SIGNAL SCANNING
    // ==================================================

    private fun hasScanPermissions(): Boolean {

        val scanPermissionGranted =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED

            } else {

                true
            }

        val locationPermissionGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return scanPermissionGranted &&
                locationPermissionGranted
    }

    private fun startPassiveSignalScanning() {

        if (!hasScanPermissions()) {
            return
        }

        bleManager.startScanning { packet, rssi ->

            runOnUiThread {

                updateSignalDisplay(
                    packet.relayId,
                    rssi
                )
            }
        }
    }

    // ==================================================
    // NODE ID
    // ==================================================

    private fun nodeIdToString(
        id: Short
    ): String {

        return (
                id.toInt() and 0xFFFF
                ).toString()
    }

    // ==================================================
    // SIGNAL STRENGTH DISPLAY
    // ==================================================

    private fun clearSignalDisplay() {

        signalStrengths.clear()

        signalText.text =
            "SIGNAL STRENGTH:\nNo nearby SOS / relay devices"
    }

    private fun updateSignalDisplay(
        nodeId: Short,
        rssi: Int
    ) {

        if (signalTrackingPaused) {
            return
        }

        signalStrengths.remove(nodeId)
        signalStrengths[nodeId] =
            rssi

        while (signalStrengths.size > 8) {

            val oldestKey =
                signalStrengths.keys.first()

            signalStrengths.remove(
                oldestKey
            )
        }

        val builder =
            SpannableStringBuilder()

        builder.append(
            "SIGNAL STRENGTH:\n"
        )

        val sortedEntries =
            signalStrengths.entries
                .sortedByDescending { it.value }

        for (
        (index, entry) in
        sortedEntries.withIndex()
        ) {

            val (entryNodeId, entryRssi) =
                entry

            val quality =
                rssiQuality(entryRssi)

            val bar =
                rssiToBarString(entryRssi)

            val distance =
                rssiToDistanceMeters(entryRssi)

            val line =
                "$bar  Node ${
                    nodeIdToString(entryNodeId)
                }: $quality ($entryRssi dBm, ~${
                    "%.0f".format(distance)
                }m)"

            val lineStart =
                builder.length

            builder.append(line)

            builder.setSpan(
                ForegroundColorSpan(
                    rssiColor(entryRssi)
                ),
                lineStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            if (
                index <
                sortedEntries.size - 1
            ) {

                builder.append("\n")
            }
        }

        signalText.text =
            builder
    }

    private fun rssiToBarString(
        rssi: Int
    ): String {

        val clamped =
            rssi.coerceIn(-100, -30)

        val percent =
            (clamped + 100) / 70.0

        val filledBars =
            (percent * 5)
                .toInt()
                .coerceIn(0, 5)

        return "█".repeat(filledBars) +
                "░".repeat(5 - filledBars)
    }

    private fun rssiColor(
        rssi: Int
    ): Int {

        return when {

            rssi >= -60 ->
                Color.parseColor("#2E7D32")

            rssi >= -75 ->
                Color.parseColor("#F9A825")

            rssi >= -90 ->
                Color.parseColor("#EF6C00")

            else ->
                Color.parseColor("#C62828")
        }
    }

    private fun rssiToDistanceMeters(
        rssi: Int
    ): Double {

        val txPowerAt1m =
            -59

        val pathLossExponent =
            2.5

        val ratio =
            (txPowerAt1m - rssi) /
                    (10.0 * pathLossExponent)

        return Math.pow(
            10.0,
            ratio
        )
    }

    private fun rssiQuality(
        rssi: Int
    ): String {

        return when {

            rssi >= -60 ->
                "Excellent"

            rssi >= -75 ->
                "Good"

            rssi >= -90 ->
                "Weak"

            else ->
                "Very Weak"
        }
    }

    // ==================================================
    // DESTROY
    // ==================================================

    override fun onDestroy() {

        bleManager.stopScanning()

        bleManager.stopAdvertising()

        super.onDestroy()
    }
}
