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

            /*
            // 画像分析の設定（現在は使用していない）
            val imageAnalyzer = ImageAnalysis.Builder().build()
                .also {
                    setAnalyzer(
                        cameraExecutor,
                        LuminosityAnalyzer { luma ->
                            Log.d(TAG, "平均輝度: $luma")
                        }
                    )
                }
            */

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

                        // スタンプ画像を読み込む
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

                        // 合成した画像を保存
                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                        }

                        // Bitmapをリサイクル（メモリリーク防止）
                        originalBitmap.recycle()
                        scaledStampBitmap.recycle()
                        resultBitmap.recycle()

                        // 成功メッセージを表示
                        val msg = "スタンプ付き写真を保存しました: ${uri}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.d(TAG, msg)
                    } catch (e: Exception) {
                        // エラー処理
                        Log.e(TAG, "スタンプの合成に失敗しました: ${e.message}", e)
                        Toast.makeText(baseContext, "スタンプの合成に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
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
        }
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
        // 必要な権限のリスト
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                // Android P以前の場合は外部ストレージの書き込み権限も必要
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}