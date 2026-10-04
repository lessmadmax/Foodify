package com.example.foodify.ar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Diagnostic collection only: no volume/weight inference and no upload. */
class ArDiagnosticActivity : Activity(), GLSurfaceView.Renderer {
    @Volatile private var session: Session? = null
    private lateinit var surface: GLSurfaceView
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var depthView: ImageView
    private lateinit var confidenceView: ImageView
    private lateinit var recordButton: Button
    private val background = CameraBackground()
    private val writer = Executors.newSingleThreadExecutor()
    private var installRequested = false
    private var permissionRequested = false
    private var resumed = false
    private var surfaceRunning = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var lastUi = 0L
    private var lastDepthTimestamp = 0L
    private var lastNewDepthTime = 0L
    private var recording: CaptureRun? = null // GL thread only
    private var lastSavedDirectory: String? = null

    private class CaptureRun(val directory: File, val deadline: Long) {
        var count = 0
        var lastSampleTime = 0L
        var lastTimestamp = 0L
        var saved = 0 // writer thread only
        var failure: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(12, 12, 12, 12)
            fitsSystemWindows = true
        }
        root.addView(text("Foodify · AR 깊이 진단", 19f))
        root.addView(text("약 0.5m부터 시작해 밝은 곳에서 천천히 옆으로 이동하세요. 음식량 측정 전 실험 화면입니다.", 13f))
        status = text("카메라·AR 서비스 확인 중", 14f)
        root.addView(status)
        val cameraBox = FrameLayout(this)
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(this@ArDiagnosticActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        cameraBox.addView(surface, FrameLayout.LayoutParams(-1, -1))
        cameraBox.addView(text("+", 32f), FrameLayout.LayoutParams(44, 60, Gravity.CENTER))
        root.addView(cameraBox, LinearLayout.LayoutParams(-1, 0, 1f))
        metrics = text("깊이 수집 대기", 13f)
        root.addView(metrics)
        val maps = LinearLayout(this)
        fun mapColumn(title: String): ImageView {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(text(title, 11f))
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            column.addView(image, LinearLayout.LayoutParams(-1, (90 * resources.displayMetrics.density).toInt()))
            maps.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            return image
        }
        depthView = mapColumn("원본 깊이: 빨강 0.2m → 파랑 2m")
        confidenceView = mapColumn("원본 신뢰도: 검정 0 → 흰색 255")
        root.addView(maps)
        root.addView(text("지도는 센서 방향입니다. 검정 깊이=미확보. 신뢰도는 오차율이 아닙니다.", 11f))
        recordButton = Button(this).apply {
            text = "5초 진단 데이터 저장"
            isEnabled = false
            setOnClickListener {
                isEnabled = false
                text = "천천히 이동하세요 · 수집 중"
                surface.queueEvent {
                    val dir = File(filesDir, "ar-diagnostics/" + UUID.randomUUID().toString())
                    recording = CaptureRun(dir, SystemClock.elapsedRealtime() + 5000)
                }
            }
        }
        root.addView(recordButton)
        root.addView(text("사진·깊이는 앱 내부에만 저장됩니다. 서버·OpenAI 전송 없음.", 11f))
        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply {
            text = "권한 설정"
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply {
            text = "돌아가기"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        setContentView(root)
    }

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        setPadding(4, 4, 4, 4)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startSession()
    }

    private fun startSession() {
        if (!resumed || surfaceRunning) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.text = "카메라 권한이 필요합니다. 거부한 경우 권한 설정에서 허용해 주세요."
            if (!permissionRequested) {
                permissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 400)
            }
            return
        }
        try {
            if (session == null) {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    status.text = "Google Play AR 서비스 설치·업데이트 후 돌아와 주세요."
                    return
                }
                val next = Session(this)
                if (!next.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    next.close()
                    status.text = "이 기기·AR 서비스에서 Depth API를 사용할 수 없습니다."
                    return
                }
                next.configure(next.config.apply {
                    depthMode = Config.DepthMode.AUTOMATIC
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                })
                session = next
            }
            session!!.resume()
            surface.onResume()
            surfaceRunning = true
            status.text = "ARCore Depth 활성화 · 천천히 움직여 추적을 시작하세요."
        } catch (e: Exception) {
            status.text = "AR 시작 실패: ${e.javaClass.simpleName}. 권한·AR 서비스·카메라 사용 상태를 확인하세요."
            session?.close()
            session = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 400) startSession()
    }

    override fun onPause() {
        resumed = false
        if (surfaceRunning) {
            surface.queueEvent { finishCapture("ACTIVITY_PAUSED") }
            surface.onPause() // stop GL update before pausing camera session
            session?.pause()
            surfaceRunning = false
        }
        recordButton.isEnabled = false
        super.onPause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        writer.shutdown() // finish already copied file writes
        super.onDestroy()
    }

    override fun finish() {
        setResult(RESULT_OK, Intent().putExtra("directory", lastSavedDirectory))
        super.finish()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try { background.create() } catch (e: Exception) { showFailure(e) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width; viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    @Suppress("DEPRECATION")
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val active = session ?: return
        val now = SystemClock.elapsedRealtime()
        if (recording != null && now >= recording!!.deadline) finishCapture("DURATION_COMPLETE")
        try {
            if (background.textureId == 0) return
            active.setCameraTextureName(background.textureId)
            active.setDisplayGeometry(windowManager.defaultDisplay.rotation, viewportWidth, viewportHeight)
            val frame = active.update()
            if (frame.timestamp == 0L) return
            background.draw(frame)
            if (now - lastUi < 200) return
            lastUi = now
            if (frame.camera.trackingState != TrackingState.TRACKING) {
                val failureReason = frame.camera.trackingFailureReason.toString()
                runOnUiThread {
                    if (!isDestroyed) {
                        status.text = "추적 대기: $failureReason · 밝은 곳에서 천천히 이동하세요."
                        metrics.text = "추적이 안정되면 깊이를 표시합니다."
                        recordButton.isEnabled = false
                        depthView.setImageDrawable(null); confidenceView.setImageDrawable(null)
                    }
                }
                return
            }
            sample(frame, now)
        } catch (_: NotYetAvailableException) {
            runOnUiThread {
                if (!isDestroyed) {
                    metrics.text = "깊이·카메라 데이터 준비 중 · 좌우로 천천히 이동하세요."
                    recordButton.isEnabled = false
                    depthView.setImageDrawable(null); confidenceView.setImageDrawable(null)
                }
            }
        } catch (e: Exception) {
            showFailure(e)
        }
    }

    private fun showFailure(e: Exception) {
        runOnUiThread {
            if (!isDestroyed) {
                status.text = "AR 처리 오류: ${e.javaClass.simpleName} · 화면을 나갔다 다시 열어 주세요."
                recordButton.isEnabled = false
            }
        }
    }

    private fun planeBytes(image: Image, bytes: Int): ByteArray {
        val p = image.planes[0]
        return DepthData.pack(p.buffer, image.width, image.height, p.rowStride, p.pixelStride, bytes)
    }

    private fun sample(frame: Frame, now: Long) {
        frame.acquireRawDepthImage16Bits().use { depth ->
            frame.acquireRawDepthConfidenceImage().use { confidence ->
                check(depth.width == confidence.width && depth.height == confidence.height)
                val d = planeBytes(depth, 2)
                val c = planeBytes(confidence, 1)
                val stats = DepthData.stats(d, c)
                val fresh = depth.timestamp != lastDepthTimestamp
                if (fresh) lastNewDepthTime = now
                lastDepthTimestamp = depth.timestamp
                val centerUv = FloatArray(2)
                frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, floatArrayOf(.5f,.5f),
                    Coordinates2d.TEXTURE_NORMALIZED, centerUv)
                val cx = (centerUv[0] * depth.width).toInt()
                val cy = (centerUv[1] * depth.height).toInt()
                val index = if (cx in 0 until depth.width && cy in 0 until depth.height) cy * depth.width + cx else -1
                val center = if (index >= 0 && (c[index].toInt() and 255) >= DepthData.CONFIDENCE_THRESHOLD)
                    DepthData.millimetres(d,index).takeIf { it > 0 } else null
                val planes = session!!.getAllTrackables(Plane::class.java).count { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                val depthBitmap = depthBitmap(d, depth.width, depth.height)
                val colors = IntArray(c.size) { i -> val value = c[i].toInt() and 255; Color.rgb(value,value,value) }
                val confidenceBitmap = Bitmap.createBitmap(colors,depth.width,depth.height,Bitmap.Config.ARGB_8888)
                val run = recording
                if (run != null && run.count < 10 && now - run.lastSampleTime >= 500 &&
                    depth.timestamp != run.lastTimestamp && stats.confident > 0) {
                    try {
                        capture(frame, depth, confidence.timestamp, d, c, stats, run)
                        run.lastTimestamp = depth.timestamp
                        run.lastSampleTime = now
                    } catch (_: NotYetAvailableException) { /* wait for a matching CPU image */ }
                }
                val message = String.format(Locale.KOREA,
                    "중앙 깊이(Z): %s · 평면 %d개\n유효 %.1f%% · 신뢰도 ≥128 %.1f%% · %d×%d\n새 깊이 이후 %dms · %s",
                    center?.let { "$it mm" } ?: "미확보", planes,
                    100.0*stats.valid/stats.total, 100.0*stats.confident/stats.total,
                    depth.width,depth.height, now-lastNewDepthTime, if(fresh) "새 데이터" else "이전 깊이 재투영")
                runOnUiThread {
                    if (!isDestroyed && resumed) {
                        status.text = "추적 정상 · 거리·부피 정확도 검증 전"
                        metrics.text = message
                        depthView.setImageBitmap(depthBitmap)
                        confidenceView.setImageBitmap(confidenceBitmap)
                        if (run == null && recordButton.text != "파일 저장 중…") recordButton.isEnabled = stats.confident > 0
                    }
                }
            }
        }
    }

    private fun depthBitmap(d: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width*height) { i ->
            val mm = DepthData.millimetres(d,i)
            if (mm == 0) Color.BLACK else Color.HSVToColor(floatArrayOf(
                ((mm - 200) / 1800f).coerceIn(0f,1f)*240f, 1f, 1f))
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }

    @Suppress("DEPRECATION")
    private fun capture(frame: Frame, depth: Image, confidenceTimestamp: Long, d: ByteArray, c: ByteArray,
                        stats: DepthData.Stats, run: CaptureRun) {
        frame.acquireCameraImage().use { cameraImage ->
            val files = linkedMapOf("depth.u16le" to d, "confidence.u8" to c)
            val planeInfo = JSONArray()
            cameraImage.planes.forEachIndexed { i, p ->
                val w = if(i == 0) cameraImage.width else (cameraImage.width+1)/2
                val h = if(i == 0) cameraImage.height else (cameraImage.height+1)/2
                val name = listOf("camera-y.u8","camera-u.u8","camera-v.u8")[i]
                files[name] = DepthData.pack(p.buffer,w,h,p.rowStride,p.pixelStride,1)
                planeInfo.put(JSONObject().put("file",name).put("width",w).put("height",h))
            }
            val camera = frame.camera
            fun intrinsics(value: CameraIntrinsics) = JSONObject()
                .put("focalLength",JSONArray(value.focalLength.toList()))
                .put("principalPoint",JSONArray(value.principalPoint.toList()))
                .put("imageDimensions",JSONArray(value.imageDimensions.toList()))
            val corners = floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f)
            val imageCorners = FloatArray(8)
            frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED,corners,
                Coordinates2d.IMAGE_NORMALIZED,imageCorners)
            val meta = JSONObject().put("schemaVersion",1)
                .put("frameTimestampNs",frame.timestamp.toString())
                .put("depthTimestampNs",depth.timestamp.toString())
                .put("confidenceTimestampNs",confidenceTimestamp.toString())
                .put("cameraTimestampNs",cameraImage.timestamp.toString())
                .put("displayRotation",windowManager.defaultDisplay.rotation)
                .put("viewportWidth",viewportWidth).put("viewportHeight",viewportHeight)
                .put("depthMatchesFrameTimestamp",depth.timestamp == frame.timestamp)
                .put("depthWidth",depth.width).put("depthHeight",depth.height)
                .put("depthUnit","millimetres_camera_z").put("invalidDepthValue",0)
                .put("confidenceThreshold",DepthData.CONFIDENCE_THRESHOLD)
                .put("confidentPixels",stats.confident).put("validPixels",stats.valid)
                .put("trackingState",camera.trackingState.name)
                .put("translationMetres",JSONArray(camera.pose.translation.toList()))
                .put("rotationQuaternionXyzw",JSONArray(camera.pose.rotationQuaternion.toList()))
                .put("cpuIntrinsics",intrinsics(camera.imageIntrinsics))
                .put("gpuIntrinsics",intrinsics(camera.textureIntrinsics))
                .put("depthNormalizedCorners",JSONArray(corners.toList()))
                .put("correspondingCpuImageNormalizedCorners",JSONArray(imageCorners.toList()))
                .put("cameraFormat",cameraImage.format).put("cameraPlanes",planeInfo)
                .put("cameraCrop",cameraImage.cropRect.toShortString())
                .put("device",android.os.Build.MODEL).put("androidSdk",android.os.Build.VERSION.SDK_INT)
                .put("volumeValidated",false)
            val name = "frame-%02d".format(Locale.ROOT,run.count++)
            writer.execute {
                try {
                    val root = File(filesDir,"ar-diagnostics")
                    val existing = if(root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                    check(existing + files.values.sumOf { it.size.toLong() } < 200L*1024*1024) { "LOCAL_STORAGE_LIMIT" }
                    val dir = File(run.directory,name)
                    check(dir.mkdirs()) { "DIRECTORY_CREATE_FAILED" }
                    for((filename,bytes) in files) File(dir,filename).writeBytes(bytes)
                    File(dir,"metadata.json").writeText(meta.toString(2))
                    run.saved++
                } catch(e: Exception) { run.failure = e.message ?: e.javaClass.simpleName }
            }
        }
    }

    private fun finishCapture(reason: String) {
        val run = recording ?: return
        recording = null
        runOnUiThread { if(!isDestroyed) { recordButton.text = "파일 저장 중…"; recordButton.isEnabled = false } }
        writer.execute {
            try {
                run.directory.mkdirs()
                File(run.directory,"session.json").writeText(JSONObject()
                    .put("schemaVersion",1).put("stopReason",reason)
                    .put("attemptedFrames",run.count).put("savedFrames",run.saved)
                    .put("error",run.failure ?: JSONObject.NULL)
                    .put("device",android.os.Build.MODEL)
                    .put("createdAtEpochMs",System.currentTimeMillis())
                    .put("volumeValidated",false).toString(2))
            } catch(e: Exception) { run.failure = e.javaClass.simpleName }
            runOnUiThread {
                if(!isDestroyed) {
                    lastSavedDirectory = run.directory.name
                    recordButton.text = "5초 진단 데이터 저장"
                    recordButton.isEnabled = resumed && run.failure == null
                    Toast.makeText(this,
                        if(run.failure != null) "저장 오류: ${run.failure}"
                        else if(run.saved == 0) "새 깊이를 확보하지 못했습니다. 밝은 곳에서 다시 촬영하세요."
                        else "진단 ${run.saved}프레임 저장 완료", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
