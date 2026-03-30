package com.darusc.mousedroid.networking

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.darusc.mousedroid.BatteryMonitor
import com.darusc.mousedroid.mkinput.InputEvent
import com.darusc.mousedroid.networking.Connection.Mode
import com.darusc.mousedroid.networking.bluetooth.BluetoothConnection
import com.darusc.mousedroid.networking.sockets.TCPConnection
import com.darusc.mousedroid.networking.sockets.UDPConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max

class ConnectionManager private constructor() : Connection.Listener, BatteryMonitor.Listener {

    private val TAG = "Mousedroid"

    companion object {
        @Volatile
        private var instance: ConnectionManager? = null

        fun getInstance(): ConnectionManager {
            synchronized(this) {
                return instance ?: ConnectionManager().also { instance = it }
            }
        }

        fun getInstance(connectionStateCallback: ConnectionStateCallback): ConnectionManager {
            synchronized(this) {
                if (instance == null) {
                    instance = ConnectionManager()
                }
                instance!!.setConnectionStateCallback(connectionStateCallback)
                return instance!!
            }
        }
    }

    private sealed class ConnectionInfo{
        data class WIFI(val address: String, val port: Int, val deviceDetails: String) : ConnectionInfo()
        data class BLUETOOTH(val macAddress: String) : ConnectionInfo()
        data class USB(val port: Int, val deviceDetails: String) : ConnectionInfo()
        object Disconnected : ConnectionInfo()
    }
    
    init {
        BatteryMonitor.getInstance().addListener(this)
    }

    private var connectionStateCallback: ConnectionStateCallback? = null

    private var tcpConn: TCPConnection? = null
    private var udpConn: UDPConnection? = null
    private var btConn: BluetoothConnection? = null

    private var connectionInfo: ConnectionInfo = ConnectionInfo.Disconnected

    /**
     * Active connection. UDP is prioritized over TCP
     */
    private val connection: Connection?
        get() = udpConn ?: tcpConn ?: btConn

    private var connected = false

    interface ConnectionStateCallback {
        fun onConnectionInitiated(mode: Mode) {}
        fun onConnectionSuccessful(connectionMode: Mode, hostName: String) {}
        fun onConnectionFailed(connectionMode: Mode) {}
        fun onDisconnected(connectionMode: Mode, hostName: String, error: Boolean) {}
    }

    private fun setConnectionStateCallback(connectionStateCallback: ConnectionStateCallback) {
        this.connectionStateCallback = connectionStateCallback
    }

    override fun onConnected(connectionMode: Mode, hostName: String) {
        connected = true
        connectionStateCallback?.onConnectionSuccessful(connectionMode, hostName)
    }

    override fun onConnectionFailed(connectionMode: Mode) {
        connectionStateCallback?.onConnectionFailed(connectionMode)
    }

    override fun onBytesReceived(buffer: ByteArray, bytes: Int) {}

    override fun onDisconnected(connectionMode: Mode, hostName: String, error: Boolean) {
        disconnect(keepInfo = true)
        connectionStateCallback?.onDisconnected(connectionMode, hostName, error)
    }

    override fun onBatteryPercentChanged(percentage: Int) {
        if (connected) {
            connection?.send(InputEvent.BatteryEvent(percentage))
        }
    }

    /**
     * Connect in WIFI mode.
     * Tcp socket is used only for connections, commands are sent using Udp
     */
    fun connectWIFI(ipAddress: String, port: Int, deviceDetails: String) {
        connectionInfo = ConnectionInfo.WIFI(ipAddress, port, deviceDetails)
        connectionStateCallback?.onConnectionInitiated(Mode.WIFI)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tcpConn = TCPConnection(ipAddress, port, false, this@ConnectionManager)
                udpConn = UDPConnection(ipAddress, port)
                tcpConn!!.sendBytes(deviceDetails.toByteArray())
                onConnected(Mode.WIFI, ipAddress)
            } catch (e: Connection.ConnectionFailedException) {
                Log.e(TAG, "Connection manager: ${e.message}")
                connectionStateCallback?.onConnectionFailed(Mode.WIFI)
            }
        }
    }

    /**
     * Connect in USB mode (through adb)
     * Tcp socket is used both for connection and sending commands (adb doesn't allow Udp port forwarding)
     */
    fun connectUSB(port: Int, deviceDetails: String) {
        connectionInfo = ConnectionInfo.USB(port, deviceDetails)
        connectionStateCallback?.onConnectionInitiated(Mode.USB)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tcpConn = TCPConnection("127.0.0.1", port, true, this@ConnectionManager)
                tcpConn!!.sendBytes(deviceDetails.toByteArray())
                onConnected(Mode.USB, "127.0.0.1")
            } catch (e: Connection.ConnectionFailedException) {
                Log.e(TAG, "Connection manager: ${e.message}")
                connectionStateCallback?.onConnectionFailed(Mode.USB)
            }
        }
    }

    /**
     * Register the bluetooth HID profile
     */
    fun registerBluetoothHID(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            btConn = BluetoothConnection(context, this@ConnectionManager)
        }
    }

    /**
     * Connect in bluetooth mode
     */
    fun connectBluetooth(macAddress: String) {
        connectionInfo = ConnectionInfo.BLUETOOTH(macAddress)
        connectionStateCallback?.onConnectionInitiated(Mode.BLUETOOTH)
        btConn?.connect(macAddress)
    }

    /**
     * Close active connection
     */
    fun disconnect(keepInfo: Boolean = false) {
        if (!keepInfo) {
            connectionInfo = ConnectionInfo.Disconnected
        }
        CoroutineScope(Dispatchers.IO).launch {
            connected = false
            udpConn?.close()
            tcpConn?.close()
            btConn?.close()

            udpConn = null
            tcpConn = null
            btConn = null
        }
    }

    fun resume() {
        when (val info = connectionInfo) {
            is ConnectionInfo.WIFI -> connectWIFI(info.address, info.port, info.deviceDetails)
            is ConnectionInfo.BLUETOOTH -> connectBluetooth(info.macAddress)
            is ConnectionInfo.USB -> connectUSB(info.port, info.deviceDetails)
            ConnectionInfo.Disconnected -> Log.d(TAG, "Tried to resume connection with no active connection")
        }
    }

    fun pause() {
        disconnect(keepInfo = true)
        Log.d(TAG, "Disconnected, stored connection info")
    }

    fun send(event: InputEvent, withCoroutine: Boolean = true) {
        if (connection == null) {
            return;
        }

        when (withCoroutine) {
            false -> connection?.send(event)
            true -> CoroutineScope(Dispatchers.IO).launch { connection?.send(event) }
        }
    }
}