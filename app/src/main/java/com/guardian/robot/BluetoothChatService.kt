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
        if (thread == null) {
            Log.e(TAG, "E023: Intento de write(Char) sin conexión activa")
            sendMessageToHandler("E023: Sin conexión BT")
            return
        }
        thread.write(cmd)
    }

    fun write(msg: String) {
        val thread: ConnectedThread?
        synchronized(this) {
            thread = connectedThread
        }
        if (thread == null) {
            Log.e(TAG, "E024: Intento de write(String) sin conexión activa")
            sendMessageToHandler("E024: Sin conexión BT")
            return
        }
        thread.write(msg)
    }

    fun connectionFailed() {
        Log.e(TAG, "E021: Conexión BT fallida con dispositivo")
        sendMessageToHandler("E021: Conexión fallida")
        state = STATE_NONE
    }

    fun connectionLost() {
        Log.e(TAG, "E025: Conexión BT perdida")
        sendMessageToHandler("E025: Conexión perdida")
        state = STATE_NONE
    }

    private fun sendMessageToHandler(text: String) {
        val msg = handler.obtainMessage(MESSAGE_TOAST)
        val bundle = android.os.Bundle()
        bundle.putString(TOAST, text)
        msg.data = bundle
        handler.sendMessage(msg)
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
            tryCreateSocket()
        }

        private fun tryCreateSocket() {
            try {
                socket = device.createInsecureRfcommSocketToServiceRecord(BT_UUID)
                Log.i(TAG, "Socket creado con createInsecureRfcommSocketToServiceRecord")
                return
            } catch (e: SecurityException) {
                Log.e(TAG, "E003: Permiso BT denegado al crear socket", e)
                sendMessageToHandler("E003: Permiso BT denegado")
                return
            } catch (e: IOException) {
                Log.w(TAG, "Insecure falló, probando método seguro", e)
            }
            try {
                socket = device.createRfcommSocketToServiceRecord(BT_UUID)
                Log.i(TAG, "Socket creado con createRfcommSocketToServiceRecord (seguro)")
            } catch (e: SecurityException) {
                Log.e(TAG, "E003: Permiso BT denegado al crear socket seguro", e)
                sendMessageToHandler("E003: Permiso BT denegado")
            } catch (e: IOException) {
                Log.w(TAG, "Seguro también falló, probando reflection", e)
                tryReflectionSocket()
            }
        }

        private fun tryReflectionSocket() {
            try {
                val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.java)
                socket = method.invoke(device, 1) as BluetoothSocket
                Log.w(TAG, "Reflection createInsecureRfcommSocket(1) exitoso")
            } catch (e: Exception) {
                Log.e(TAG, "E020: Todos los métodos de socket fallaron", e)
                sendMessageToHandler("E020: Socket no creado")
            }
        }

        override fun run() {
            name = "ConnectThread"

            adapter?.cancelDiscovery()

            if (socket == null) {
                Log.e(TAG, "E020: socket es null antes de connect(), abortando")
                connectionFailed()
                return
            }

            try {
                socket?.connect()
                Log.i(TAG, "ConnectThread: socket.connect() exitoso")
            } catch (e: IOException) {
                Log.e(TAG, "E021: connect() falló: ${e.message}", e)
                try {
                    socket?.close()
                } catch (e2: IOException) {
                    Log.e(TAG, "E022: Error cerrando socket tras fallo", e2)
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
                Log.e(TAG, "E027: Error cancelando ConnectThread", e)
            }
        }
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val inputStream: InputStream? = socket.inputStream
        private val outputStream: OutputStream? = socket.outputStream

        init {
            if (inputStream == null) {
                Log.e(TAG, "E026: InputStream es null al crear ConnectedThread")
            }
            if (outputStream == null) {
                Log.e(TAG, "E026: OutputStream es null al crear ConnectedThread")
            }
        }

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
                    Log.e(TAG, "E025: IOException en read(), conexión perdida", e)
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
                Log.e(TAG, "E023: Error escribiendo Char por BT", e)
                sendMessageToHandler("E023: Error envío BT")
            }
        }

        fun write(msg: String) {
            try {
                outputStream?.write(msg.toByteArray())
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "E024: Error escribiendo String por BT", e)
                sendMessageToHandler("E024: Error envío BT")
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "E026: Error cerrando socket ConnectedThread", e)
            }
        }
    }
}
