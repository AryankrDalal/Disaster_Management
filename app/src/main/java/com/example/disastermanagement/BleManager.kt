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
        // Temporary company ID for our prototype
        private const val MANUFACTURER_ID = 0x1234
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager

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

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            )
            .setTxPowerLevel(
                AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
            )
            .setConnectable(false)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .addManufacturerData(
                MANUFACTURER_ID,
                data
            )
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(
            settings,
            advertiseData,
            advertiseCallback
        )
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {

        advertiser?.stopAdvertising(
            advertiseCallback
        )
    }

    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect: AdvertiseSettings?
            ) {
                println("BLE: Advertising started")
            }

            override fun onStartFailure(
                errorCode: Int
            ) {
                println(
                    "BLE: Advertising failed: $errorCode"
                )
            }
        }

    // --------------------------------------------------
    // SCANNING
    // --------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScanning(
        onPacketReceived: (SosPacket) -> Unit
    ) {

        val settings = ScanSettings.Builder()
            .setScanMode(
                ScanSettings.SCAN_MODE_LOW_LATENCY
            )
            .build()

        scanCallback = object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                val scanRecord =
                    result.scanRecord ?: return

                val manufacturerData =
                    scanRecord.manufacturerSpecificData

                for (i in 0 until manufacturerData.size()) {

                    val manufacturerId =
                        manufacturerData.keyAt(i)

                    if (manufacturerId != MANUFACTURER_ID) {
                        continue
                    }

                    val data =
                        manufacturerData.valueAt(i)

                    val packet =
                        SosPacket.fromBytes(data)

                    if (packet != null) {
                        onPacketReceived(packet)
                    }
                }
            }

            override fun onScanFailed(
                errorCode: Int
            ) {
                println(
                    "BLE: Scan failed: $errorCode"
                )
            }
        }

        scanner?.startScan(
            null,
            settings,
            scanCallback
        )

        println("BLE: Scanning started")
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {

        scanCallback?.let {
            scanner?.stopScan(it)
        }

        scanCallback = null

        println("BLE: Scanning stopped")
    }

    fun isBluetoothAvailable(): Boolean {

        return bluetoothAdapter?.isEnabled == true
    }
}