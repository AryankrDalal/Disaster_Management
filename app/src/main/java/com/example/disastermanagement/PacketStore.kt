package com.example.disastermanagement

import android.content.Context
import kotlin.random.Random

class PacketStore(context: Context) {

    private val preferences =
        context.getSharedPreferences(
            "mesh_preferences",
            Context.MODE_PRIVATE
        )

    private val seenMessages =
        HashSet<Int>()

    fun getNodeId(): Short {

        var nodeId =
            preferences.getInt(
                "node_id",
                -1
            )

        if (nodeId == -1) {

            nodeId =
                Random.nextInt(
                    1,
                    65535
                )

            preferences.edit()
                .putInt(
                    "node_id",
                    nodeId
                )
                .apply()
        }

        return nodeId.toShort()
    }

    @Synchronized
    fun hasSeen(messageId: Int): Boolean {

        return seenMessages.contains(
            messageId
        )
    }

    @Synchronized
    fun markSeen(messageId: Int) {

        seenMessages.add(
            messageId
        )
    }

    @Synchronized
    fun clear() {

        seenMessages.clear()
    }
}