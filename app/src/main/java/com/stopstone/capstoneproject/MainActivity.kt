package com.stopstone.capstoneproject

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.stopstone.capstoneproject.ml.SignModel
import com.google.common.util.concurrent.ListenableFuture
import com.stopstone.Constants
import com.stopstone.capstoneproject.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.support.model.Model
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

typealias CameraBitmapOutputListener = (bitmap: Bitmap) -> Unit

class MainActivity : AppCompatActivity() {

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupML()
        setupCameraThread()
        setupCameraControllers()

        requestCameraPermission()
    }

    private fun requestCameraPermission() {
        if (allPermissionsGranted) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, Constants.REQUIRED_PERMISSIONS, Constants.REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == Constants.REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "권한이 거부되었습니다. 카메라를 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    private fun setupCameraControllers() { // 아이콘 변경
        fun setLensButtonIcon() {
            binding.btnCameraLensFace.setImageDrawable(
                AppCompatResources.getDrawable(
                    applicationContext,
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                        R.drawable.ic_baseline_camera_rear_24
                    else
                        R.drawable.ic_baseline_camera_front_24
                )
            )
        }

        setLensButtonIcon()
        binding.btnCameraLensFace.setOnClickListener { // 버튼 클릭
            lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            setLensButtonIcon() // 아이콘 변경
            setupCameraUseCases() // 카메라 변경
        }
        try {
            binding.btnCameraLensFace.isEnabled = hasBackCamera && hasFrontCamera
        } catch (exception: CameraInfoUnavailableException) {
            binding.btnCameraLensFace.isEnabled = false
        }
    }

    private fun setupCameraUseCases() { // 카메라 변경
        val cameraSelector: CameraSelector =
            CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val metrics: DisplayMetrics =
            DisplayMetrics().also { binding.viewFinder.display.getRealMetrics(it)}
        val rotation: Int = binding.viewFinder.display.rotation
        val screenAspectRatio: Int = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()
            .also { it.setAnalyzer(
                cameraExecutor, BitmapOutputAnalysis(applicationContext){
                        bitmap-> setupMLOutput(bitmap)
                })
            }

        cameraProvider?.unbindAll()
        try {
            camera = cameraProvider?.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.createSurfaceProvider())
        } catch (exc: Exception) {
            Log.e(Constants.TAG, "카메라 작동 실패", exc)
        }
    }

    private lateinit var sign: SignModel
    private fun setupML() {
        val options: Model.Options = Model.Options.Builder().setNumThreads(4).build()
        sign = SignModel.newInstance(applicationContext, options)
    }

    private fun setupMLOutput(bitmap: Bitmap) {
        val tensorImage: TensorImage = TensorImage.fromBitmap(bitmap)
        val result: SignModel.Outputs = sign.process(tensorImage)
        val output: List<Category> =
            result.probabilityAsCategoryList.apply{
                sortByDescending{ res -> res.score }
            }
        lifecycleScope.launch(Dispatchers.Main){
            output.firstOrNull()?.let{
                    category ->
                if(category.label == "None")
                    binding.tvOutput.text = ""
                else
                    binding.tvOutput.text = category.label

            }
        }
    }

    private fun setupCameraThread() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }


    private fun startCamera() {
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                lensFacing = when {
                    hasFrontCamera -> CameraSelector.LENS_FACING_FRONT
                    hasBackCamera -> CameraSelector.LENS_FACING_BACK
                    else -> throw IllegalStateException("장치를 찾을 수 없습니다.")
                }
                setupCameraControllers()
                setupCameraUseCases()
            } catch (e: Exception) {
                Log.e(Constants.TAG, "카메라 초기화 실패", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val allPermissionsGranted: Boolean
        get() {
            return Constants.REQUIRED_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(
                    baseContext, it
                ) == PackageManager.PERMISSION_GRANTED
            }
        }

    private val hasBackCamera: Boolean
        get() {
            return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
        }
    private val hasFrontCamera: Boolean
        get() {
            return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
        }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupCameraControllers()
    }

}


private fun aspectRatio(width: Int, height: Int): Int{
    val previewRatio: Double = max(width, height).toDouble() / min(width, height)
    if(abs(previewRatio - Constants.RATIO_4_3_VALUE)  <= abs(previewRatio - Constants.RATIO_16_9_VALUE))
    {
        return AspectRatio.RATIO_4_3
    }
    return AspectRatio.RATIO_16_9
}

private class BitmapOutputAnalysis(
    context: Context,
    private val listener: CameraBitmapOutputListener
):
    ImageAnalysis.Analyzer{
    private val yuvToRgbConverter = YuvToRgbConverter(context)
    private lateinit var bitmapBuffer: Bitmap
    private lateinit var rotationMatrix: Matrix

    @SuppressLint("UnsafeExperimentalUsageError", "UnsafeOptInUsageError")
    private fun ImageProxy.toBitmap(): Bitmap?{
        val image: Image = this.image?:return null
        if(! :: bitmapBuffer.isInitialized){
            rotationMatrix = Matrix()
            rotationMatrix.postRotate(this.imageInfo.rotationDegrees.toFloat())
            bitmapBuffer = Bitmap.createBitmap(this.width, this.height, Bitmap.Config.ARGB_8888)
        }

        yuvToRgbConverter.yuvToRgb(image, bitmapBuffer)

        return Bitmap.createBitmap(
            bitmapBuffer,
            0,
            0,
            bitmapBuffer.width,
            bitmapBuffer.height,
            rotationMatrix,
            false
        )
    }

    override fun analyze(imageProxy: ImageProxy) {
        imageProxy.toBitmap()?.let{
            listener(it)
        }
        imageProxy.close()
    }
}