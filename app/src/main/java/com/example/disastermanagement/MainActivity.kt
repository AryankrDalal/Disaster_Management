package com.example.disastermanagement

import android.Manifest
import android.bluetooth.BluetoothAdapter
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
import android.widget.Button
import android.widget.LinearLayout
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

    private val messageCounter =
        AtomicInteger(1000)

    companion object {

        private const val PERMISSION_REQUEST_CODE = 100

        const val ACTION_SOS_RECEIVED =
            "com.example.disastermanagement.SOS_RECEIVED"

        const val ACTION_SIGNAL_UPDATE =
            "com.example.disastermanagement.SIGNAL_UPDATE"
    }

    // --------------------------------------------------
    // LIVE SIGNAL STRENGTH STATE
    // --------------------------------------------------

    // nodeId -> last known RSSI (dBm), sorted strongest first for display
    private val signalStrengths =
        linkedMapOf<Short, Int>()

    // When true, incoming RSSI readings are ignored and the
    // panel stays on "No nearby devices" until mesh relay or
    // SOS sending is explicitly started again.
    private var signalTrackingPaused = false

    private lateinit var signalText: TextView

    // --------------------------------------------------
    // RECEIVE MESSAGES FROM MESH SERVICE
    // --------------------------------------------------

    private val sosReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (intent?.action) {

                    ACTION_SOS_RECEIVED -> {

                        val message =
                            intent.getStringExtra("message")
                                ?: "SOS received"

                        runOnUiThread {

                            statusText.text =
                                message

                            Toast.makeText(
                                this@MainActivity,
                                "SOS RECEIVED",
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

    // --------------------------------------------------
    // REGISTER RECEIVER
    // --------------------------------------------------

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

        // So the SENDING phone can also see live signal
        // strength to whoever relays/receives its SOS, not
        // just phones running the mesh relay service.
        startPassiveSignalScanning()
    }

    override fun onStop() {

        bleManager.stopScanning()

        unregisterReceiver(
            sosReceiver
        )

        super.onStop()
    }

    // --------------------------------------------------
    // UI
    // --------------------------------------------------

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

        val nodeIdText =
            TextView(this)

        nodeIdText.text =
            "DEVICE NODE ID: " +
                    nodeIdToString(
                        packetStore.getNodeId()
                    )

        nodeIdText.textSize = 18f

        layout.addView(
            nodeIdText
        )

        statusText =
            TextView(this)

        statusText.text =
            """
            MESH SOS
            
            STATUS: READY
            
            GPS: Waiting
            """.trimIndent()

        statusText.textSize = 18f

        layout.addView(
            statusText
        )

        signalText =
            TextView(this)

        signalText.text =
            "SIGNAL: No nearby devices"

        signalText.textSize = 16f

        signalText.setPadding(
            0,
            20,
            0,
            20
        )

        layout.addView(
            signalText
        )

        // --------------------------------------------------
        // START RELAY
        // --------------------------------------------------

        val relayButton =
            Button(this)

        relayButton.text =
            "START MESH RELAY"

        relayButton.setOnClickListener {

            signalTrackingPaused = false

            startMeshService()

            startPassiveSignalScanning()
        }

        layout.addView(
            relayButton
        )

        // --------------------------------------------------
        // SEND SOS
        // --------------------------------------------------

        val sosButton =
            Button(this)

        sosButton.text =
            "SEND SOS"

        sosButton.setOnClickListener {

            signalTrackingPaused = false

            startPassiveSignalScanning()

            sendSOS()
        }

        layout.addView(
            sosButton
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
                MESH SOS
                
                STATUS:
                MESH STOPPED
                """.trimIndent()

            // Stop this phone's own passive scan too, and
            // ignore any updates already in flight, so the
            // panel stays cleared until explicitly restarted.
            signalTrackingPaused = true

            bleManager.stopScanning()

            clearSignalDisplay()
        }

        layout.addView(
            stopButton
        )

        setContentView(
            layout
        )
    }

    // --------------------------------------------------
    // START MESH SERVICE
    // --------------------------------------------------

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

            startService(
                intent
            )
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
            
            GPS:
            Obtaining...
            
            LISTENING FOR SOS...
            """.trimIndent()
    }

    // --------------------------------------------------
    // SEND SOS
    // --------------------------------------------------

    private fun sendSOS() {

        if (!checkBluetooth()) {
            return
        }

        statusText.text =
            """
            MESH SOS
            
            STATUS:
            GETTING GPS...
            
            Please wait...
            """.trimIndent()

        locationManager.getCurrentLocation(

            onLocationReceived = { gps ->

                runOnUiThread {

                    transmitSOS(gps)
                }
            },

            onError = { error ->

                runOnUiThread {

                    statusText.text =
                        """
                        MESH SOS
                        
                        STATUS:
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

    // --------------------------------------------------
    // TRANSMIT SOS
    // --------------------------------------------------

    private fun transmitSOS(
        gps: GpsLocation
    ) {

        val messageId =
            messageCounter.incrementAndGet()

        val sourceId =
            packetStore.getNodeId()

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
                    5
            )

        bleManager.advertise(
            packet
        )

        statusText.text =
            """
            MESH SOS
            
            STATUS:
            SOS TRANSMITTING
            
            SOURCE DEVICE:
            ${
                nodeIdToString(
                    sourceId
                )
            }
            
            SOURCE LATITUDE:
            ${gps.latitude}
            
            SOURCE LONGITUDE:
            ${gps.longitude}
            
            TTL:
            ${packet.ttl}
            
            MESSAGE ID:
            $messageId
            """.trimIndent()
    }

    // --------------------------------------------------
    // BLUETOOTH
    // --------------------------------------------------

    private fun checkBluetooth(): Boolean {

        val adapter =
            BluetoothAdapter.getDefaultAdapter()

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

    // --------------------------------------------------
    // PERMISSIONS
    // --------------------------------------------------

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
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missing.isNotEmpty()) {

            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

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

            // Permissions may have just been granted -
            // start (or retry) passive signal scanning.
            startPassiveSignalScanning()
        }
    }

    // --------------------------------------------------
    // PASSIVE SIGNAL SCANNING (this phone, not the relay
    // service). Lets the SENDING phone see live RSSI to
    // whoever picks up / relays its SOS.
    // --------------------------------------------------

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

    private fun nodeIdToString(
        id: Short
    ): String {

        return (
                id.toInt() and 0xFFFF
                ).toString()
    }

    // --------------------------------------------------
    // SIGNAL STRENGTH DISPLAY
    // --------------------------------------------------

    private fun clearSignalDisplay() {

        signalStrengths.clear()

        signalText.text =
            "SIGNAL: No nearby devices"
    }

    private fun updateSignalDisplay(
        nodeId: Short,
        rssi: Int
    ) {

        if (signalTrackingPaused) {
            return
        }

        // Move this node to the front (most recently heard)
        signalStrengths.remove(nodeId)
        signalStrengths[nodeId] = rssi

        // Keep the list from growing forever
        while (signalStrengths.size > 8) {

            val oldestKey =
                signalStrengths.keys.first()

            signalStrengths.remove(oldestKey)
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
                }: $entryRssi dBm " +
                        "($quality, ~${
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

    /**
     * Simple 5-segment bar built from block characters,
     * scaled between -100 dBm (empty) and -30 dBm (full).
     */
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
                Color.parseColor("#2E7D32") // green: strong

            rssi >= -75 ->
                Color.parseColor("#F9A825") // amber: medium

            rssi >= -90 ->
                Color.parseColor("#EF6C00") // orange: weak

            else ->
                Color.parseColor("#C62828") // red: very weak
        }
    }

    /**
     * Very rough distance estimate from RSSI using the
     * log-distance path loss model. This is NOT precise -
     * BLE RSSI is noisy and affected by obstacles, phone
     * orientation, and antenna differences between devices.
     * Treat it as "closer/farther", not a real measurement.
     */
    private fun rssiToDistanceMeters(
        rssi: Int
    ): Double {

        // Assumed calibrated RSSI at 1 meter. Real apps should
        // calibrate this per device/tx-power if possible.
        val txPowerAt1m = -59

        // Path loss exponent: ~2 in free space, higher (3-4)
        // indoors or with obstructions. 2.5 is a reasonable
        // middle ground for outdoor disaster scenarios.
        val pathLossExponent = 2.5

        val ratio =
            (txPowerAt1m - rssi) /
                    (10.0 * pathLossExponent)

        return Math.pow(10.0, ratio)
    }

    private fun rssiQuality(
        rssi: Int
    ): String {

        return when {

            rssi >= -60 -> "Strong"
            rssi >= -75 -> "Medium"
            rssi >= -90 -> "Weak"
            else -> "Very Weak"
        }
    }

    override fun onDestroy() {

        bleManager.stopScanning()

        bleManager.stopAdvertising()

        super.onDestroy()
    }
}