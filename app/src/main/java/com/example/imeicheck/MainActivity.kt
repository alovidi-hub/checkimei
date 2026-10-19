package com.example.imeicheck

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultView: TextView
    private lateinit var manualInput: EditText
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val netExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    private var lastImei: String? = null

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_QR_CODE)
            .build()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewView = PreviewView(this)
        root.addView(previewView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        resultView = TextView(this).apply {
            text = "Наведите камеру на штрих-код IMEI (коробка, SIM-лоток, Настройки → Основные → Об этом устройстве)"
            textSize = 16f; setPadding(32, 24, 32, 8)
        }
        root.addView(resultView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(24, 0, 24, 24) }
        manualInput = EditText(this).apply {
            hint = "или введите IMEI вручную"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        val btn = Button(this).apply {
            text = "Проверить"
            setOnClickListener { handleImei(manualInput.text.toString().trim(), force = true) }
        }
        row.addView(manualInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(btn)
        root.addView(row)
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || busy) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                codes.mapNotNull { it.rawValue }.forEach { raw ->
                    val digits = raw.filter { it.isDigit() }
                    if (digits.length == 15 && digits != lastImei) {
                        runOnUiThread { handleImei(digits, force = false) }
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun handleImei(imei: String, force: Boolean) {
        if (!isValidImei(imei)) {
            resultView.text = "Некорректный IMEI (нужно 15 цифр, проверка Луна не пройдена)"
            return
        }
        if (busy && !force) return
        busy = true
        lastImei = imei
        resultView.setTextColor(Color.GRAY)
        resultView.text = "IMEI $imei\nПроверяю…"
        netExecutor.execute {
            val msg: String; val color: Int
            try {
                val r = FindMyChecker.check(imei)
                when (r.status) {
                    FindMyStatus.ON -> { msg = "Найти iPhone: ВКЛЮЧЕНО (устройство привязано к iCloud)"; color = Color.rgb(200, 30, 30) }
                    FindMyStatus.OFF -> { msg = "Найти iPhone: ВЫКЛЮЧЕНО"; color = Color.rgb(30, 140, 60) }
                    FindMyStatus.UNKNOWN -> { msg = "Статус не определён.\n${r.raw.take(200)}"; color = Color.DKGRAY }
                }
                runOnUiThread {
                    resultView.setTextColor(color)
                    resultView.text = "IMEI $imei\n${r.model?.let { "$it\n" } ?: ""}$msg"
                }
            } catch (e: Exception) {
                runOnUiThread { resultView.setTextColor(Color.RED); resultView.text = "Ошибка сети: ${e.message}" }
            } finally { busy = false }
        }
    }

    private fun isValidImei(s: String): Boolean {
        if (s.length != 15 || !s.all { it.isDigit() }) return false
        var sum = 0
        for (i in s.indices) {
            var d = s[14 - i] - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 10 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else resultView.text = "Нужен доступ к камере"
    }

    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown(); netExecutor.shutdown() }
}
