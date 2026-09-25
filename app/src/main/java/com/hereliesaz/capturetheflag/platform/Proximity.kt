package com.hereliesaz.capturetheflag.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.hereliesaz.capturetheflag.model.BleSighting
import com.hereliesaz.capturetheflag.rules.GameRules
import java.util.UUID

/**
 * BLE proof-of-proximity. Each phone advertises a short-lived, server-issued token as service
 * data and scans for everyone else's. Tokens are meaningless without the server's registry,
 * so eavesdroppers learn nothing about who is nearby.
 */
@SuppressLint("MissingPermission") // Callers gate on runtime permissions.
class Proximity(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val sightings = ArrayDeque<BleSighting>()
    private var advertising: AdvertiseCallback? = null
    private var scanning: ScanCallback? = null

    fun start(tokenHex: String) {
        val a = adapter?.takeIf { it.isEnabled } ?: return
        stopAdvertising()
        val cb = object : AdvertiseCallback() {}
        a.bluetoothLeAdvertiser?.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build(),
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceData(SERVICE, tokenHex.hexToBytes())
                .build(),
            cb,
        )
        advertising = cb
        if (scanning == null) startScan()
    }

    private fun startScan() {
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(SERVICE) ?: return
                record(BleSighting(data.toHex(), System.currentTimeMillis(), result.rssi))
            }
        }
        adapter?.bluetoothLeScanner?.startScan(
            listOf(ScanFilter.Builder().setServiceData(SERVICE, byteArrayOf(), byteArrayOf()).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        scanning = cb
    }

    @Synchronized
    private fun record(s: BleSighting) {
        sightings.addLast(s)
        val cutoff = s.at - 5 * GameRules.BLE_WINDOW
        while (sightings.isNotEmpty() && sightings.first().at < cutoff) sightings.removeFirst()
    }

    /** Sightings within [GameRules.BLE_WINDOW] of [at]. */
    @Synchronized
    fun around(at: Long): List<BleSighting> = sightings.filter { kotlin.math.abs(it.at - at) <= GameRules.BLE_WINDOW }

    fun stop() {
        stopAdvertising()
        scanning?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanning = null
    }

    private fun stopAdvertising() {
        advertising?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        advertising = null
    }

    private companion object {
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("7c7f0000-c7f0-4e11-a5e5-0000c7f1a900"))
        fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
