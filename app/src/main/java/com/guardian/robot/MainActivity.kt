package com.guardian.robot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guardian.robot.BluetoothChatService.Companion.DEVICE_NAME
import com.guardian.robot.BluetoothChatService.Companion.MESSAGE_DEVICE_NAME
import com.guardian.robot.BluetoothChatService.Companion.MESSAGE_STATE_CHANGE
import com.guardian.robot.BluetoothChatService.Companion.MESSAGE_TOAST
import com.guardian.robot.BluetoothChatService.Companion.STATE_CONNECTED
import com.guardian.robot.BluetoothChatService.Companion.STATE_CONNECTING
import com.guardian.robot.BluetoothChatService.Companion.STATE_NONE
import com.guardian.robot.databinding.ActivityMainBinding
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.support.image.TensorImage
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var detector: ObjectDetector? = null
    private var btService: BluetoothChatService? = null
    private var tts: TextToSpeech? = null
    private var btDeviceName: String? = null

    private var isProcessing = false
    private var cameraActive = false
    private var lastSent = 3
    private var lastZone = DetectionOverlayView.ZONE_GREEN

    private val intrusionLog = mutableListOf<IntrusionEvent>()
    private lateinit var intrusionAdapter: IntrusionAdapter

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0

    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var discoveryReceiver: BroadcastReceiver? = null
    private var devicePickerDialog: AlertDialog? = null
    private var pendingDeviceList = mutableListOf<String>()
    private var pendingDeviceAddr = mutableListOf<String>()
    private var isScanning = false

    companion object {
        private val INTRUDER_CLASSES = setOf(1, 16, 17, 18, 19, 20, 21, 22, 23, 24)
        private const val CONF_THRESHOLD = 0.6f
        private const val YELLOW_RATIO = 0.05f
        private const val RED_RATIO = 0.18f
        private const val FRAME_W = 640
        private const val FRAME_H = 480
        private const val FRAME_AREA = FRAME_W * FRAME_H
        private const val TAG = "Guardian"
        private const val PERM_REQ_CODE = 100
        private const val MAX_LOG_SIZE = 50
    }

    private val btHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_STATE_CHANGE -> {
                    when (msg.arg1) {
                        STATE_CONNECTED -> {
                            updateStatus("BT conectado a $btDeviceName. Iniciando cámara...")
                            updateBtIndicator(true, btDeviceName)
                            startCamera()
                        }
                        STATE_CONNECTING -> updateStatus("Conectando Bluetooth...")
                        STATE_NONE -> {
                            updateBtIndicator(false, null)
                            val toast = msg.data.getString(BluetoothChatService.Companion.TOAST)
                            if (toast != null) updateStatus(toast)
                        }
                    }
                }
                MESSAGE_DEVICE_NAME -> {
                    btDeviceName = msg.data.getString(DEVICE_NAME)
                }
                MESSAGE_TOAST -> {
                    updateStatus(msg.data.getString(BluetoothChatService.Companion.TOAST) ?: "Error BT")
                    updateBtIndicator(false, null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupIntrusionLog()
        binding.btnStart.setOnClickListener { onStartClick() }
        binding.btnStop.setOnClickListener { onStopClick() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }

        binding.progressModel.visibility = View.VISIBLE
        binding.tvModelStatus.text = "Cargando"
        inferenceExecutor.execute { loadModel() }
    }

    private fun setupIntrusionLog() {
        intrusionAdapter = IntrusionAdapter(intrusionLog)
        binding.rvIntrusionLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = intrusionAdapter
        }
    }

    private fun loadModel() {
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setScoreThreshold(CONF_THRESHOLD)
                .setMaxResults(5)
                .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
                .build()
            detector = ObjectDetector.createFromFileAndOptions(
                this, "efficientdet_lite0.tflite", options
            )
            mainHandler.post {
                binding.progressModel.visibility = View.GONE
                binding.tvModelStatus.text = "Listo"
                binding.tvModelStatus.setTextColor(ContextCompat.getColor(this, R.color.zone_green))
                updateStatus("Modelo cargado. Presiona INICIAR.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando modelo", e)
            mainHandler.post {
                binding.progressModel.visibility = View.GONE
                binding.tvModelStatus.text = "Error"
                binding.tvModelStatus.setTextColor(ContextCompat.getColor(this, R.color.zone_red))
                updateStatus("Error modelo: ${e.message}")
            }
        }
    }

    private fun onStartClick() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            connectBluetooth()
        } else {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQ_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, grants)
        if (requestCode == PERM_REQ_CODE && grants.all { it == PackageManager.PERMISSION_GRANTED }) {
            connectBluetooth()
        } else {
            updateStatus("Permisos denegados")
        }
    }

    private fun connectBluetooth() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                updateStatus("Activa Bluetooth en Ajustes")
                mainHandler.post { updateBtIndicator(false, null) }
                return
            }
            showDevicePicker()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permiso BT", e)
            updateStatus("Permiso BT denegado")
            mainHandler.post { updateBtIndicator(false, null) }
        }
    }

    private fun showDevicePicker() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        pendingDeviceList.clear()
        pendingDeviceAddr.clear()

        for (d in adapter.bondedDevices) {
            pendingDeviceList.add("${d.name}\n${d.address} (emparejado)")
            pendingDeviceAddr.add(d.address)
        }
        pendingDeviceList.add("--- ESCANEAR ---")
        pendingDeviceAddr.add("__SCAN__")

        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, pendingDeviceList)
        devicePickerDialog = AlertDialog.Builder(this)
            .setTitle("Seleccionar dispositivo")
            .setAdapter(listAdapter) { _, which ->
                val addr = pendingDeviceAddr[which]
                if (addr == "__SCAN__") {
                    startDiscovery()
                } else {
                    val dev = adapter.getRemoteDevice(addr)
                    btService = BluetoothChatService(btHandler)
                    btService?.connect(dev)
                    updateStatus("Conectando a ${dev.name}...")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startDiscovery() {
        if (isScanning) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        isScanning = true
        discoveredDevices.clear()

        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && device.name != null && device !in discoveredDevices) {
                        discoveredDevices.add(device)
                        pendingDeviceList.add("${device.name}\n${device.address}")
                        pendingDeviceAddr.add(device.address)
                        (devicePickerDialog?.listView?.adapter as? ArrayAdapter<String>)?.notifyDataSetChanged()
                    }
                } else if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                    isScanning = false
                    if (discoveredDevices.isEmpty()) {
                        pendingDeviceList.add("(no se encontraron dispositivos)")
                        pendingDeviceAddr.add("__NONE__")
                        (devicePickerDialog?.listView?.adapter as? ArrayAdapter<String>)?.notifyDataSetChanged()
                    }
                    try { unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(discoveryReceiver, filter)
        adapter.startDiscovery()
        updateStatus("Escaneando dispositivos...")
    }

    private fun updateBtIndicator(connected: Boolean, deviceName: String?) {
        val color = if (connected) ContextCompat.getColor(this, R.color.zone_green)
                    else ContextCompat.getColor(this, R.color.zone_red)
        binding.btIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        binding.tvBtStatus.text = if (connected) "BT: $deviceName" else "Desconectado"
        binding.tvBtStatus.setTextColor(
            if (connected) ContextCompat.getColor(this, R.color.zone_green)
            else ContextCompat.getColor(this, R.color.text_muted)
        )
    }

    private fun startCamera() {
        try {
            val provider = ProcessCameraProvider.getInstance(this)
            provider.addListener({
                val cameraProvider = provider.get()
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                val analyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(FRAME_W, FRAME_H))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }

                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, analyzer)
                cameraActive = true

                binding.btnStart.visibility = View.GONE
                binding.btnStop.visibility = View.VISIBLE
                updateStatus("GUARDIÁN ACTIVO")
                setZoneIndicator(DetectionOverlayView.ZONE_GREEN)
                speak("Guardián activado")
                sendBT(3)
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e(TAG, "Error cámara", e)
            updateStatus("Error cámara: ${e.message}")
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (isProcessing || detector == null || !cameraActive) {
            imageProxy.close()
            return
        }
        isProcessing = true

        updateFps()

        val bitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()

        inferenceExecutor.execute {
            try {
                val tensorImage = TensorImage.fromBitmap(bitmap)
                val detections = detector?.detect(tensorImage) ?: emptyList()

                var maxRatio = 0f
                var topClass = ""
                var topScore = 0f

                for (d in detections) {
                    val catId = d.categories.firstOrNull()?.index ?: -1
                    val score = d.categories.firstOrNull()?.score ?: 0f

                    if (catId !in INTRUDER_CLASSES || score < CONF_THRESHOLD) continue

                    val box = d.boundingBox
                    val ratio = (box.width() * box.height()).toFloat() / FRAME_AREA

                    if (ratio > maxRatio) {
                        maxRatio = ratio
                        topClass = getCocoLabel(catId)
                        topScore = score
                    }
                }

                val zone = when {
                    maxRatio == 0f -> DetectionOverlayView.ZONE_GREEN
                    maxRatio >= RED_RATIO -> DetectionOverlayView.ZONE_RED
                    maxRatio >= YELLOW_RATIO -> DetectionOverlayView.ZONE_YELLOW
                    else -> DetectionOverlayView.ZONE_GREEN
                }

                val cmd = when (zone) {
                    DetectionOverlayView.ZONE_RED -> 1    // Peligro
                    DetectionOverlayView.ZONE_YELLOW -> 2  // Precaución
                    else -> 3                              // Seguro
                }

                mainHandler.post {
                    binding.detectionOverlay.setDetections(detections, FRAME_W, FRAME_H, zone)
                }

                if (zone != lastZone) {
                    lastZone = zone
                    setZoneIndicator(zone)
                    sendBT(cmd)
                    if (zone == DetectionOverlayView.ZONE_RED) {
                        speak("Intruso detectado")
                        addIntrusionEvent(topClass, topScore, maxRatio)
                    }
                }

                val confPct = (topScore * 100).toInt()
                val zoneName = when (zone) {
                    DetectionOverlayView.ZONE_GREEN -> "VERDE"
                    DetectionOverlayView.ZONE_YELLOW -> "AMARILLO"
                    else -> "ROJO"
                }
                val sizeText = if (maxRatio > 0f) "${(maxRatio * 100).toInt()}%" else "---"
                updateDetection("$topClass $confPct% | $sizeText → $zoneName ($cmd)")

            } catch (e: Exception) {
                Log.e(TAG, "Error inferencia", e)
            } finally {
                bitmap.recycle()
                isProcessing = false
            }
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTime = now
            mainHandler.post { binding.tvFps.text = currentFps.toString() }
        }
    }

    private fun addIntrusionEvent(type: String, confidence: Float, sizeRatio: Float) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val event = IntrusionEvent(type, confidence, sizeRatio, time)
        mainHandler.post {
            intrusionLog.add(0, event)
            if (intrusionLog.size > MAX_LOG_SIZE) intrusionLog.removeAt(intrusionLog.lastIndex)
            binding.tvCount.text = intrusionLog.size.toString()
            intrusionAdapter.notifyItemInserted(0)
            binding.rvIntrusionLog.scrollToPosition(0)
        }
    }

    private fun setZoneIndicator(zone: Int) {
        mainHandler.post {
            val color = when (zone) {
                DetectionOverlayView.ZONE_RED -> ContextCompat.getColor(this, R.color.zone_red)
                DetectionOverlayView.ZONE_YELLOW -> ContextCompat.getColor(this, R.color.zone_yellow)
                else -> ContextCompat.getColor(this, R.color.zone_green)
            }
            val dimColor = when (zone) {
                DetectionOverlayView.ZONE_RED -> ContextCompat.getColor(this, R.color.zone_red_dim)
                DetectionOverlayView.ZONE_YELLOW -> ContextCompat.getColor(this, R.color.zone_yellow_dim)
                else -> ContextCompat.getColor(this, R.color.zone_green_dim)
            }
            val text = when (zone) {
                DetectionOverlayView.ZONE_RED -> "INTRUSO DETECTADO"
                DetectionOverlayView.ZONE_YELLOW -> "PRECAUCIÓN"
                else -> "DESPEJADO"
            }
            val badgeText = when (zone) {
                DetectionOverlayView.ZONE_RED -> "ALERTA"
                DetectionOverlayView.ZONE_YELLOW -> "ATENCIÓN"
                else -> "DESPEJADO"
            }
            val bgRes = when (zone) {
                DetectionOverlayView.ZONE_RED -> R.color.zone_red_glow
                DetectionOverlayView.ZONE_YELLOW -> R.color.zone_yellow_glow
                else -> R.color.zone_green_glow
            }
            binding.zoneColorBar.setBackgroundColor(color)
            binding.zoneColorBar.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            binding.zoneIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            binding.tvZoneText.text = text
            binding.tvZoneText.setTextColor(color)
            binding.tvZoneBadge.text = badgeText
            binding.tvZoneBadge.setTextColor(color)
            binding.tvZoneBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(dimColor)
        }
    }

    private fun imageProxyToBitmap(img: ImageProxy): Bitmap {
        val buffer = img.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val yuv = YuvImage(bytes, ImageFormat.NV21, img.width, img.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, img.width, img.height), 90, out)
        val jpg = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
    }

    private fun getCocoLabel(id: Int): String = when (id) {
        1 -> "persona"; 16 -> "gato"; 17 -> "perro"
        18 -> "caballo"; 19 -> "oveja"; 20 -> "vaca"
        21 -> "elefante"; 22 -> "oso"; 23 -> "cebra"; 24 -> "jirafa"
        else -> "obj_$id"
    }

    private fun sendBT(n: Int) {
        btService?.write("$n\n")
    }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guardian")
    }

    private fun updateStatus(text: String) {
        mainHandler.post { binding.tvZoneText.text = text }
    }

    private fun updateDetection(text: String) {
        mainHandler.post { binding.tvDetection.text = text }
    }

    private fun onStopClick() {
        stopCamera()
        closeBT()
        cameraActive = false
        lastSent = 3
        lastZone = DetectionOverlayView.ZONE_GREEN
        binding.btnStart.visibility = View.VISIBLE
        binding.btnStop.visibility = View.GONE
        binding.detectionOverlay.clearDetections()
        updateStatus("DETENIDO")
        updateDetection("")
        binding.zoneColorBar.setBackgroundColor(Color.DKGRAY)
        binding.zoneIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.DKGRAY)
        binding.tvZoneText.text = "DETENIDO"
        binding.tvZoneText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvZoneBadge.text = "INACTIVO"
        binding.tvZoneBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        binding.tvZoneBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        updateBtIndicator(false, null)
        binding.tvFps.text = "0"
        speak("Guardián desactivado")
    }

    private fun stopCamera() {
        try {
            val provider = ProcessCameraProvider.getInstance(this)
            provider.addListener({ provider.get().unbindAll() }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {}
    }

    private fun closeBT() {
        btService?.stop()
        btService = null
        try {
            if (discoveryReceiver != null) {
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                unregisterReceiver(discoveryReceiver)
                discoveryReceiver = null
            }
        } catch (_: Exception) {}
        isScanning = false
    }

    override fun onDestroy() {
        stopCamera()
        closeBT()
        tts?.shutdown()
        cameraExecutor.shutdown()
        inferenceExecutor.shutdown()
        super.onDestroy()
    }

    data class IntrusionEvent(
        val type: String,
        val confidence: Float,
        val sizeRatio: Float,
        val time: String
    )

    inner class IntrusionAdapter(
        private val items: List<IntrusionEvent>
    ) : RecyclerView.Adapter<IntrusionAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val colorBar: View = view.findViewById(R.id.intrusionColorBar)
            val tvType: TextView = view.findViewById(R.id.tvIntrusionType)
            val tvDetail: TextView = view.findViewById(R.id.tvIntrusionDetail)
            val tvTime: TextView = view.findViewById(R.id.tvIntrusionTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_intrusion, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val event = items[position]
            val color = when {
                event.type == "persona" -> ContextCompat.getColor(holder.itemView.context, R.color.zone_red)
                event.type == "perro" || event.type == "oso" -> ContextCompat.getColor(holder.itemView.context, R.color.zone_yellow)
                else -> ContextCompat.getColor(holder.itemView.context, R.color.zone_green)
            }
            holder.colorBar.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            holder.tvType.text = event.type
            holder.tvDetail.text = "${(event.confidence * 100).toInt()}% | ${(event.sizeRatio * 100).toInt()}%"
            holder.tvTime.text = event.time
        }

        override fun getItemCount() = items.size
    }
}
