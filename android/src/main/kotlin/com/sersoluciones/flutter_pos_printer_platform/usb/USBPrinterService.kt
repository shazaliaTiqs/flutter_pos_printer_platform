package com.sersoluciones.flutter_pos_printer_platform.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.sersoluciones.flutter_pos_printer_platform.R
import java.nio.charset.Charset
import java.util.*

class USBPrinterService private constructor(private var mHandler: Handler?) {
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIndent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null
    var state: Int = STATE_USB_NONE

    fun setHandler(handler: Handler?) {
        mHandler = handler
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if ((ACTION_USB_PERMISSION == action)) {
                synchronized(this) {
                    val usbDevice: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(
                            LOG_TAG,
                            "Success get permission for device ${usbDevice?.deviceId}, vendor_id: ${usbDevice?.vendorId} product_id: ${usbDevice?.productId}"
                        )
                        mUsbDevice = usbDevice
                        state = STATE_USB_CONNECTED
                        mHandler?.obtainMessage(STATE_USB_CONNECTED)?.sendToTarget()
                    } else {
                        Toast.makeText(context, mContext?.getString(R.string.user_refuse_perm) + ": ${usbDevice!!.deviceName}", Toast.LENGTH_LONG).show()
                        state = STATE_USB_NONE
                        mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                    }
                }
            } else if ((UsbManager.ACTION_USB_DEVICE_DETACHED == action)) {

                if (mUsbDevice != null) {
                    Toast.makeText(context, mContext?.getString(R.string.device_off), Toast.LENGTH_LONG).show()
                    closeConnectionIfExists()
                    state = STATE_USB_NONE
                    mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                }

            } else if ((UsbManager.ACTION_USB_DEVICE_ATTACHED == action)) {
//                if (mUsbDevice != null) {
//                    Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG).show()
//                    closeConnectionIfExists()
//                }
            }
        }
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager
        mPermissionIndent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), 0)
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
        Log.v(LOG_TAG, "ESC/POS Printer initialized")
    }

    fun closeConnectionIfExists() {
        if (mUsbDeviceConnection != null) {
            mUsbDeviceConnection!!.releaseInterface(mUsbInterface)
            mUsbDeviceConnection!!.close()
            mUsbInterface = null
            mEndPoint = null
            mUsbDevice = null
            mUsbDeviceConnection = null
        }
    }

    val deviceList: List<UsbDevice>
        get() {
            if (mUSBManager == null) {
                Toast.makeText(mContext, mContext?.getString(R.string.not_usb_manager), Toast.LENGTH_LONG).show()
                return emptyList()
            }
            return ArrayList(mUSBManager!!.deviceList.values)
        }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
//        Log.v(LOG_TAG, " status usb ______ $state")
        if ((mUsbDevice == null) || (mUsbDevice!!.vendorId != vendorId) || (mUsbDevice!!.productId != productId)) {
            synchronized(printLock) {
                closeConnectionIfExists()
                val usbDevices: List<UsbDevice> = deviceList
                for (usbDevice: UsbDevice in usbDevices) {
                    if ((usbDevice.vendorId == vendorId) && (usbDevice.productId == productId)) {
                        Log.v(LOG_TAG, "Request for device: vendor_id: " + usbDevice.vendorId + ", product_id: " + usbDevice.productId)
                        closeConnectionIfExists()
                        mUSBManager!!.requestPermission(usbDevice, mPermissionIndent)
                        state = STATE_USB_CONNECTING
                        mHandler?.obtainMessage(STATE_USB_CONNECTING)?.sendToTarget()
                        return true
                    }
                }
                return false
            }
        } else {
            mHandler?.obtainMessage(state)?.sendToTarget()
        }

        return true
    }

    // REPLACE the whole openConnection() with this:
    private fun openConnection(): Boolean {
        if (mUsbDevice == null) { Log.e(LOG_TAG, "USB Device is not initialized"); return false }
        if (mUSBManager == null) { Log.e(LOG_TAG, "USB Manager is not initialized"); return false }

        // already good?
        if (mUsbDeviceConnection != null && mEndPoint != null) {
            Log.i(LOG_TAG, "USB Connection already connected (endpoint ok)")
            return true
        }

        val device = mUsbDevice!!
        val manager = mUSBManager!!

        val connection = manager.openDevice(device)
            ?: run { Log.e(LOG_TAG, "Failed to open USB Connection"); return false }

        // Scan ALL interfaces & endpoints; accept BULK OUT or INTERRUPT OUT
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                Log.d(
                    LOG_TAG,
                    "iface=$i class=${iface.interfaceClass} sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} " +
                            "ep=${ep.address} type=${ep.type} dir=${ep.direction} mps=${ep.maxPacketSize}"
                )

                val isOut = ep.direction == UsbConstants.USB_DIR_OUT
                val isWriteCapable =
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK ||
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_INT

                if (isOut && isWriteCapable) {
                    if (connection.claimInterface(iface, true)) {
                        mUsbInterface = iface
                        mEndPoint = ep
                        mUsbDeviceConnection = connection
                        Log.i(LOG_TAG, "Claimed iface=$i epOut=${ep.address} type=${ep.type} mps=${ep.maxPacketSize}")
                        return true
                    } else {
                        Log.e(LOG_TAG, "Failed to claim interface $i")
                    }
                }
            }
        }

        // nothing found → close & fail (don’t leave nulls and say 'true')
        connection.close()
        mUsbInterface = null
        mEndPoint = null
        mUsbDeviceConnection = null
        Log.e(LOG_TAG, "No BULK/INT OUT endpoint found on any interface")
        return false
    }


    // printText (guard & reuse openConnection)
    fun printText(text: String): Boolean {
        Log.v(LOG_TAG, "Printing text")
        val isConnected = openConnection()
        if (!isConnected || mUsbDeviceConnection == null || mEndPoint == null) {
            Log.v(LOG_TAG, "Failed to connect to device")
            return false
        }
        Thread {
            synchronized(printLock) {
                val bytes: ByteArray = text.toByteArray(Charset.forName("UTF-8"))
                val rc = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                Log.i(LOG_TAG, "Return code: $rc")
            }
        }.start()
        return true
    }

    // printRawData (guard & safe)
    fun printRawData(data: String): Boolean {
        Log.v(LOG_TAG, "Printing raw data")
        val isConnected = openConnection()
        if (!isConnected || mUsbDeviceConnection == null || mEndPoint == null) {
            Log.v(LOG_TAG, "Failed to connect to device")
            return false
        }
        Thread {
            synchronized(printLock) {
                val bytes: ByteArray = Base64.decode(data, Base64.DEFAULT)
                val rc = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                Log.i(LOG_TAG, "Return code: $rc")
            }
        }.start()
        return true
    }





    // REPLACE the whole printBytes() with this:
    fun printBytes(bytes: ArrayList<Int>): Boolean {
        Log.v(LOG_TAG, "Printing bytes")
        val ok = openConnection()
        if (!ok || mUsbDeviceConnection == null || mEndPoint == null) {
            Log.e(LOG_TAG, "No connection or endpoint to print (aborting)")
            return false
        }

        val ep = mEndPoint!!
        val conn = mUsbDeviceConnection!!

        // Guard MPS; use a sane minimum to avoid 0 (some INT endpoints report tiny MPS)
        val chunkSize = maxOf(16, ep.maxPacketSize)
        Log.v(LOG_TAG, "Packet Size: $chunkSize  (epType=${ep.type})")

        Thread {
            synchronized(printLock) {
                val out = ByteArray(bytes.size) { i -> bytes[i].toByte() }
                var offset = 0
                var rc = 0
                while (offset < out.size) {
                    val end = kotlin.math.min(offset + chunkSize, out.size)
                    val buf = java.util.Arrays.copyOfRange(out, offset, end)
                    // bulkTransfer works with INT endpoints as well; Android routes it appropriately
                    rc = conn.bulkTransfer(ep, buf, buf.size, 100_000)
                    offset = end
                }
                Log.i(LOG_TAG, "Return code: $rc")
            }
        }.start()
        return true
    }


    companion object {
        @SuppressLint("StaticFieldLeak")
        private var mInstance: USBPrinterService? = null
        private const val LOG_TAG = "ESC POS Printer"
        private const val ACTION_USB_PERMISSION = "com.flutter_pos_printer.USB_PERMISSION"

        // Constants that indicate the current connection state
        const val STATE_USB_NONE = 0 // we're doing nothing
        const val STATE_USB_CONNECTING = 2 // now initiating an outgoing connection
        const val STATE_USB_CONNECTED = 3 // now connected to a remote device

        private val printLock = Any()

        fun getInstance(handler: Handler): USBPrinterService {
            if (mInstance == null) {
                mInstance = USBPrinterService(handler)
            }
            return mInstance!!
        }
    }
}