package com.example.disastermanagement

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

class BleManager(private val context: Context) {

    companion object {

        // Must be the SAME on every phone
        private const val MANUFACTURER_ID = 0x1234
    }

    private val bluetoothManager =
        context.getSystemService(
            Context.BLUETOOTH_SERVICE
        ) as BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private val advertiser: BluetoothLeAdvertiser?
        get() = bluetoothAdapter?.bluetoothLeAdvertiser

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private var scanCallback: ScanCallback? = null

    // --------------------------------------------------
    // ADVERTISING
    // --------------------------------------------------

    @SuppressLint("MissingPermission")
    fun advertise(packet: SosPacket) {

        val data = packet.toBytes()

        println(
            "BLE: Preparing advertisement"
        )

        println(
            "BLE: Packet size = ${data.size} bytes"
        )

        println(
            "BLE: Message ID = ${packet.messageId}"
        )

        println(
            "BLE: Source ID = ${packet.sourceId}"
        )

        println(
            "BLE: TTL = ${packet.ttl}"
        )

        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(
                    AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                )
                .setTxPowerLevel(
                    AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                )
                .setConnectable(false)
                .setTimeout(0)
                .build()

        val advertiseData =
            AdvertiseData.Builder()
                .addManufacturerData(
                    MANUFACTURER_ID,
                    data
                )
                .setIncludeDeviceName(false)
                .build()

        val bleAdvertiser =
            advertiser

        if (bleAdvertiser == null) {

            println(
                "BLE ERROR: Advertiser unavailable"
            )

            return
        }

        // Stop any advertisement already in flight first.
        // Without this, switching between the idle presence
        // beacon and a real SOS payload can fail with
        // ADVERTISE_FAILED_ALREADY_STARTED.
        try {

            bleAdvertiser.stopAdvertising(
                advertiseCallback
            )

        } catch (e: Exception) {

            println(
                "BLE: No previous advertisement to stop"
            )
        }

        bleAdvertiser.startAdvertising(
            settings,
            advertiseData,
            advertiseCallback
        )

        println(
            "BLE: startAdvertising() called"
        )
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {

        advertiser?.stopAdvertising(
            advertiseCallback
        )

        println(
            "BLE: Advertising stopped"
        )
    }

    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect:
                AdvertiseSettings?
            ) {

                println(
                    "BLE SUCCESS: Advertising started"
                )
            }

            override fun onStartFailure(
                errorCode: Int
            ) {

                println(
                    "BLE ERROR: Advertising failed"
                )

                println(
                    "BLE ERROR CODE: $errorCode"
                )

                val errorMessage =
                    when (errorCode) {

                        ADVERTISE_FAILED_ALREADY_STARTED ->
                            "ALREADY_STARTED"

                        ADVERTISE_FAILED_DATA_TOO_LARGE ->
                            "DATA_TOO_LARGE"

                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                            "FEATURE_UNSUPPORTED"

                        ADVERTISE_FAILED_INTERNAL_ERROR ->
                            "INTERNAL_ERROR"

                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                            "TOO_MANY_ADVERTISERS"

                        else ->
                            "UNKNOWN_ERROR"
                    }

                println(
                    "BLE ERROR: $errorMessage"
                )
            }
        }

    // --------------------------------------------------
    // SCANNING
    // --------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScanning(
        onPacketReceived:
            (SosPacket, rssi: Int) -> Unit
    ) {

        println(
            "BLE: Starting scanner..."
        )

        val bleScanner =
            scanner

        if (bleScanner == null) {

            println(
                "BLE ERROR: Scanner unavailable"
            )

            return
        }

        /*
         * Stop an old scan first.
         */

        scanCallback?.let {

            try {

                bleScanner.stopScan(it)

            } catch (e: Exception) {

                println(
                    "BLE: Previous scan not active"
                )
            }
        }

        val settings =
            ScanSettings.Builder()
                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                )
                .setReportDelay(0)
                .build()

        scanCallback =
            object : ScanCallback() {

                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult
                ) {

                    val scanRecord =
                        result.scanRecord

                    val rssi =
                        result.rssi

                    if (scanRecord == null) {

                        println(
                            "BLE: Scan result has no record"
                        )

                        return
                    }

                    println(
                        "BLE: Device detected"
                    )

                    val manufacturerData =
                        scanRecord
                            .manufacturerSpecificData

                    if (
                        manufacturerData.size() == 0
                    ) {

                        println(
                            "BLE: No manufacturer data"
                        )

                        return
                    }

                    for (
                    i in 0 until
                            manufacturerData.size()
                    ) {

                        val manufacturerId =
                            manufacturerData.keyAt(i)

                        println(
                            "BLE: Manufacturer ID = " +
                                    "0x" +
                                    manufacturerId
                                        .toString(16)
                        )

                        if (
                            manufacturerId !=
                            MANUFACTURER_ID
                        ) {

                            continue
                        }

                        val data =
                            manufacturerData
                                .valueAt(i)

                        println(
                            "BLE: Correct manufacturer found"
                        )

                        println(
                            "BLE: Received data size = " +
                                    "${data.size} bytes"
                        )

                        val packet =
                            SosPacket.fromBytes(
                                data
                            )

                        if (packet == null) {

                            println(
                                "BLE ERROR: Could not decode packet"
                            )

                            return
                        }

                        println(
                            "================================"
                        )

                        println(
                            "BLE: SOS PACKET RECEIVED"
                        )

                        println(
                            "Message ID: " +
                                    packet.messageId
                        )

                        println(
                            "Source ID: " +
                                    packet.sourceId
                        )

                        println(
                            "Relay ID: " +
                                    packet.relayId
                        )

                        println(
                            "Latitude: " +
                                    packet.sourceLatitude
                        )

                        println(
                            "Longitude: " +
                                    packet.sourceLongitude
                        )

                        println(
                            "TTL: " +
                                    packet.ttl
                        )

                        println(
                            "RSSI: " +
                                    "$rssi dBm"
                        )

                        println(
                            "================================"
                        )

                        onPacketReceived(
                            packet,
                            rssi
                        )
                    }
                }

                override fun onBatchScanResults(
                    results:
                    MutableList<ScanResult>
                ) {

                    println(
                        "BLE: Batch results = " +
                                results.size
                    )
                }

                override fun onScanFailed(
                    errorCode: Int
                ) {

                    println(
                        "================================"
                    )

                    println(
                        "BLE ERROR: Scan failed"
                    )

                    println(
                        "BLE ERROR CODE: $errorCode"
                    )

                    println(
                        "================================"
                    )
                }
            }

        bleScanner.startScan(
            null,
            settings,
            scanCallback
        )

        println(
            "BLE: Scanning started successfully"
        )
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {

        scanCallback?.let {

            scanner?.stopScan(it)
        }

        scanCallback = null

        println(
            "BLE: Scanning stopped"
        )
    }

    fun isBluetoothAvailable():
            Boolean {

        return bluetoothAdapter
            ?.isEnabled == true
    }
}