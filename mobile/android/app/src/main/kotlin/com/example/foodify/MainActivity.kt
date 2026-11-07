package com.example.foodify

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.Config
import android.content.Intent
import com.example.foodify.ar.ArDiagnosticActivity

class MainActivity : FlutterActivity() {
    private var localModel: com.example.foodify.local.LocalModelBridge? = null
    private var classifier: com.example.foodify.local.FoodClassifierBridge? = null
    private var segmentation: com.example.foodify.local.SegmentationBridge? = null
    private var arResearch: com.example.foodify.ar.ArResearchBridge? = null
    private var captureResult: MethodChannel.Result? = null
    @Deprecated("Android activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7403) { arResearch?.onFileResult(resultCode, data); return }
        if (requestCode == 7402) { localModel?.onFileResult(resultCode, data); return }
        if (requestCode != 7401) return
        val pending = captureResult ?: return
        captureResult = null
        if (data?.getBooleanExtra("fallback", false) == true) { pending.success(mapOf("fallback" to true)); return }
        val directory = data?.getStringExtra("directory")
        if (resultCode != RESULT_OK || directory == null) { pending.success(null); return }
        Thread {
            try {
                val exported = com.example.foodify.ar.ArCaptureExport.export(java.io.File(filesDir, "ar-diagnostics"), directory)
                runOnUiThread { pending.success(exported) }
            } catch (e: Exception) { runOnUiThread { pending.error("AR_EXPORT_FAILED", e.message, null) } }
        }.start()
    }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        arResearch = com.example.foodify.ar.ArResearchBridge(this, flutterEngine.dartExecutor.binaryMessenger)
        localModel = com.example.foodify.local.LocalModelBridge(this, flutterEngine.dartExecutor.binaryMessenger)
        classifier = com.example.foodify.local.FoodClassifierBridge(this, flutterEngine.dartExecutor.binaryMessenger)
        segmentation = com.example.foodify.local.SegmentationBridge(this, flutterEngine.dartExecutor.binaryMessenger)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "foodify/ar")
            .setMethodCallHandler { call, result ->
                if (call.method == "captureMeal") {
                    if (captureResult != null) { result.error("AR_BUSY", "AR 촬영 중입니다.", null); return@setMethodCallHandler }
                    captureResult = result
                    try { startActivityForResult(Intent(this, ArDiagnosticActivity::class.java).putExtra("mealCapture", true).putExtra("researchCapture", call.argument<Boolean>("research") == true), 7401) }
                    catch (e: Exception) { captureResult = null; result.error("AR_UNAVAILABLE", e.message, null) }
                    return@setMethodCallHandler
                }
                if (call.method == "openDiagnostics") {
                    if (android.os.Build.VERSION.SDK_INT < 24) {
                        result.error("AR_UNSUPPORTED", "AR 진단은 Android 7 이상에서 사용할 수 있습니다.", null)
                    } else {
                        startActivity(Intent(this, ArDiagnosticActivity::class.java))
                        result.success(null)
                    }
                    return@setMethodCallHandler
                }
                if (call.method != "capabilities") { result.notImplemented(); return@setMethodCallHandler }
                val availability = ArCoreApk.getInstance().checkAvailability(this)
                var depth: Boolean? = null
                var reason = "실기기 세션 확인 필요"
                if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED) {
                    try {
                        val session = Session(this)
                        try { depth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC); reason = "지원 여부 확인 완료" }
                        finally { session.close() }
                    } catch (_: Exception) { reason = "카메라 권한 또는 AR 서비스 확인 필요" }
                }
                result.success(mapOf("arCore" to availability.name, "depth" to depth, "reason" to reason,
                    "volumeValidated" to false, "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
            }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "com.example.foodify.DEBUG_CLASSIFY50") classifier?.evaluateBatch()
        if (intent.action == "com.example.foodify.DEBUG_EVALUATE50") localModel?.evaluateBatch()
    }
    override fun onDestroy() {
        localModel?.dispose()
        classifier?.dispose()
        segmentation?.dispose()
        arResearch?.dispose()
        super.onDestroy()
    }
}
