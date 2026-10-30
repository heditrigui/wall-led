package com.wallled.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
 * BLE links to LEDDMX controllers (service ffe0, characteristic ffe1), one per strip, shared by
 * the page and [ScheduleService]. All state lives on the main thread.
 *
 * The page names its links with keys ("p1", "p2", ...) and gets status back as
 * `__ble.onStatus(key, state, detail)` with state "connected", "failed" or "disconnected".
 * The schedule service opens its own links, keyed "bg:<address>", which report nothing.
 */
@SuppressLint("MissingPermission") // checked in connect()
object LedBle {
    private lateinit var ctx: Context
    private var adapter: BluetoothAdapter? = null
    private val main = Handler(Looper.getMainLooper())
    private val links = LinkedHashMap<String, Link>()
    private var scanningFor: Link? = null

    /** Delivers status scripts to the page while it is shown. */
    var js: ((String) -> Unit)? = null

    /** Addresses of the strips whose link is ready, readable from any thread. */
    @Volatile var readyAddresses: Set<String> = emptySet()
        private set

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    fun hasPermissions() = PERMISSIONS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Strips connected at least once, so schedules can reach them while the app is closed. */
    fun knownAddresses(): Set<String> = prefs().getStringSet(KNOWN, emptySet())!!

    private class Link(val key: String) {
        var address: String? = null
        var gatt: BluetoothGatt? = null
        var ch: BluetoothGattCharacteristic? = null
        // Android allows one GATT operation at a time per link, so writes wait here.
        val queue = ArrayDeque<ByteArray>()
        var writing = false
        val timeout = Runnable { fail(this, "Strip not found. Is it powered, and the phone app closed?") }
    }

    /** Connects the page's link [key]: to the strip it had before, or else to the next free strip found. */
    fun connect(key: String) {
        main.post {
            val a = adapter
            when {
                a == null || !a.isEnabled -> status(key, "failed", "Turn on Bluetooth")
                !hasPermissions() -> status(key, "failed", "Allow the Nearby devices permission")
                scanningFor != null && scanningFor?.key != key -> status(key, "failed", "Still looking for the previous strip")
                else -> {
                    val link = links.getOrPut(key) { Link(key) }
                    closeGatt(link)
                    main.removeCallbacks(link.timeout)
                    main.postDelayed(link.timeout, 12_000)
                    val addr = link.address
                    if (addr != null) connectTo(link, a.getRemoteDevice(addr)) else startScan(link)
                }
            }
        }
    }

    /** Opens background links to [addresses] that have no link yet. */
    fun ensure(addresses: Collection<String>) {
        main.post {
            val a = adapter ?: return@post
            if (!a.isEnabled || !hasPermissions()) return@post
            for (addr in addresses) {
                if (links.values.any { it.address == addr && it.gatt != null }) continue
                val link = links.getOrPut("bg:$addr") { Link("bg:$addr") }
                link.address = addr
                main.removeCallbacks(link.timeout)
                main.postDelayed(link.timeout, 15_000)
                connectTo(link, a.getRemoteDevice(addr))
            }
        }
    }

    /** Closes the background links, leaving the page's links alone. */
    fun closeBackground() {
        main.post {
            links.values.filter { it.key.startsWith("bg:") }.forEach { main.removeCallbacks(it.timeout); closeGatt(it) }
            links.keys.removeAll { it.startsWith("bg:") }
            updateReady()
        }
    }

    fun disconnect(key: String) {
        main.post {
            val link = links[key] ?: return@post status(key, "disconnected")
            main.removeCallbacks(link.timeout)
            if (scanningFor == link) stopScan()
            val g = link.gatt
            if (g != null && link.ch != null) g.disconnect() // the callback reports "disconnected"
            else { closeGatt(link); status(key, "disconnected") }
        }
    }

    /** Disconnects every link the page opened (the app is closing). */
    fun disconnectPage() {
        main.post { links.keys.filter { !it.startsWith("bg:") }.forEach { disconnect(it) } }
    }

    fun write(key: String, hex: String) {
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        main.post { links[key]?.let { enqueue(it, bytes) } }
    }

    /** Sends [bytes] to every ready strip, whoever opened its link. */
    fun writeAll(bytes: ByteArray) {
        main.post {
            val seen = HashSet<String>()
            for (link in links.values) {
                val addr = link.address ?: continue
                if (link.ch != null && seen.add(addr)) enqueue(link, bytes)
            }
        }
    }

    private fun enqueue(link: Link, bytes: ByteArray) {
        if (link.queue.size >= 8) link.queue.removeFirst()
        link.queue.addLast(bytes)
        flush(link)
    }

    private fun flush(link: Link) {
        val g = link.gatt ?: return
        val c = link.ch ?: return
        if (link.writing) return
        val data = link.queue.removeFirstOrNull() ?: return
        link.writing = true
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
            link.writing = false
            link.queue.addFirst(data)
            main.postDelayed({ flush(link) }, 15)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith("LEDDMX")) return
            main.post {
                val link = scanningFor ?: return@post
                // Skip strips another link already holds.
                if (links.values.any { it.address == result.device.address && it.gatt != null }) return@post
                stopScan()
                connectTo(link, result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { scanningFor?.let { fail(it, "Bluetooth scan failed ($errorCode)") } }
        }
    }

    private fun startScan(link: Link) {
        scanningFor = link
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter?.bluetoothLeScanner?.startScan(null, settings, scanCallback) ?: fail(link, "Bluetooth is not ready")
    }

    private fun stopScan() {
        if (scanningFor == null) return
        scanningFor = null
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun connectTo(link: Link, device: BluetoothDevice) {
        link.address = device.address
        link.gatt = device.connectGatt(ctx, false, GattCallback(link), BluetoothDevice.TRANSPORT_LE)
    }

    private class GattCallback(private val link: Link) : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != link.gatt) { g.close(); return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val wasReady = link.ch != null
                    closeGatt(link)
                    if (wasReady) status(link.key, "disconnected") else fail(link, "Could not connect (error $status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != link.gatt) return@post
                val c = g.getService(SERVICE)?.getCharacteristic(CHAR)
                if (c == null) { fail(link, "This doesn't look like an LEDDMX strip"); return@post }
                link.ch = c
                main.removeCallbacks(link.timeout)
                remember(g.device.address)
                updateReady()
                status(link.key, "connected", g.device.name ?: "LEDDMX")
                flush(link)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { link.writing = false; flush(link) }
        }
    }

    private fun fail(link: Link, msg: String) {
        main.removeCallbacks(link.timeout)
        if (scanningFor == link) stopScan()
        closeGatt(link)
        status(link.key, "failed", msg)
    }

    private fun closeGatt(link: Link) {
        link.gatt?.close()
        link.gatt = null
        link.ch = null
        link.writing = false
        link.queue.clear()
        updateReady()
    }

    private fun updateReady() {
        readyAddresses = links.values.filter { it.ch != null }.mapNotNull { it.address }.toSet()
    }

    private fun remember(address: String) {
        val known = knownAddresses()
        if (address !in known) prefs().edit().putStringSet(KNOWN, known + address).apply()
    }

    private fun prefs() = ctx.getSharedPreferences("strips", Context.MODE_PRIVATE)

    private fun status(key: String, state: String, detail: String = "") {
        if (key.startsWith("bg:")) return
        js?.invoke("window.__ble && __ble.onStatus(${JSONObject.quote(key)}, ${JSONObject.quote(state)}, ${JSONObject.quote(detail)})")
    }

    val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    private const val KNOWN = "known"
    private val SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val CHAR: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
}
