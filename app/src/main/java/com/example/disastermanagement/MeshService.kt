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

    // --------------------------------------------------
    // LIVE SIGNAL STRENGTH
    // --------------------------------------------------

    // nodeId of transmitter -> last known RSSI (dBm)
    private val nodeSignalStrength =
        ConcurrentHashMap<Short, Int>()

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

    // --------------------------------------------------
    // START MESH
    // --------------------------------------------------

    private fun startMesh() {

        bleManager.startScanning { packet, rssi ->

            // Signal strength updates on EVERY scan result,
            // even for duplicate/already-seen packets, so the
            // UI reflects live distance changes as phones move.
            handleSignalUpdate(
                packet.relayId,
                rssi
            )

            handlePacket(packet)
        }

        // Broadcast a lightweight "I'm listening" presence
        // beacon right away. Without this, this phone never
        // transmits anything until it actually forwards a
        // real SOS (which needs GPS first and only lasts 30
        // seconds) - so a source phone's own scanner would
        // have nothing to detect and never show a connection.
        bleManager.advertise(
            createBeaconPacket()
        )
    }

    // --------------------------------------------------
    // PRESENCE BEACON
    // --------------------------------------------------

    // messageId 0 is reserved to mean "presence beacon, not a
    // real SOS" - it lets nearby phones see this device and
    // its live RSSI at all times while mesh relay is running.
    private fun createBeaconPacket(): SosPacket {

        val myNodeId =
            packetStore.getNodeId()

        return SosPacket(
            messageId = 0,
            sourceId = myNodeId,
            relayId = myNodeId,
            sourceLatitude = 0f,
            sourceLongitude = 0f,
            ttl = 0
        )
    }

    // --------------------------------------------------
    // HANDLE LIVE SIGNAL UPDATE
    // --------------------------------------------------

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

    // --------------------------------------------------
    // HANDLE RECEIVED SOS
    // --------------------------------------------------

    private fun handlePacket(
        packet: SosPacket
    ) {

        // Presence beacons already fed the signal display via
        // handleSignalUpdate() above - they're not real SOS
        // traffic and should never enter the relay/dedup logic.
        if (packet.messageId == 0) {
            return
        }

        val myNodeId =
            packetStore.getNodeId()

        // ----------------------------------------------
        // CHECK DUPLICATE
        // ----------------------------------------------

        if (
            packetStore.hasSeen(
                packet.messageId
            )
        ) {

            println(
                "MESH: Duplicate packet ignored: " +
                        packet.messageId
            )

            return
        }

        // ----------------------------------------------
        // MARK PACKET AS SEEN
        // ----------------------------------------------

        packetStore.markSeen(
            packet.messageId
        )

        // ----------------------------------------------
        // SHOW SOS ON PHONE UI
        // ----------------------------------------------

        sendSosToUI(
            """
            ╔══════════════════════════╗
                   🚨 SOS RECEIVED
            ╚══════════════════════════╝
            
            MESSAGE ID:
            ${packet.messageId}
            
            SOURCE DEVICE:
            ${nodeIdToString(packet.sourceId)}
            
            SOURCE GPS:
            ${packet.sourceLatitude},
            ${packet.sourceLongitude}
            
            TTL:
            ${packet.ttl}
            
            STATUS:
            PROCESSING...
            """.trimIndent()
        )

        // ----------------------------------------------
        // CHECK TTL
        // ----------------------------------------------

        if (
            packet.ttl.toInt() <= 0
        ) {

            updateNotification(
                """
                SOS RECEIVED
                
                TTL EXPIRED
                
                Source:
                ${
                    nodeIdToString(
                        packet.sourceId
                    )
                }
                """.trimIndent()
            )

            sendSosToUI(
                """
                ╔══════════════════════════╗
                       🚨 SOS RECEIVED
                ╚══════════════════════════╝
                
                SOURCE DEVICE:
                ${
                    nodeIdToString(
                        packet.sourceId
                    )
                }
                
                SOURCE GPS:
                ${packet.sourceLatitude},
                ${packet.sourceLongitude}
                
                TTL:
                EXPIRED
                
                STATUS:
                NOT FORWARDED
                """.trimIndent()
            )

            return
        }

        // ----------------------------------------------
        // GET CURRENT RELAY GPS
        // ----------------------------------------------

        updateNotification(
            """
            SOS RECEIVED
            
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
            """.trimIndent()
        )

        sendSosToUI(
            """
            ╔══════════════════════════╗
                   🚨 SOS RECEIVED
            ╚══════════════════════════╝
            
            SOURCE DEVICE:
            ${
                nodeIdToString(
                    packet.sourceId
                )
            }
            
            SOURCE GPS:
            ${packet.sourceLatitude},
            ${packet.sourceLongitude}
            
            STATUS:
            GETTING CURRENT GPS...
            """.trimIndent()
        )

        locationManager.getCurrentLocation(

            onLocationReceived = { gps ->

                serviceScope.launch {

                    relayPacket(
                        packet,
                        gps,
                        myNodeId
                    )
                }
            },

            onError = { error ->

                updateNotification(
                    """
                    SOS RECEIVED
                    
                    GPS ERROR
                    
                    $error
                    
                    Packet will NOT
                    be forwarded.
                    """.trimIndent()
                )

                sendSosToUI(
                    """
                    ╔══════════════════════════╗
                           🚨 SOS RECEIVED
                    ╚══════════════════════════╝
                    
                    SOURCE DEVICE:
                    ${
                        nodeIdToString(
                            packet.sourceId
                        )
                    }
                    
                    SOURCE GPS:
                    ${packet.sourceLatitude},
                    ${packet.sourceLongitude}
                    
                    STATUS:
                    GPS ERROR
                    
                    $error
                    
                    NOT FORWARDED
                    """.trimIndent()
                )
            }
        )
    }

    // --------------------------------------------------
    // RELAY PACKET
    // --------------------------------------------------

    private fun relayPacket(
        packet: SosPacket,
        gps: GpsLocation,
        myNodeId: Short
    ) {

        val newTtl =
            (
                    packet.ttl.toInt() - 1
                    ).toByte()

        val forwardedPacket =
            packet.copy(

                // Original source remains unchanged
                sourceId =
                    packet.sourceId,

                // This phone becomes the new relay
                relayId =
                    myNodeId,

                // Original source GPS remains unchanged
                sourceLatitude =
                    packet.sourceLatitude,

                sourceLongitude =
                    packet.sourceLongitude,

                // TTL decreases
                ttl =
                    newTtl
            )

        // ----------------------------------------------
        // SHOW FORWARDING ON PHONE UI
        // ----------------------------------------------

        sendSosToUI(
            """
            ╔══════════════════════════╗
                   🚨 SOS RELAY
            ╚══════════════════════════╝
            
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
            
            SOURCE GPS:
            ${packet.sourceLatitude},
            ${packet.sourceLongitude}
            
            CURRENT GPS:
            ${gps.latitude},
            ${gps.longitude}
            
            TTL:
            ${packet.ttl} → $newTtl
            """.trimIndent()
        )

        // ----------------------------------------------
        // UPDATE NOTIFICATION
        // ----------------------------------------------

        updateNotification(
            """
            ╔══════════════════════╗
                  SOS RELAY
            ╚══════════════════════╝
            
            STATUS:
            FORWARDING
            
            MESSAGE:
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
            
            SOURCE GPS:
            ${packet.sourceLatitude},
            ${packet.sourceLongitude}
            
            CURRENT GPS:
            ${gps.latitude},
            ${gps.longitude}
            
            TTL:
            ${packet.ttl} → $newTtl
            """.trimIndent()
        )

        // ----------------------------------------------
        // FORWARD SOS
        // ----------------------------------------------

        bleManager.advertise(
            forwardedPacket
        )

        // ----------------------------------------------
        // STOP FORWARDING AFTER 30 SECONDS
        // ----------------------------------------------

        serviceScope.launch {

            delay(30_000)

            // Resume the presence beacon rather than going
            // silent, so this phone stays visible/live on
            // nearby phones' signal panels between SOS events.
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

    // --------------------------------------------------
    // SEND MESSAGE TO MAIN ACTIVITY
    // --------------------------------------------------

    private fun sendSosToUI(
        message: String
    ) {

        val intent =
            Intent(
                MainActivity.ACTION_SOS_RECEIVED
            )

        /*
         * Keep the broadcast inside
         * our own application.
         */

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

        println(
            "MESH UI UPDATE:\n$message"
        )
    }

    // --------------------------------------------------
    // NODE ID
    // --------------------------------------------------

    private fun nodeIdToString(
        id: Short
    ): String {

        return (
                id.toInt() and 0xFFFF
                ).toString()
    }

    // --------------------------------------------------
    // NOTIFICATION
    // --------------------------------------------------

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

    // --------------------------------------------------
    // NOTIFICATION CHANNEL
    // --------------------------------------------------

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

    // --------------------------------------------------
    // CREATE NOTIFICATION
    // --------------------------------------------------

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

    // --------------------------------------------------
    // SERVICE DESTROYED
    // --------------------------------------------------

    override fun onDestroy() {

        serviceScope.cancel()

        bleManager.stopScanning()

        bleManager.stopAdvertising()

        super.onDestroy()
    }

    // --------------------------------------------------
    // BIND
    // --------------------------------------------------

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}