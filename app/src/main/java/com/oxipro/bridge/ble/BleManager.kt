package com.oxipro.bridge.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Scans for, connects to, and reads readings from the OxiPro BP2, which uses
 * a proprietary GATT profile (NOT the standard 0x1810 Blood Pressure
 * Service). UUIDs and framing below were reverse-engineered from an HCI
 * snoop capture of the official MedM app — see BloodPressureParser for
 * protocol details and known uncertainties.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        // Vendor-specific service — NOT a Bluetooth SIG standard profile.
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

        // Device -> phone: streams live pressure + final results.
        val CHAR_NOTIFY: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

        // Phone -> device: handshake / control commands.
        val CHAR_WRITE: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * The one handshake command observed as byte-for-byte IDENTICAL across
         * every capture session (unlike the time-sync command, whose trailer
         * changes with the date and whose checksum algorithm we don't know).
         * Sending this after subscribing to notifications appears to be what
         * tells the device the phone is "ready" — without it, no captured
         * session ever produced data.
         */
        private val HANDSHAKE_HELLO = byteArrayOf(
            0xBE.toByte(), 0xB0.toByte(), 0x01, 0xB2.toByte(), 0x72
        )
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val _packets = MutableSharedFlow<BloodPressureParser.ParsedPacket>(extraBufferCapacity = 16)
    val packets: SharedFlow<BloodPressureParser.ParsedPacket> = _packets

    private val _connectionState = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val connectionState: SharedFlow<String> = _connectionState

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var scanResultHandled = false

    fun startScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _connectionState.tryEmit("Bluetooth adapter unavailable")
            return
        }
        scanResultHandled = false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // BLE devices re-advertise every ~100ms-1s while scanning, and
                // stopScan() is asynchronous — several more results can land
                // before it actually takes effect. Without this guard, each
                // one would call connect() again, stacking up overlapping
                // connectGatt() calls to the same device and causing repeated
                // connect/disconnect churn until the stack sorts it out.
                if (scanResultHandled) return
                val name = result.device.name ?: return
                if (name.contains("BP", ignoreCase = true) || name.contains("OxiPro", ignoreCase = true)) {
                    scanResultHandled = true
                    stopScan()
                    onDeviceFound(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _connectionState.tryEmit("Scan failed: $errorCode")
            }
        }
        scanner.startScan(callback)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(object : ScanCallback() {})
    }

    fun connect(device: BluetoothDevice) {
        // Defensive: if a previous connection attempt is still hanging around
        // (shouldn't happen now that startScan only fires once, but cheap to
        // guard against), close it before starting a fresh one.
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        _isConnected.value = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.tryEmit("Connected")
                    _isConnected.value = true
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.tryEmit("Disconnected")
                    _isConnected.value = false
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                _connectionState.tryEmit("Service 0xffe0 not found on this device")
                return
            }

            val notifyChar = service.getCharacteristic(CHAR_NOTIFY)
            writeCharacteristic = service.getCharacteristic(CHAR_WRITE)

            if (notifyChar == null || writeCharacteristic == null) {
                _connectionState.tryEmit("Expected characteristics (0xffe1/0xffe2) not found")
                return
            }

            g.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } else {
                _connectionState.tryEmit("No CCCD on notify characteristic — can't subscribe")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD) {
                _connectionState.tryEmit("Subscribed. Sending handshake...")
                sendHandshake(g)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.tryEmit("Handshake sent — waiting for a reading (press the button on the device)")
            } else {
                _connectionState.tryEmit("Handshake write failed (status $status)")
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != CHAR_NOTIFY) return
            val bytes = characteristic.value ?: return
            val parsed = BloodPressureParser.parse(bytes)
            if (parsed is BloodPressureParser.ParsedPacket.LivePressure) {
                _connectionState.tryEmit("Measuring... ${parsed.cuffPressureMmHg} mmHg")
            }
            _packets.tryEmit(parsed)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendHandshake(g: BluetoothGatt) {
        val char = writeCharacteristic ?: return
        char.value = HANDSHAKE_HELLO
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        g.writeCharacteristic(char)
    }
}
