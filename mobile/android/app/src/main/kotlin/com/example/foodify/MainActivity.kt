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
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "foodify/ar")
            .setMethodCallHandler { call, result ->
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
}
