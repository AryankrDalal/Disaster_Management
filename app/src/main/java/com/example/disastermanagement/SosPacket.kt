package com.example.disastermanagement

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SosPacket(

    // Unique ID of this SOS
    val messageId: Int,

    // Original device that created the SOS
    val sourceId: Short,

    // Most recent device that relayed the SOS
    val relayId: Short,

    // GPS location of the ORIGINAL source
    val sourceLatitude: Float,

    val sourceLongitude: Float,

    // Number of hops remaining
    val ttl: Byte,

    // SOS type
    val sosType: Byte

) {

    // ==================================================
    // CONVERT PACKET TO BLE DATA
    // ==================================================

    fun toBytes(): ByteArray {

        /*
         * Packet size:
         *
         * messageId       = 4 bytes
         * sourceId        = 2 bytes
         * relayId         = 2 bytes
         * latitude        = 4 bytes
         * longitude       = 4 bytes
         * ttl             = 1 byte
         * sosType         = 1 byte
         *
         * TOTAL = 18 bytes
         */

        val buffer =
            ByteBuffer
                .allocate(18)
                .order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(messageId)

        buffer.putShort(sourceId)

        buffer.putShort(relayId)

        buffer.putFloat(sourceLatitude)

        buffer.putFloat(sourceLongitude)

        buffer.put(ttl)

        buffer.put(sosType)

        return buffer.array()
    }

    // ==================================================
    // CONVERT BLE DATA BACK TO PACKET
    // ==================================================

    companion object {

        // --------------------------------------------------
        // SOS TYPES
        // --------------------------------------------------

        const val MEDICAL = 1

        const val FIRE = 2

        const val TRAPPED = 3

        const val ACCIDENT = 4

        const val NEED_WATER = 5

        const val EVACUATION = 6

        // --------------------------------------------------
        // GET HUMAN-READABLE SOS NAME
        // --------------------------------------------------

        fun getSosTypeName(
            type: Byte
        ): String {

            return when (type.toInt()) {

                MEDICAL ->
                    "🚑 Medical Emergency"

                FIRE ->
                    "🔥 Fire"

                TRAPPED ->
                    "🆘 Trapped"

                ACCIDENT ->
                    "⚠️ Accident"

                NEED_WATER ->
                    "💧 Need Water"

                EVACUATION ->
                    "🏠 Evacuation Required"

                else ->
                    "❓ Unknown SOS"
            }
        }

        // --------------------------------------------------
        // DECODE BLE PACKET
        // --------------------------------------------------

        fun fromBytes(
            data: ByteArray
        ): SosPacket? {

            if (data.size < 18) {
                return null
            }

            return try {

                val buffer =
                    ByteBuffer
                        .wrap(data)
                        .order(ByteOrder.BIG_ENDIAN)

                val messageId =
                    buffer.int

                val sourceId =
                    buffer.short

                val relayId =
                    buffer.short

                val sourceLatitude =
                    buffer.float

                val sourceLongitude =
                    buffer.float

                val ttl =
                    buffer.get()

                val sosType =
                    buffer.get()

                SosPacket(

                    messageId =
                        messageId,

                    sourceId =
                        sourceId,

                    relayId =
                        relayId,

                    sourceLatitude =
                        sourceLatitude,

                    sourceLongitude =
                        sourceLongitude,

                    ttl =
                        ttl,

                    sosType =
                        sosType
                )

            } catch (e: Exception) {

                null
            }
        }
    }
}