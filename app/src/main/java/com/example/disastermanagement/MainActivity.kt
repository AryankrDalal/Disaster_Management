package com.example.disastermanagement

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private lateinit var statusText: TextView

    private lateinit var packetStore: PacketStore

    private val messageCounter =
        AtomicInteger(1000)

    companion object {

        private const val PERMISSION_REQUEST_CODE = 100

        private const val BLUETOOTH_ENABLE_REQUEST = 101
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        bleManager = BleManager(this)

        packetStore = PacketStore(this)

        createUI()

        requestPermissions()
    }

    private fun createUI() {

        val layout = LinearLayout(this)

        layout.orientation =
            LinearLayout.VERTICAL

        layout.setPadding(
            40,
            60,
            40,
            40
        )

        val nodeIdText = TextView(this)

        nodeIdText.text =
            "DEVICE NODE ID: ${nodeIdToString(packetStore.getNodeId())}"

        nodeIdText.textSize = 18f

        layout.addView(nodeIdText)

        statusText = TextView(this)

        statusText.text =
            """

            Mesh SOS

            Status: READY

            """.trimIndent()

        statusText.textSize = 18f

        layout.addView(statusText)

        val listenButton =
            Button(this)

        listenButton.text =
            "START MESH RELAY"

        listenButton.setOnClickListener {

            startMeshService()
        }

        layout.addView(listenButton)

        val sosButton =
            Button(this)

        sosButton.text =
            "SEND SOS"

        sosButton.setOnClickListener {

            sendSOS()
        }

        layout.addView(sosButton)

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
                Mesh SOS

                Status: MESH STOPPED
                """.trimIndent()
        }

        layout.addView(stopButton)

        setContentView(layout)
    }

    private fun startMeshService() {

        val intent =
            Intent(
                this,
                MeshService::class.java
            )

        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            startForegroundService(intent)

        } else {

            startService(intent)
        }

        statusText.text =
            """
            Mesh SOS

            Status: RELAY ACTIVE

            Device:
            ${nodeIdToString(packetStore.getNodeId())}

            Listening for SOS...
            """.trimIndent()
    }

    private fun sendSOS() {

        if (!checkBluetooth()) {
            return
        }

        val messageId =
            messageCounter.incrementAndGet()

        val sourceId =
            packetStore.getNodeId()

        /*
         * Temporary GPS coordinates.
         *
         * We will replace these with
         * real GPS later.
         */

        val latitude =
            26.9124f

        val longitude =
            75.7873f

        val packet =
            SosPacket(

                messageId = messageId,

                sourceId = sourceId,

                // Sender is the first relay
                relayId = sourceId,

                sourceLatitude = latitude,

                sourceLongitude = longitude,

                ttl = 5
            )

        bleManager.advertise(packet)

        statusText.text =
            """
            MESH SOS

            STATUS: SOS TRANSMITTING

            SOURCE DEVICE:
            ${nodeIdToString(sourceId)}

            DESTINATION:
            ANY NEARBY RELAY

            SOURCE LATITUDE:
            $latitude

            SOURCE LONGITUDE:
            $longitude

            TTL:
            ${packet.ttl}

            MESSAGE ID:
            $messageId
            """.trimIndent()

        Toast.makeText(
            this,
            "SOS transmission started",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun nodeIdToString(
        id: Short
    ): String {

        return (id.toInt() and 0xFFFF)
            .toString()
    }

    private fun checkBluetooth(): Boolean {

        if (!bleManager.isBluetoothAvailable()) {

            Toast.makeText(
                this,
                "Please turn Bluetooth ON",
                Toast.LENGTH_LONG
            ).show()

            return false
        }

        return true
    }

    private fun requestPermissions() {

        val permissions =
            mutableListOf<String>()

        if (Build.VERSION.SDK_INT >=
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

        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        permissions.add(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val missingPermissions =
            permissions.filter {

                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missingPermissions.isNotEmpty()) {

            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onDestroy() {

        bleManager.stopScanning()

        bleManager.stopAdvertising()

        super.onDestroy()
    }
}