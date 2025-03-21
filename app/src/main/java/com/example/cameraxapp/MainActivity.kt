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
import android.media.ExifInterface
import java.io.FileOutputStream
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import java.util.TimeZone

/**
 * 輝度リスナーの型エイリアス定義
 * 画像の輝度（明るさ）の値を受け取るコールバック関数の型
 */
typealias LumaListener = (luma: Double) -> Unit

/**
 * メインアクティビティクラス
 * カメラプレビュー、写真撮影、ビデオ録画、スタンプ機能を提供する
 */
class MainActivity : AppCompatActivity() {
    // ビューバインディング - レイアウトの各要素にアクセスするために使用
    private lateinit var viewBinding: ActivityMainBinding
    // スタンプ画像を表示するためのImageView
    private lateinit var stampImageView: ImageView
    
    // スタンプの位置を保持する変数
    private var stampX: Float = 0f // スタンプのX座標
    private var stampY: Float = 0f // スタンプのY座標
    private var initialX: Float = 0f // ドラッグ開始時のスタンプX座標
    private var initialY: Float = 0f // ドラッグ開始時のスタンプY座標
    private var initialTouchX: Float = 0f // ドラッグ開始時のタッチX座標
    private var initialTouchY: Float = 0f // ドラッグ開始時のタッチY座標

    // 写真撮影用のCameraXコンポーネント
    private var imageCapture: ImageCapture? = null

    // ビデオ録画用のCameraXコンポーネント
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    // カメラ操作を実行するためのExecutor
    private lateinit var cameraExecutor: ExecutorService

    // 位置情報関連
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    
    // 位置情報のリスナー
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // 位置情報が更新されたら保存
            currentLocation = location
            Log.d(TAG, "位置情報が更新されました: ${location.latitude}, ${location.longitude}")
        }
        
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        
        override fun onProviderEnabled(provider: String) {}
        
        override fun onProviderDisabled(provider: String) {
            // 位置情報が無効になったら通知
            Toast.makeText(baseContext, "位置情報が無効です。設定から位置情報を有効にしてください。", 
                Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 画像の輝度を分析するための内部クラス
     * CameraXのImageAnalysis.Analyzerインターフェースを実装
     */
    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        /**
         * ByteBufferをByteArrayに変換する拡張関数
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // バッファをゼロにリワインド
            val data = ByteArray(remaining())
            get(data)   // バッファをバイト配列にコピー
            return data // バイト配列を返す
        }

        /**
         * 画像を分析するメソッド
         * 画像の輝度（明るさ）を計算し、リスナーに通知する
         */
        override fun analyze(image: ImageProxy) {
            // 画像の最初のプレーンからバッファを取得
            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            // 各ピクセルの値を取得し、平均輝度を計算
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            // 計算した輝度をリスナーに通知
            listener(luma)

            // 画像リソースを解放
            image.close()
        }
    }

    /**
     * カメラを初期化し、プレビューを開始するメソッド
     */
    private fun startCamera() {
        // カメラプロバイダのインスタンスを取得
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        // リスナーを追加して、カメラプロバイダが利用可能になったら実行
        cameraProviderFuture.addListener({
            // カメラのライフサイクルをライフサイクルオーナーにバインドするために使用
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // プレビューの設定
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }
            
            // レコーダーの設定（ビデオ録画用）
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // 写真撮影用の設定
            imageCapture = ImageCapture.Builder().build()


            // デフォルトで背面カメラを選択
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // 再バインド前にユースケースをアンバインド
                cameraProvider.unbindAll()

                // ユースケースをカメラにバインド
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture)

            } catch(exc: Exception) {
                // エラーログを出力
                Log.e(TAG, "ユースケースのバインドに失敗しました", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * アクティビティの作成時に呼ばれるメソッド
     * UIの初期化、カメラの設定、ボタンリスナーの設定を行う
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ビューバインディングの初期化
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // スタンプ画像オーバーレイを追加
        setupStampOverlay()

        // カメラ権限のリクエスト
        if (allPermissionsGranted()) {
            startCamera() // 権限がある場合はカメラを開始
        } else {
            // 権限がない場合はリクエスト
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // 写真撮影とビデオ録画ボタンのリスナーを設定
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        // カメラ操作用のExecutorを初期化
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 位置情報マネージャーの初期化
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    /**
     * スタンプオーバーレイを設定するメソッド
     * ドラッグ可能なスタンプをカメラプレビュー上に配置する
     */
    private fun setupStampOverlay() {
        // スタンプ用のImageViewを作成
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

    /**
     * 写真を撮影するメソッド
     * スタンプを合成した写真を保存する
     */
    private fun takePhoto() {
        // ImageCaptureが初期化されていない場合は早期リターン
        val imageCapture = imageCapture ?: return

        // タイムスタンプ付きの名前とMediaStoreエントリを作成
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // ファイルとメタデータを含む出力オプションを作成
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // 画像キャプチャリスナーを設定
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                // 写真撮影に失敗した場合
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "写真の撮影に失敗しました: ${exc.message}", exc)
                    Toast.makeText(baseContext, "エラーが発生しました", Toast.LENGTH_SHORT).show()
                }

                // 写真が保存された場合
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        // 撮影した写真を読み込む
                        val uri = output.savedUri ?: return
                        val inputStream = contentResolver.openInputStream(uri) ?: return
                        val originalBitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream.close()

                        // スタンプ画像を読み込む現在はstamp.pngのファイルのみを検索
                        val stampDrawable = ContextCompat.getDrawable(baseContext, R.drawable.stamp) ?: return
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

                        // スタンプのサイズを計算（写真の1/5のサイズ）
                        val stampSize = originalBitmap.width / 5
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

                        // EXIF情報を設定
                        val exifInterface = ExifInterface(getFilePathFromUri(uri) ?: return)
                        
                        // 基本的なEXIF情報を設定
                        exifInterface.setAttribute(ExifInterface.TAG_DATETIME, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(System.currentTimeMillis()))
                        exifInterface.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                        exifInterface.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                        
                        // カスタムEXIF情報（スタンプの位置情報）を設定
                        exifInterface.setAttribute(ExifInterface.TAG_USER_COMMENT, "StampPosition: X=${adjustedX}, Y=${adjustedY}")
                        
                        // アプリケーション名を設定
                        exifInterface.setAttribute(ExifInterface.TAG_SOFTWARE, "CameraX Stamp App")
                        
                        // 位置情報をEXIFに設定
                        currentLocation?.let { location ->
                            // 緯度を設定
                            val latitudeRef = if (location.latitude >= 0) "N" else "S"
                            val latitudeValue = convertToExifLatLong(Math.abs(location.latitude))
                            exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, latitudeRef)
                            exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE, latitudeValue)
                            
                            // 経度を設定
                            val longitudeRef = if (location.longitude >= 0) "E" else "W"
                            val longitudeValue = convertToExifLatLong(Math.abs(location.longitude))
                            exifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, longitudeRef)
                            exifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, longitudeValue)
                            
                            // 高度を設定（メートル単位）
                            if (location.hasAltitude()) {
                                val altitude = location.altitude
                                val altitudeRef = if (altitude >= 0) "0" else "1" // 0=海抜、1=海抜以下
                                exifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, altitudeRef)
                                exifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${Math.abs(altitude)}")
                            }
                            
                            // 撮影時間を設定（UTC時間）
                            val utcTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { 
                                timeZone = TimeZone.getTimeZone("UTC") 
                            }.format(location.time)
                            exifInterface.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, utcTime.substring(0, 10).replace(":", "/"))
                            exifInterface.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, utcTime.substring(11))
                            
                            Log.d(TAG, "位置情報をEXIFに設定しました: ${location.latitude}, ${location.longitude}")
                        }
                        
                        // EXIF情報を保存
                        exifInterface.saveAttributes()
                        
                        // 合成した画像を保存
                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                        }
                        
                        // 画像保存後にEXIF情報を再設定（画像の保存によってEXIF情報が消えることがあるため）
                        val updatedExif = ExifInterface(getFilePathFromUri(uri) ?: return)
                        updatedExif.setAttribute(ExifInterface.TAG_DATETIME, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(System.currentTimeMillis()))
                        updatedExif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                        updatedExif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                        updatedExif.setAttribute(ExifInterface.TAG_USER_COMMENT, "StampPosition: X=${adjustedX}, Y=${adjustedY}")
                        updatedExif.setAttribute(ExifInterface.TAG_SOFTWARE, "CameraX Stamp App")
                        
                        // 位置情報を再設定
                        currentLocation?.let { location ->
                            val latitudeRef = if (location.latitude >= 0) "N" else "S"
                            val latitudeValue = convertToExifLatLong(Math.abs(location.latitude))
                            updatedExif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, latitudeRef)
                            updatedExif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, latitudeValue)
                            
                            val longitudeRef = if (location.longitude >= 0) "E" else "W"
                            val longitudeValue = convertToExifLatLong(Math.abs(location.longitude))
                            updatedExif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, longitudeRef)
                            updatedExif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, longitudeValue)
                            
                            if (location.hasAltitude()) {
                                val altitude = location.altitude
                                val altitudeRef = if (altitude >= 0) "0" else "1"
                                updatedExif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, altitudeRef)
                                updatedExif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${Math.abs(altitude)}")
                            }
                            
                            val utcTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { 
                                timeZone = TimeZone.getTimeZone("UTC") 
                            }.format(location.time)
                            updatedExif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, utcTime.substring(0, 10).replace(":", "/"))
                            updatedExif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, utcTime.substring(11))
                        }
                        
                        updatedExif.saveAttributes()

                        // Bitmapをリサイクル（メモリリーク防止）
                        originalBitmap.recycle()
                        scaledStampBitmap.recycle()
                        resultBitmap.recycle()

                        // 成功メッセージを表示（位置情報の有無を含める）
                        val locationInfo = if (currentLocation != null) "（位置情報あり）" else "（位置情報なし）"
                        val msg = "EXIF情報付きスタンプ写真を保存しました${locationInfo}: ${uri}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.d(TAG, msg)

                        // 写真保存後に位置情報を表示
                        showLocationInfo()
                    } catch (e: Exception) {
                        // エラー処理
                        Log.e(TAG, "スタンプの合成またはEXIF情報の設定に失敗しました: ${e.message}", e)
                        Toast.makeText(baseContext, "スタンプの合成またはEXIF情報の設定に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    /**
     * Uriからファイルパスを取得するメソッド
     * EXIF情報を設定するために必要
     */
    private fun getFilePathFromUri(uri: Uri): String? {
        try {
            // ContentResolverを使用してファイルパスを取得
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (columnIndex >= 0) {
                        return it.getString(columnIndex)
                    }
                }
            }
            
            // 通常の方法で取得できない場合は、一時ファイルを作成
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(cacheDir, "temp_image.jpg")
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            return tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "ファイルパスの取得に失敗しました: ${e.message}", e)
            return null
        }
    }

    /**
     * ビデオを録画するメソッド
     * 録画の開始と停止を処理する
     */
    private fun captureVideo() {
        // VideoCaptureが初期化されていない場合は早期リターン
        val videoCapture = this.videoCapture ?: return

        // 録画中はボタンを無効化
        viewBinding.videoCaptureButton.isEnabled = false

        // 現在の録画セッションを取得
        val curRecording = recording
        if (curRecording != null) {
            // 録画中の場合は停止
            curRecording.stop()
            recording = null
            return
        }

        // 新しい録画セッションを作成して開始
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        // MediaStoreの出力オプションを設定
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        
        // 録画を開始
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                // 音声録音の権限がある場合は音声を有効化
                if (PermissionChecker.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED)
                {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                // 録画イベントに応じた処理
                when(recordEvent) {
                    // 録画開始時
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    // 録画終了時
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            // 成功メッセージを表示
                            val msg = "ビデオの録画に成功しました: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        } else {
                            // エラー処理
                            recording?.close()
                            recording = null
                            Log.e(TAG, "ビデオの録画がエラーで終了しました: " +
                                    "${recordEvent.error}")
                        }
                        // ボタンテキストを「録画開始」に戻す
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    /**
     * 必要な権限がすべて許可されているかチェックするメソッド
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * アクティビティが破棄されるときに呼ばれるメソッド
     * リソースの解放を行う
     */
    override fun onDestroy() {
        super.onDestroy()
        // カメラExecutorをシャットダウン
        cameraExecutor.shutdown()
    }

    /**
     * 権限リクエストの結果を処理するメソッド
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                // 権限が許可された場合はカメラを開始
                startCamera()
            } else {
                // 権限が拒否された場合はメッセージを表示してアプリを終了
                Toast.makeText(this,
                    "ユーザーによって権限が許可されませんでした。",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && 
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 位置情報の権限が許可された場合は位置情報の取得を開始
                startLocationUpdates()
            } else {
                // 位置情報の権限が拒否された場合はメッセージを表示
                Toast.makeText(this,
                    "位置情報の権限が許可されていないため、位置情報は記録されません。",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 写真保存後に位置情報を表示する機能を追加
     */
    private fun showLocationInfo() {
        　currentLocation?.let { location ->
            val latitude = location.latitude
            val longitude = location.longitude
            
            // 緯度・経度を表示
            val locationInfo = "位置情報: 緯度 ${latitude}, 経度 ${longitude}"
            
            // アラートダイアログで表示
            AlertDialog.Builder(this)
                .setTitle("撮影位置情報")
                .setMessage(locationInfo)
                .setPositiveButton("地図で見る") { _, _ ->
                    // 地図アプリで位置を表示
                    val uri = Uri.parse("geo:${latitude},${longitude}?q=${latitude},${longitude}")
                    val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                    if (mapIntent.resolveActivity(packageManager) != null) {
                        startActivity(mapIntent)
                    } else {
                        Toast.makeText(this, "地図アプリが見つかりません", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("閉じる", null)
                .show()
        } ?: Toast.makeText(this, "位置情報がありません", Toast.LENGTH_SHORT).show()
    }

    /**
     * アクティビティが表示される時に呼ばれるメソッド
     * 位置情報の取得を開始する
     */
    override fun onResume() {
        super.onResume()
        // 位置情報の取得を開始
        startLocationUpdates()
    }
    
    /**
     * アクティビティが非表示になる時に呼ばれるメソッド
     * 位置情報の取得を停止する
     */
    override fun onPause() {
        super.onPause()
        // 位置情報の取得を停止
        stopLocationUpdates()
    }
    
    /**
     * 位置情報の更新を開始するメソッド
     */
    private fun startLocationUpdates() {
        try {
            // 位置情報の権限が許可されているか確認
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION) == 
                PackageManager.PERMISSION_GRANTED) {
                
                // GPSプロバイダから位置情報を取得（精度高）
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 
                        10000,  // 10秒ごとに更新
                        10f,    // 10メートル以上移動したら更新
                        locationListener
                    )
                    
                    // 最後に取得した位置情報を取得
                    val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    if (lastKnownLocation != null) {
                        currentLocation = lastKnownLocation
                        Log.d(TAG, "最後に取得した位置情報: ${lastKnownLocation.latitude}, ${lastKnownLocation.longitude}")
                    }
                }
                
                // ネットワークプロバイダからも取得（バックアップ）
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 
                        10000,  // 10秒ごとに更新
                        10f,    // 10メートル以上移動したら更新
                        locationListener
                    )
                    
                    // GPSで取得できなかった場合はネットワークから取得
                    if (currentLocation == null) {
                        val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (networkLocation != null) {
                            currentLocation = networkLocation
                            Log.d(TAG, "ネットワークから取得した位置情報: ${networkLocation.latitude}, ${networkLocation.longitude}")
                        }
                    }
                }
            } else {
                // 位置情報の権限がない場合は要求
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "位置情報の取得に失敗しました: ${e.message}", e)
        }
    }
    
    /**
     * 位置情報の更新を停止するメソッド
     */
    private fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.e(TAG, "位置情報の更新停止に失敗しました: ${e.message}", e)
        }
    }

    /**
     * 緯度・経度をEXIF形式の文字列に変換するメソッド
     * EXIF形式: 度,分,秒 (例: "35/1,40/1,30/1")
     */
    private fun convertToExifLatLong(value: Double): String {
        val degrees = value.toInt()
        val minutes = ((value - degrees) * 60.0).toInt()
        val seconds = ((value - degrees) * 60.0 - minutes) * 60.0
        
        return "$degrees/1,$minutes/1,${seconds.toInt()}/1"
    }

    /**
     * 定数とアプリ全体で使用される値を定義するコンパニオンオブジェクト
     */
    companion object {
        // ログ用のタグ
        private const val TAG = "CameraXApp"
        // ファイル名のフォーマット
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        // 権限リクエストコード
        private const val REQUEST_CODE_PERMISSIONS = 10
        // 位置情報の権限リクエストコード
        private const val LOCATION_PERMISSION_REQUEST = 20
        // 必要な権限のリスト - 位置情報の権限を追加
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION  // 詳細な位置情報の権限
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}