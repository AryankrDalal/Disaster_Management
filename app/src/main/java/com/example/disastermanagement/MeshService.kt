package com.example.disastermanagement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class MeshService : Service() {

    private lateinit var bleManager: BleManager

    private lateinit var packetStore: PacketStore

    private lateinit var locationManager: LocationManager

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.Main
        )

    private val nodeSignalStrength =
        ConcurrentHashMap<Short, Int>()

    // ==================================================
    // SERVICE START
    // ==================================================

    override fun onCreate() {

        super.onCreate()

        bleManager =
            BleManager(this)

        packetStore =
            PacketStore(this)

        locationManager =
            LocationManager(this)

        createNotificationChannel()

        startForeground(
            1001,
            createNotification()
        )

        startMesh()
    }

    // ==================================================
    // START BLE SCANNING
    // ==================================================

    private fun startMesh() {

        bleManager.startScanning { packet, rssi ->

            handleSignalUpdate(
                packet.relayId,
                rssi
            )

            handlePacket(
                packet,
                rssi
            )
        }

        bleManager.advertise(
            createBeaconPacket()
        )
    }

    // ==================================================
    // PRESENCE BEACON
    // ==================================================

    private fun createBeaconPacket(): SosPacket {

        val myNodeId =
            packetStore.getNodeId()

        return SosPacket(
            messageId =
                0,

            sourceId =
                myNodeId,

            relayId =
                myNodeId,

            sourceLatitude =
                0f,

            sourceLongitude =
                0f,

            ttl =
                0,

            sosType =
                0
        )
    }

    // ==================================================
    // HANDLE LIVE SIGNAL UPDATE
    // ==================================================

    private fun handleSignalUpdate(
        nodeId: Short,
        rssi: Int
    ) {

        nodeSignalStrength[nodeId] =
            rssi

        val intent =
            Intent(
                MainActivity.ACTION_SIGNAL_UPDATE
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            "nodeId",
            nodeId
        )

        intent.putExtra(
            "rssi",
            rssi
        )

        sendBroadcast(
            intent
        )
    }

    // ==================================================
    // HANDLE RECEIVED PACKET
    // ==================================================

    private fun handlePacket(
        packet: SosPacket,
        rssi: Int
    ) {

        if (packet.messageId == 0) {
            return
        }

        val myNodeId =
            packetStore.getNodeId()

        // --------------------------------------------------
        // IGNORE DUPLICATE
        // --------------------------------------------------

        if (
            packetStore.hasSeen(
                packet.messageId
            )
        ) {

            return
        }

        // --------------------------------------------------
        // MARK AS SEEN
        // --------------------------------------------------

        packetStore.markSeen(
            packet.messageId
        )

        val sosName =
            SosPacket.getSosTypeName(
                packet.sosType
            )

        // --------------------------------------------------
        // SHOW SOS RECEIVED
        // --------------------------------------------------

        sendSosToUI(

            """
            ╔══════════════════════════╗
                   🚨 SOS RECEIVED
            ╚══════════════════════════╝
            
            SOS:
            $sosName
            
            MESSAGE ID:
            ${packet.messageId}
            
            SOURCE DEVICE:
            ${
                nodeIdToString(
                    packet.sourceId
                )
            }
            
            SOURCE LOCATION:
            ${packet.sourceLatitude},
            ${packet.sourceLongitude}
            
            RECEIVED VIA:
            BLUETOOTH BLE

            SIGNAL:
            $rssi dBm
            
            TTL:
            ${packet.ttl}
            
            STATUS:
            PROCESSING...
            """.trimIndent()
        )

        // --------------------------------------------------
        // TTL CHECK
        // --------------------------------------------------

        if (
            packet.ttl.toInt() <= 0
        ) {

            sendSosToUI(

                """
                ╔══════════════════════════╗
                       🚨 SOS RECEIVED
                ╚══════════════════════════╝
                
                SOS:
                $sosName
                
                SOURCE DEVICE:
                ${
                    nodeIdToString(
                        packet.sourceId
                    )
                }
                
                SOURCE LOCATION:
                ${packet.sourceLatitude},
                ${packet.sourceLongitude}
                
                RECEIVED VIA:
                BLUETOOTH BLE

                SIGNAL:
                $rssi dBm
                
                TTL:
                EXPIRED
                
                STATUS:
                NOT FORWARDED
                """.trimIndent()
            )

            return
        }

        // --------------------------------------------------
        // GET RELAY GPS
        // --------------------------------------------------

        updateNotification(

            """
            SOS RECEIVED
            
            SOS:
            $sosName
            
            Getting relay GPS...
            
            Source:
            ${
                nodeIdToString(
                    packet.sourceId
                )
            }
            
            Source GPS:
            ${packet.sourceLatitude},
            ${packet.sourceLongitude}

            Signal:
            $rssi dBm
            """.trimIndent()
        )

        locationManager.getCurrentLocation(

            onLocationReceived = { gps ->

                serviceScope.launch {

                    relayPacket(
                        packet,
                        gps,
                        myNodeId,
                        rssi
                    )
                }
            },

            onError = { error ->

                updateNotification(

                    """
                    SOS RECEIVED
                    
                    SOS:
                    $sosName
                    
                    GPS ERROR
                    
                    $error
                    
                    Packet will NOT
                    be forwarded.
                    """.trimIndent()
                )

                sendSosToUI(

                    """
                    🚨 SOS RECEIVED
                    
                    SOS:
                    $sosName
                    
                    SOURCE DEVICE:
                    ${
                        nodeIdToString(
                            packet.sourceId
                        )
                    }
                    
                    SOURCE LOCATION:
                    ${packet.sourceLatitude},
                    ${packet.sourceLongitude}
                    
                    RECEIVED VIA:
                    BLUETOOTH BLE

                    SIGNAL:
                    $rssi dBm
                    
                    STATUS:
                    GPS ERROR
                    
                    NOT FORWARDED
                    """.trimIndent()
                )
            }
        )
    }

    // ==================================================
    // RELAY / FORWARD PACKET
    // ==================================================

    private fun relayPacket(
        packet: SosPacket,
        gps: GpsLocation,
        myNodeId: Short,
        rssi: Int
    ) {

        val newTtl =
            (
                    packet.ttl.toInt() - 1
                    ).toByte()

        /*
         * IMPORTANT:
         *
         * sourceId stays the same.
         *
         * sourceLatitude stays the same.
         *
         * sourceLongitude stays the same.
         *
         * sosType stays the same.
         *
         * Only relayId and TTL change.
         */

        val forwardedPacket =
            packet.copy(

                sourceId =
                    packet.sourceId,

                relayId =
                    myNodeId,

                sourceLatitude =
                    packet.sourceLatitude,

                sourceLongitude =
                    packet.sourceLongitude,

                ttl =
                    newTtl
            )

        val sosName =
            SosPacket.getSosTypeName(
                packet.sosType
            )

        // --------------------------------------------------
        // SHOW FORWARDING ON PHONE B
        // --------------------------------------------------

        sendSosToUI(

            """
            ╔══════════════════════════╗
                   🚨 SOS RELAY
            ╚══════════════════════════╝
            
            SOS:
            $sosName
            
            STATUS:
            FORWARDING
            
            MESSAGE ID:
            ${packet.messageId}
            
            SOURCE DEVICE:
            ${
                nodeIdToString(
                    packet.sourceId
                )
            }
            
            PREVIOUS RELAY:
            ${
                nodeIdToString(
                    packet.relayId
                )
            }
            
            CURRENT RELAY:
            ${
                nodeIdToString(
                    myNodeId
                )
            }
            
            SOURCE LOCATION:
            ${packet.sourceLatitude},
            ${packet.sourceLongitude}
            
            CURRENT RELAY LOCATION:
            ${gps.latitude},
            ${gps.longitude}

            SIGNAL:
            $rssi dBm
            
            TTL:
            ${packet.ttl} → $newTtl
            
            RECEIVED VIA:
            BLUETOOTH BLE
            
            FORWARDING VIA:
            BLUETOOTH BLE
            """.trimIndent()
        )

        // --------------------------------------------------
        // NOTIFICATION
        // --------------------------------------------------

        updateNotification(

            """
            SOS RELAY
            
            $sosName
            
            Source:
            ${
                nodeIdToString(
                    packet.sourceId
                )
            }
            
            Relay:
            ${
                nodeIdToString(
                    myNodeId
                )
            }
            
            TTL:
            ${packet.ttl} → $newTtl

            Signal:
            $rssi dBm
            """.trimIndent()
        )

        // --------------------------------------------------
        // FORWARD THROUGH BLE
        // --------------------------------------------------

        bleManager.advertise(
            forwardedPacket
        )

        // --------------------------------------------------
        // ADVERTISE FOR 30 SECONDS
        // --------------------------------------------------

        serviceScope.launch {

            delay(30_000)

            bleManager.advertise(
                createBeaconPacket()
            )

            updateNotification(

                """
                RELAY READY
                
                Listening for SOS...
                """.trimIndent()
            )

            sendSosToUI(

                """
                MESH SOS
                
                STATUS:
                RELAY READY
                
                Listening for SOS...
                """.trimIndent()
            )
        }
    }

    // ==================================================
    // SEND SOS INFORMATION TO MAIN ACTIVITY
    // ==================================================

    private fun sendSosToUI(
        message: String
    ) {

        val intent =
            Intent(
                MainActivity.ACTION_SOS_RECEIVED
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            "message",
            message
        )

        sendBroadcast(
            intent
        )
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
    // NOTIFICATION
    // ==================================================

    private fun updateNotification(
        message: String
    ) {

        println(
            "MESH:\n$message"
        )

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            1001,
            createNotification(
                message
            )
        )
    }

    // ==================================================
    // NOTIFICATION CHANNEL
    // ==================================================

    private fun createNotificationChannel() {

        val channel =
            NotificationChannel(
                "MESH_CHANNEL",
                "Mesh SOS",
                NotificationManager.IMPORTANCE_LOW
            )

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(
            channel
        )
    }

    // ==================================================
    // CREATE NOTIFICATION
    // ==================================================

    private fun createNotification(
        message: String =
            "Mesh relay is active"
    ): Notification {

        return Notification.Builder(
            this,
            "MESH_CHANNEL"
        )
            .setContentTitle(
                "Mesh SOS"
            )
            .setContentText(
                message.replace(
                    "\n",
                    " "
                )
            )
            .setSmallIcon(
                android.R.drawable.ic_dialog_alert
            )
            .build()
    }

    // ==================================================
    // SERVICE DESTROY
    // ==================================================

    override fun onDestroy() {

        serviceScope.cancel()

        bleManager.stopScanning()

        bleManager.stopAdvertising()

        super.onDestroy()
    }

    // ==================================================
    // BIND
    // ==================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
