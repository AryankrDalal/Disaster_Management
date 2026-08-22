package com.example.disastermanagement

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private lateinit var packetStore: PacketStore
    private lateinit var locationManager: LocationManager

    private lateinit var statusText: TextView

    private val messageCounter =
        AtomicInteger(1000)

    companion object {

        private const val PERMISSION_REQUEST_CODE = 100

        const val ACTION_SOS_RECEIVED =
            "com.example.disastermanagement.SOS_RECEIVED"
    }

    // --------------------------------------------------
    // RECEIVE MESSAGES FROM MESH SERVICE
    // --------------------------------------------------

    private val sosReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (intent?.action != ACTION_SOS_RECEIVED) {
                    return
                }

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
    }

    override fun onStop() {

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

        // --------------------------------------------------
        // START RELAY
        // --------------------------------------------------

        val relayButton =
            Button(this)

        relayButton.text =
            "START MESH RELAY"

        relayButton.setOnClickListener {

            startMeshService()
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

    private fun nodeIdToString(
        id: Short
    ): String {

        return (
                id.toInt() and 0xFFFF
                ).toString()
    }

    override fun onDestroy() {

        bleManager.stopScanning()

        bleManager.stopAdvertising()

        super.onDestroy()
    }
}