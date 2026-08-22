package com.example.disastermanagement

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SosPacket(

    val messageId: Int,

    // Original SOS creator
    val sourceId: Short,

    // Most recent relay
    val relayId: Short,

    // GPS of original SOS creator
    val sourceLatitude: Float,
    val sourceLongitude: Float,

    val ttl: Byte

) {

    fun toBytes(): ByteArray {

        val buffer = ByteBuffer
            .allocate(17)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(messageId)

        buffer.putShort(sourceId)

        buffer.putShort(relayId)

        buffer.putFloat(sourceLatitude)

        buffer.putFloat(sourceLongitude)

        buffer.put(ttl)

        return buffer.array()
    }

    companion object {

        fun fromBytes(
            data: ByteArray
        ): SosPacket? {

            if (data.size < 17) {
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
                        ttl
                )

            } catch (e: Exception) {

                null
            }
        }
    }
}