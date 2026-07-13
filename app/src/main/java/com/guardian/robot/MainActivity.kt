package com.guardian.robot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.guardian.robot.databinding.ActivityMainBinding
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.support.image.TensorImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var detector: ObjectDetector? = null
    private var btSocket: BluetoothSocket? = null
    private var tts: TextToSpeech? = null

    private var isProcessing = false
    private var cameraActive = false
    private var lastSent = 'G'
    private var lastZone = DetectionOverlayView.ZONE_GREEN

    private val intrusionLog = mutableListOf<IntrusionEvent>()
    private lateinit var intrusionAdapter: IntrusionAdapter

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0

    companion object {
        private val INTRUDER_CLASSES = setOf(1, 16, 17, 18, 19, 20, 21, 22, 23, 24)
        private const val CONF_THRESHOLD = 0.6f
        private const val YELLOW_RATIO = 0.05f
        private const val RED_RATIO = 0.18f
        private const val FRAME_W = 640
        private const val FRAME_H = 480
        private const val FRAME_AREA = FRAME_W * FRAME_H
        private const val BT_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private const val TAG = "Guardian"
        private const val PERM_REQ_CODE = 100
        private const val MAX_LOG_SIZE = 50
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
            updateStatus("Conectando Bluetooth...")
            inferenceExecutor.execute { connectBluetooth() }
        } else {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQ_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, grants)
        if (requestCode == PERM_REQ_CODE && grants.all { it == PackageManager.PERMISSION_GRANTED }) {
            updateStatus("Conectando Bluetooth...")
            inferenceExecutor.execute { connectBluetooth() }
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

            var device = adapter.bondedDevices.firstOrNull { it.name?.contains("HC-05", true) == true }
            if (device == null) device = adapter.bondedDevices.firstOrNull { it.name?.contains("HC-06", true) == true }
            if (device == null) device = adapter.bondedDevices.firstOrNull()

            if (device == null) {
                updateStatus("Empareja el módulo BT en Ajustes primero")
                mainHandler.post { updateBtIndicator(false, null) }
                return
            }

            btSocket = device.createRfcommSocketToServiceRecord(UUID.fromString(BT_UUID))
            btSocket?.connect()

            val devName = device.name ?: "Desconocido"
            updateStatus("BT conectado a $devName. Iniciando cámara...")
            mainHandler.post {
                updateBtIndicator(true, devName)
                startCamera()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error BT", e)
            updateStatus("Error BT: ${e.message}")
            mainHandler.post { updateBtIndicator(false, null) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permiso BT", e)
            updateStatus("Permiso BT denegado")
            mainHandler.post { updateBtIndicator(false, null) }
        }
    }

    private fun updateBtIndicator(connected: Boolean, deviceName: String?) {
        val color = if (connected) ContextCompat.getColor(this, R.color.zone_green)
                    else ContextCompat.getColor(this, R.color.zone_red)
        binding.btIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        binding.tvBtStatus.text = if (connected) "BT: $deviceName" else "BT: Desconectado"
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
                sendBT('G')
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
                    DetectionOverlayView.ZONE_RED -> 'P'
                    DetectionOverlayView.ZONE_YELLOW -> 'Y'
                    else -> 'G'
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
            intrusionAdapter.notifyItemInserted(0)
            binding.rvIntrusionLog.scrollToPosition(0)
        }
    }

    private fun setZoneIndicator(zone: Int) {
        mainHandler.post {
            val colorRes = when (zone) {
                DetectionOverlayView.ZONE_RED -> R.color.zone_red
                DetectionOverlayView.ZONE_YELLOW -> R.color.zone_yellow
                else -> R.color.zone_green
            }
            val color = ContextCompat.getColor(this, colorRes)
            val text = when (zone) {
                DetectionOverlayView.ZONE_RED -> "INTRUSO DETECTADO"
                DetectionOverlayView.ZONE_YELLOW -> "PRECAUCIÓN"
                else -> "DESPEJADO"
            }
            binding.zoneColorBar.setBackgroundColor(color)
            binding.tvZoneText.text = text
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

    private fun sendBT(c: Char) {
        try {
            btSocket?.outputStream?.write(c.code)
            btSocket?.outputStream?.flush()
        } catch (_: Exception) {
            updateStatus("Error enviando BT")
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
        lastSent = 'G'
        lastZone = DetectionOverlayView.ZONE_GREEN
        binding.btnStart.visibility = View.VISIBLE
        binding.btnStop.visibility = View.GONE
        binding.detectionOverlay.clearDetections()
        updateStatus("DETENIDO")
        updateDetection("")
        binding.zoneColorBar.setBackgroundColor(Color.GRAY)
        binding.tvZoneText.text = "DETENIDO"
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
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
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
            val colorDot: View = view.findViewById(R.id.intrusionColorDot)
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
            holder.colorDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            holder.tvType.text = event.type
            holder.tvDetail.text = "${(event.confidence * 100).toInt()}% | ${(event.sizeRatio * 100).toInt()}%"
            holder.tvTime.text = event.time
        }

        override fun getItemCount() = items.size
    }
}
