package com.guardian.robot

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Message
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothChatService(private val handler: Handler) {

    companion object {
        private const val TAG = "BluetoothChatService"
        val BT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val STATE_NONE = 0
        const val STATE_LISTEN = 1
        const val STATE_CONNECTING = 2
        const val STATE_CONNECTED = 3

        const val MESSAGE_STATE_CHANGE = 1
        const val MESSAGE_READ = 2
        const val MESSAGE_WRITE = 3
        const val MESSAGE_DEVICE_NAME = 4
        const val MESSAGE_TOAST = 5

        const val DEVICE_NAME = "device_name"
        const val TOAST = "toast"
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var state = STATE_NONE
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    init {
        state = STATE_NONE
    }

    fun getState(): Int = state

    fun start() {
        cancelConnectThread()
        cancelConnectedThread()
        state = STATE_LISTEN
    }

    fun connect(device: BluetoothDevice) {
        cancelConnectThread()
        cancelConnectedThread()
        connectThread = ConnectThread(device)
        connectThread?.start()
        state = STATE_CONNECTING
    }

    fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        cancelConnectThread()
        cancelConnectedThread()
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()

        val msg = handler.obtainMessage(MESSAGE_DEVICE_NAME)
        val bundle = android.os.Bundle()
        bundle.putString(DEVICE_NAME, device.name)
        msg.data = bundle
        handler.sendMessage(msg)

        state = STATE_CONNECTED
    }

    fun stop() {
        cancelConnectThread()
        cancelConnectedThread()
        state = STATE_NONE
    }

    fun write(cmd: Char) {
        val thread: ConnectedThread?
        synchronized(this) {
            thread = connectedThread
        }
        thread?.write(cmd)
    }

    fun connectionFailed() {
        val msg = handler.obtainMessage(MESSAGE_TOAST)
        val bundle = android.os.Bundle()
        bundle.putString(TOAST, "Unable to connect device")
        msg.data = bundle
        handler.sendMessage(msg)

        state = STATE_NONE
    }

    fun connectionLost() {
        val msg = handler.obtainMessage(MESSAGE_TOAST)
        val bundle = android.os.Bundle()
        bundle.putString(TOAST, "Device connection was lost")
        msg.data = bundle
        handler.sendMessage(msg)

        state = STATE_NONE
    }

    private fun cancelConnectThread() {
        connectThread?.cancel()
        connectThread = null
    }

    private fun cancelConnectedThread() {
        connectedThread?.cancel()
        connectedThread = null
    }

    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private var socket: BluetoothSocket? = null

        init {
            try {
                socket = device.createRfcommSocketToServiceRecord(BT_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "create() failed", e)
            }
        }

        override fun run() {
            name = "ConnectThread"

            adapter?.cancelDiscovery()

            try {
                socket?.connect()
            } catch (e: IOException) {
                Log.e(TAG, "connect() failed", e)
                try {
                    socket?.close()
                } catch (e2: IOException) {
                    Log.e(TAG, "close() of connect socket failed", e2)
                }
                connectionFailed()
                return
            }

            synchronized(this@BluetoothChatService) {
                connectThread = null
            }

            connected(socket!!, device)
        }

        fun cancel() {
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "close() of connect socket failed", e)
            }
        }
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val inputStream: InputStream? = socket.inputStream
        private val outputStream: OutputStream? = socket.outputStream

        override fun run() {
            name = "ConnectedThread"

            val buffer = ByteArray(1024)

            while (true) {
                try {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes == -1) {
                        connectionLost()
                        break
                    }
                    handler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget()
                } catch (e: IOException) {
                    connectionLost()
                    break
                }
            }
        }

        fun write(cmd: Char) {
            try {
                outputStream?.write(cmd.code)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Exception during write", e)
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "close() of connected socket failed", e)
            }
        }
    }
}
