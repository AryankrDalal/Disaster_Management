package com.example.disastermanagement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

class MeshService : Service() {

    private lateinit var bleManager: BleManager

    private lateinit var packetStore: PacketStore

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.Main
        )

    override fun onCreate() {

        super.onCreate()

        bleManager =
            BleManager(this)

        packetStore =
            PacketStore(this)

        createNotificationChannel()

        startForeground(
            1001,
            createNotification()
        )

        startMesh()
    }

    private fun startMesh() {

        bleManager.startScanning { packet ->

            handlePacket(packet)
        }
    }

    private fun handlePacket(
        packet: SosPacket
    ) {

        val myNodeId =
            packetStore.getNodeId()

        /*
         * Prevent duplicate forwarding
         */

        if (
            packetStore.hasSeen(
                packet.messageId
            )
        ) {

            return
        }

        packetStore.markSeen(
            packet.messageId
        )

        /*
         * If TTL has expired,
         * don't forward.
         */

        if (packet.ttl.toInt() <= 0) {

            updateNotification(
                "SOS received - TTL expired"
            )

            return
        }

        /*
         * The phone receiving this packet
         * becomes the current relay.
         */

        val newTtl =
            (
                    packet.ttl.toInt() - 1
                    ).toByte()

        val forwardedPacket =
            packet.copy(

                /*
                 * Source remains unchanged.
                 */
                sourceId =
                    packet.sourceId,

                /*
                 * Current phone becomes
                 * the new relay.
                 */
                relayId =
                    myNodeId,

                ttl =
                    newTtl
            )

        /*
         * Show RELAYING information.
         */

        updateNotification(
            """
            FORWARDING SOS

            Source:
            ${nodeIdToString(packet.sourceId)}

            Previous Relay:
            ${nodeIdToString(packet.relayId)}

            Destination:
            ${nodeIdToString(myNodeId)}

            Source Latitude:
            ${packet.sourceLatitude}

            Source Longitude:
            ${packet.sourceLongitude}

            Destination GPS:
            This phone's GPS

            TTL:
            ${packet.ttl} → $newTtl

            Message:
            ${packet.messageId}
            """.trimIndent()
        )

        /*
         * Forward the packet.
         */

        bleManager.advertise(
            forwardedPacket
        )

        /*
         * Continue forwarding for 30 seconds.
         */

        serviceScope.launch {

            delay(30_000)

            bleManager.stopAdvertising()

            updateNotification(
                "RELAY READY - Listening..."
            )
        }
    }

    private fun nodeIdToString(
        id: Short
    ): String {

        return (id.toInt() and 0xFFFF)
            .toString()
    }

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

    override fun onDestroy() {

        serviceScope.cancel()

        bleManager.stopScanning()

        bleManager.stopAdvertising()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}