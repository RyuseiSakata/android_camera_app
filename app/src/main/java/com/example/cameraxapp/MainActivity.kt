package com.example.cameraxapp

import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameraxapp.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.PermissionChecker
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import android.widget.ImageView
import android.widget.FrameLayout
import android.view.ViewGroup
import java.io.File
import android.view.Gravity
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import android.view.MotionEvent

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var stampImageView: ImageView
    
    // スタンプの位置を保持する変数
    private var stampX: Float = 0f
    private var stampY: Float = 0f
    private var initialX: Float = 0f
    private var initialY: Float = 0f
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy) {

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            imageCapture = ImageCapture.Builder().build()

            /*
            val imageAnalyzer = ImageAnalysis.Builder().build()
                .also {
                    setAnalyzer(
                        cameraExecutor,
                        LuminosityAnalyzer { luma ->
                            Log.d(TAG, "Average luminosity: $luma")
                        }
                    )
                }
            */

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Add stamp image overlay
        setupStampOverlay()

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupStampOverlay() {
        stampImageView = ImageView(this)
        stampImageView.setImageResource(R.drawable.stamp)
        
        // スタンプのサイズを画面の20%程度に設定
        val displayMetrics = resources.displayMetrics
        val stampSize = (displayMetrics.widthPixels * 0.2).toInt()
        
        // スタンプを含むFrameLayoutを作成
        val frameLayout = FrameLayout(this)
        frameLayout.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        
        // スタンプのレイアウトパラメータを設定
        val stampParams = FrameLayout.LayoutParams(
            stampSize,
            stampSize
        ).apply {
            gravity = Gravity.NO_GRAVITY  // 重力を解除して自由に移動できるように
        }
        
        // スタンプをFrameLayoutに追加
        stampImageView.layoutParams = stampParams
        frameLayout.addView(stampImageView)
        
        // ドラッグ処理の設定
        stampImageView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // タッチ開始時の位置を記録
                    initialX = view.x
                    initialY = view.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 移動量を計算して反映
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    view.x = initialX + dx
                    view.y = initialY + dy
                    
                    // 現在位置を保存
                    stampX = view.x
                    stampY = view.y
                    true
                }
                else -> false
            }
        }
        
        // カメラプレビューの親ViewGroupを取得
        val parent = viewBinding.viewFinder.parent as ViewGroup
        val index = parent.indexOfChild(viewBinding.viewFinder)
        
        // カメラプレビューを一時的に削除
        parent.removeView(viewBinding.viewFinder)
        
        // FrameLayoutを追加
        parent.addView(frameLayout, index)
        
        // カメラプレビューをFrameLayoutに追加
        frameLayout.addView(viewBinding.viewFinder, 0)
        
        // 初期位置を右下に設定
        stampImageView.post {
            stampX = frameLayout.width - stampSize - 32f
            stampY = frameLayout.height - stampSize - 32f
            stampImageView.x = stampX
            stampImageView.y = stampY
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(baseContext, "エラーが発生しました", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        // 撮影した写真を読み込む
                        val uri = output.savedUri ?: return
                        val inputStream = contentResolver.openInputStream(uri) ?: return
                        val originalBitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream.close()

                        // スタンプ画像を読み込む
                        val stampDrawable = ContextCompat.getDrawable(baseContext, R.drawable.stamp) ?: return//この部分でstamp.pngを検索している名前を保存名になるように変化すればいいかも？
                        val stampBitmap = (stampDrawable as BitmapDrawable).bitmap

                        // 合成用のBitmapを作成
                        val resultBitmap = Bitmap.createBitmap(
                            originalBitmap.width,
                            originalBitmap.height,
                            Bitmap.Config.ARGB_8888
                        )

                        // 写真とスタンプを合成
                        val canvas = Canvas(resultBitmap)
                        canvas.drawBitmap(originalBitmap, 0f, 0f, null)

                        // スタンプのサイズを計算
                        val stampSize = originalBitmap.width / 5  // 画像の1/5のサイズ
                        val scaledStampBitmap = Bitmap.createScaledBitmap(
                            stampBitmap,
                            stampSize,
                            stampSize,
                            true
                        )

                        // プレビュー画面とのサイズ比率を計算
                        val previewWidth = viewBinding.viewFinder.width.toFloat()
                        val previewHeight = viewBinding.viewFinder.height.toFloat()
                        val scaleX = originalBitmap.width.toFloat() / previewWidth
                        val scaleY = originalBitmap.height.toFloat() / previewHeight

                        // スタンプの位置を写真のサイズに合わせて調整
                        val adjustedX = stampX * scaleX
                        val adjustedY = stampY * scaleY

                        // スタンプを描画
                        canvas.drawBitmap(scaledStampBitmap, adjustedX, adjustedY, null)

                        // 合成した画像を保存
                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                        }

                        // Bitmapをリサイクル
                        originalBitmap.recycle()
                        scaledStampBitmap.recycle()
                        resultBitmap.recycle()

                        val msg = "スタンプ付き写真を保存しました: ${uri}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.d(TAG, msg)
                    } catch (e: Exception) {
                        Log.e(TAG, "スタンプの合成に失敗しました: ${e.message}", e)
                        Toast.makeText(baseContext, "スタンプの合成に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Implements VideoCapture use case, including start and stop capturing.
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED)
                {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}