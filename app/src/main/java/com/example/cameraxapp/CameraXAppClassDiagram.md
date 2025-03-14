# CameraX App Class Diagram

## Class Diagram (クラス図)

```mermaid
classDiagram
    class MainActivity {
        -ActivityMainBinding viewBinding
        -ImageView stampImageView
        -Float stampX
        -Float stampY
        -Float initialX
        -Float initialY
        -Float initialTouchX
        -Float initialTouchY
        -ImageCapture? imageCapture
        -VideoCapture<Recorder>? videoCapture
        -Recording? recording
        -ExecutorService cameraExecutor
        +onCreate(Bundle?) void
        -setupStampOverlay() void
        -startCamera() void
        -takePhoto() void
        -captureVideo() void
        -allPermissionsGranted() Boolean
        +onDestroy() void
        +onRequestPermissionsResult(Int, Array<String>, IntArray) void
    }

    class LuminosityAnalyzer {
        -LumaListener listener
        +analyze(ImageProxy) void
        -ByteBuffer.toByteArray() ByteArray
    }

    class AppCompatActivity {
        <<Android Framework>>
    }

    class ImageAnalysis.Analyzer {
        <<Interface>>
        +analyze(ImageProxy) void
    }

    class LumaListener {
        <<Function Type>>
        (Double) -> Unit
    }

    class ImageCapture {
        <<CameraX>>
        +takePicture(OutputFileOptions, Executor, OnImageSavedCallback) void
        +Builder() Builder
    }

    class VideoCapture~T~ {
        <<CameraX>>
        +output: OutputOptions
        +static withOutput(Recorder) VideoCapture<Recorder>
    }

    class Recorder {
        <<CameraX>>
        +Builder() Builder
    }

    class Recording {
        <<CameraX>>
        +stop() void
    }

    class Preview {
        <<CameraX>>
        +setSurfaceProvider(SurfaceProvider) void
        +Builder() Builder
    }

    class ProcessCameraProvider {
        <<CameraX>>
        +bindToLifecycle(LifecycleOwner, CameraSelector, UseCase...) Camera
        +unbindAll() void
        +static getInstance(Context) ListenableFuture<ProcessCameraProvider>
    }

    class CameraSelector {
        <<CameraX>>
        +DEFAULT_BACK_CAMERA: CameraSelector
    }

    class ImageCapture.OnImageSavedCallback {
        <<Interface>>
        +onError(ImageCaptureException) void
        +onImageSaved(OutputFileResults) void
    }

    class Companion {
        -String TAG
        -String FILENAME_FORMAT
        -Int REQUEST_CODE_PERMISSIONS
        -Array<String> REQUIRED_PERMISSIONS
    }

    class ActivityMainBinding {
        <<View Binding>>
        +PreviewView viewFinder
        +Button imageCaptureButton
        +Button videoCaptureButton
    }

    class ImageView {
        <<Android View>>
        +setImageResource(Int) void
        +setOnTouchListener(OnTouchListener) void
    }

    class FrameLayout {
        <<Android View>>
        +addView(View) void
    }

    class Bitmap {
        <<Android Graphics>>
        +static createBitmap(Int, Int, Config) Bitmap
        +static createScaledBitmap(Bitmap, Int, Int, Boolean) Bitmap
        +recycle() void
        +compress(CompressFormat, Int, OutputStream) Boolean
    }

    class Canvas {
        <<Android Graphics>>
        +drawBitmap(Bitmap, Float, Float, Paint?) void
    }

    class BitmapDrawable {
        <<Android Graphics>>
        +bitmap: Bitmap
    }

    class View.OnTouchListener {
        <<Interface>>
        +onTouch(View, MotionEvent) Boolean
    }

    class ImageProcessing {
        <<Functionality>>
        -loadOriginalImage(Uri)
        -loadStampImage(R.drawable)
        -createResultBitmap()
        -drawStampOnImage()
        -saveProcessedImage(Uri)
    }

    class StampManagement {
        <<Functionality>>
        -setupStampView()
        -handleStampDrag()
        -positionStamp()
    }

    MainActivity --|> AppCompatActivity : extends
    MainActivity *-- LuminosityAnalyzer : inner class
    MainActivity *-- Companion : inner object
    MainActivity o-- ActivityMainBinding : has
    MainActivity o-- ImageCapture : uses
    MainActivity o-- VideoCapture : uses
    MainActivity o-- Recording : uses
    MainActivity o-- ImageView : uses for stamp
    MainActivity ..> ProcessCameraProvider : uses
    MainActivity ..> Preview : creates
    MainActivity ..> CameraSelector : uses
    MainActivity ..> Recorder : creates
    MainActivity ..> FrameLayout : creates in setupStampOverlay
    MainActivity ..> Bitmap : uses in takePhoto
    MainActivity ..> Canvas : uses in takePhoto
    MainActivity ..> BitmapDrawable : uses in takePhoto
    MainActivity ..> View.OnTouchListener : implements for stamp
    MainActivity ..> ImageProcessing : implements functionality
    MainActivity ..> StampManagement : implements functionality
    LuminosityAnalyzer ..|> ImageAnalysis.Analyzer : implements
    LuminosityAnalyzer --> LumaListener : uses
    MainActivity ..> ImageCapture.OnImageSavedCallback : creates anonymous implementation
    VideoCapture --> Recorder : parameterized with
    ImageProcessing ..> Bitmap : uses
    ImageProcessing ..> Canvas : uses
    StampManagement ..> ImageView : manages
```

## Class Diagram Explanation (クラス図の説明)


### 日本語

このクラス図は、移動可能なスタンプオーバーレイを使用して写真やビデオを撮影できるCameraX Androidアプリケーションの構造を表しています。主要なコンポーネントは以下の通りです：

1. **MainActivity**: AppCompatActivityを継承し、カメラ操作、スタンプの位置決め、画像処理を扱うメインアクティビティ。
2. **LuminosityAnalyzer**: 画像の輝度を分析するためにImageAnalysis.Analyzerを実装する内部クラス。
3. **CameraXコンポーネント**: ImageCapture、VideoCapture、Preview、ProcessCameraProviderなど、カメラ機能を処理するクラス。
4. **画像処理**: 撮影した写真にスタンプを追加する機能で、takePhotoメソッドに実装されています。
5. **スタンプ管理**: カメラプレビュー上でのスタンプの位置決めとドラッグを処理する機能。

この図は、クラス間の継承、コンポジション、使用関係などの関係を示しています。

## Key Features (主な機能)

### 日本語
- スタンプオーバーレイ付きの写真撮影
- ビデオ録画
- ドラッグ可能なスタンプ位置決め
- 写真とスタンプを合成する画像処理
- 権限処理

## Data Flow (データフロー)

### 日本語

アプリケーションは以下の主要なデータフローに従います：

1. **カメラ初期化フロー**:
   - `onCreate()` → 権限チェック → `startCamera()` → CameraXコンポーネントの初期化 → ライフサイクルへのバインド

2. **スタンプオーバーレイフロー**:
   - `onCreate()` → `setupStampOverlay()` → ImageViewの作成 → タッチリスナーの設定 → スタンプの位置決め

3. **写真撮影フロー**:
   - ユーザーが撮影ボタンをクリック → `takePhoto()` → ImageCaptureが写真を撮影 → MediaStoreに保存 → 
   - 元の画像を読み込む → スタンプ画像を読み込む → キャンバスを作成 → 位置にスタンプを描画 → 合成画像を保存

4. **ビデオ撮影フロー**:
   - ユーザーがビデオボタンをクリック → `captureVideo()` → 録画開始/停止 → MediaStoreに保存

5. **スタンプドラッグフロー**:
   - ユーザーがスタンプをタッチ → OnTouchListener → ACTION_DOWNが初期位置を記録 → 
   - ACTION_MOVEが位置を更新 → 位置がstampX/stampY変数に保存
``` 