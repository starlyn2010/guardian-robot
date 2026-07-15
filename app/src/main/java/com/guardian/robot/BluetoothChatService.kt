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
        const val MESSAGE_RETRY = 6

        const val DEVICE_NAME = "device_name"
        const val TOAST = "toast"

        private const val CONNECT_TIMEOUT_MS = 15000L
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 2000L
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var state = STATE_NONE
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private var retryCount = 0
    private var connectingDevice: BluetoothDevice? = null

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
        retryCount = 0
        connectingDevice = device
        Log.i(TAG, "E032: Iniciando conexion a ${device.name} addr=${device.address} bond=${device.bondState}")
        doConnect()
    }

    private fun doConnect() {
        cancelConnectThread()
        cancelConnectedThread()
        adapter?.cancelDiscovery()
        connectThread = ConnectThread(connectingDevice!!)
        connectThread?.start()
        state = STATE_CONNECTING
    }

    private fun retryConnect() {
        retryCount++
        if (retryCount <= MAX_RETRIES) {
            Log.w(TAG, "E032: Reintento $retryCount/$MAX_RETRIES en ${RETRY_DELAY_MS}ms")
            val msg = handler.obtainMessage(MESSAGE_RETRY)
            val bundle = android.os.Bundle()
            bundle.putString(TOAST, "Reintentando ($retryCount/$MAX_RETRIES)")
            msg.data = bundle
            handler.sendMessage(msg)
            handler.postDelayed({ doConnect() }, RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "E031: Todos los ${MAX_RETRIES+1} intentos fallaron")
            sendMessageToHandler("E031: No conectó tras ${MAX_RETRIES+1} intentos")
            this@BluetoothChatService.state = STATE_NONE
        }
    }

    fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        cancelConnectThread()
        cancelConnectedThread()
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()

        if (connectedThread?.outputStream == null) {
            Log.e(TAG, "E033: outputStream es null tras conexion, reintentando")
            socket.close()
            retryConnect()
            return
        }

        val msg = handler.obtainMessage(MESSAGE_DEVICE_NAME)
        val bundle = android.os.Bundle()
        bundle.putString(DEVICE_NAME, device.name)
        msg.data = bundle
        handler.sendMessage(msg)

        state = STATE_CONNECTED
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
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

    fun connectionLost() {
        Log.e(TAG, "E025: Conexión BT perdida con ${connectingDevice?.name}")
        sendMessageToHandler("E025: Conexión perdida")
        this@BluetoothChatService.state = STATE_NONE
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
        private var socketMethod = ""

        init {
            tryCreateSocket()
        }

        private fun tryCreateSocket() {
            try {
                socket = device.createRfcommSocketToServiceRecord(BT_UUID)
                socketMethod = "createRfcommSocketToServiceRecord"
                Log.i(TAG, "E032: Socket creado via $socketMethod")
                return
            } catch (e: SecurityException) {
                Log.e(TAG, "E003: Permiso denegado al crear socket", e)
                sendMessageToHandler("E003: Permiso BT denegado")
                return
            } catch (e: IOException) {
                Log.w(TAG, "E032: $socketMethod falló: ${e.message}", e)
            }
            try {
                val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.java)
                socket = method.invoke(device, 1) as BluetoothSocket
                socketMethod = "reflection createInsecureRfcommSocket(1)"
                Log.w(TAG, "E032: Socket creado via $socketMethod")
            } catch (e: Exception) {
                Log.e(TAG, "E020: Todos los métodos de socket fallaron", e)
                sendMessageToHandler("E020: Socket no creado")
            }
        }

        override fun run() {
            name = "ConnectThread"

            if (socket == null) {
                Log.e(TAG, "E020: socket es null, abortando intento")
                this@BluetoothChatService.retryConnect()
                return
            }

            val startMs = System.currentTimeMillis()
            Log.i(TAG, "E032: Iniciando connect() via $socketMethod")

            val timeoutThread = Thread {
                try {
                    Thread.sleep(CONNECT_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                synchronized(this@ConnectThread) {
                    if (this@BluetoothChatService.getState() == STATE_CONNECTING) {
                        Log.e(TAG, "E030: Timeout de ${CONNECT_TIMEOUT_MS}ms alcanzado")
                        try {
                            socket?.close()
                        } catch (_: IOException) {}
                    }
                }
            }
            timeoutThread.start()

            try {
                socket?.connect()
                timeoutThread.interrupt()
                Log.i(TAG, "E032: connect() exitoso tras ${System.currentTimeMillis() - startMs}ms")
            } catch (e: IOException) {
                timeoutThread.interrupt()
                val elapsed = System.currentTimeMillis() - startMs
                Log.e(TAG, "E032: connect() falló a los ${elapsed}ms: ${e.message}", e)
                if (elapsed >= CONNECT_TIMEOUT_MS) {
                    this@BluetoothChatService.state = STATE_NONE
                    sendMessageToHandler("E030: Tiempo agotado (${CONNECT_TIMEOUT_MS / 1000}s)")
                }
                try {
                    socket?.close()
                } catch (e2: IOException) {
                    Log.e(TAG, "E022: Error cerrando socket", e2)
                }
                this@BluetoothChatService.retryConnect()
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
        val outputStream: OutputStream? = socket.outputStream

        init {
            Log.i(TAG, "E033: ConnectedThread creado inputStream=${inputStream != null} outputStream=${outputStream != null}")
        }

        override fun run() {
            name = "ConnectedThread"

            val buffer = ByteArray(1024)

            while (true) {
                try {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes == -1) {
                        Log.w(TAG, "E025: read() retornó -1, conexión perdida")
                        this@BluetoothChatService.connectionLost()
                        break
                    }
                    handler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget()
                } catch (e: IOException) {
                    Log.e(TAG, "E025: IOException en read(): ${e.message}", e)
                    this@BluetoothChatService.connectionLost()
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
            }
        }

        fun write(msg: String) {
            try {
                outputStream?.write(msg.toByteArray())
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "E024: Error escribiendo String por BT", e)
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
