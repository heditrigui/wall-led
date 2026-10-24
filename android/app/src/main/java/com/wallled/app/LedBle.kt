package com.wallled.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID

/**
 * Minimal BLE link to an LEDDMX controller (service ffe0, characteristic ffe1).
 * All state lives on the main thread. Status goes back to the page as
 * `__ble.onStatus(state, detail)` with state "connected", "failed" or "disconnected".
 */
@SuppressLint("MissingPermission") // checked in connect()
class LedBle(private val ctx: Context, private val js: (String) -> Unit) {
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var ch: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var lastAddress: String? = null

    // Android allows one GATT operation at a time, so writes wait here for the previous one.
    private val queue = ArrayDeque<ByteArray>()
    private var writing = false

    private val timeout = Runnable { fail("Strip not found. Is it powered, and the phone app closed?") }

    fun hasPermissions() = PERMISSIONS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun connect() {
        main.post {
            when {
                adapter == null || !adapter.isEnabled -> status("failed", "Turn on Bluetooth")
                !hasPermissions() -> status("failed", "Allow the Nearby devices permission")
                else -> {
                    closeGatt()
                    main.removeCallbacks(timeout)
                    main.postDelayed(timeout, 12_000)
                    val addr = lastAddress
                    if (addr != null) connectTo(adapter.getRemoteDevice(addr)) else startScan()
                }
            }
        }
    }

    fun disconnect() {
        main.post {
            main.removeCallbacks(timeout)
            stopScan()
            val g = gatt
            if (g != null && ch != null) g.disconnect() // the callback reports "disconnected"
            else { closeGatt(); status("disconnected") }
        }
    }

    fun write(hex: String) {
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        main.post {
            if (queue.size >= 8) queue.removeFirst()
            queue.addLast(bytes)
            flush()
        }
    }

    private fun flush() {
        val g = gatt ?: return
        val c = ch ?: return
        if (writing) return
        val data = queue.removeFirstOrNull() ?: return
        writing = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            c.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!ok) {
            // Stack busy: put it back and try again shortly.
            writing = false
            queue.addFirst(data)
            main.postDelayed({ flush() }, 15)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith("LEDDMX")) return
            main.post {
                if (!scanning) return@post
                stopScan()
                connectTo(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { fail("Bluetooth scan failed ($errorCode)") }
        }
    }

    private fun startScan() {
        scanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback) ?: fail("Bluetooth is not ready")
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun connectTo(device: BluetoothDevice) {
        gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) { g.close(); return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val wasReady = ch != null
                    closeGatt()
                    if (wasReady) status("disconnected") else fail("Could not connect (error $status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                val c = g.getService(SERVICE)?.getCharacteristic(CHAR)
                if (c == null) { fail("This doesn't look like an LEDDMX strip"); return@post }
                ch = c
                lastAddress = g.device.address
                main.removeCallbacks(timeout)
                status("connected", g.device.name ?: "LEDDMX")
                flush()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { writing = false; flush() }
        }
    }

    private fun fail(msg: String) {
        main.removeCallbacks(timeout)
        stopScan()
        closeGatt()
        status("failed", msg)
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        ch = null
        writing = false
        queue.clear()
    }

    private fun status(state: String, detail: String = "") =
        js("window.__ble && __ble.onStatus(${JSONObject.quote(state)}, ${JSONObject.quote(detail)})")

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        private val SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val CHAR: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }
}
