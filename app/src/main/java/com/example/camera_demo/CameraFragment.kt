package com.example.camera_demo

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.navigation.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.camera_demo.databinding.CameraUiContainerBinding
import com.example.camera_demo.databinding.FragmentCameraBinding
import com.example.camera_demo.utils.ANIMATION_FAST_MILLIS
import com.example.camera_demo.utils.ANIMATION_SLOW_MILLIS
import com.example.camera_demo.utils.simulateClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.window.WindowManager



//用于分析用例回调的助手类型别名
typealias LumaListener = (luma: Double) -> Unit

class CameraFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowManager: WindowManager

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }


    //使用该执行器执行阻塞摄像机操作
    private lateinit var cameraExecutor: ExecutorService


    //用于触发快门的音量降低按钮接收器
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            //KEY_EVENT_EXTRA对应MainActivity中的置顶声明变量
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
                }
            }
        }
    }


    //这段代码将屏幕方向更改与设备配置更改分开，为屏幕方向更改设置监听器
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                //TAG在本kt文件的companion object中定义
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }





    override fun onResume() {
        super.onResume()
        //确保所有权限仍然存在
        if (!PermissionFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.nav_host_fragment_container)
                .navigate(
                    CameraFragmentDirections.actionCameraToPermission()
                )
        }
    }


    override fun onDestroyView() {
        //将fragmentCameraBinding置空销毁
        _fragmentCameraBinding = null
        super.onDestroyView()

        //关闭后台执行器
        cameraExecutor.shutdown()

        //注销广播接收器
        broadcastManager.unregisterReceiver(volumeDownReceiver)

        //注销侦听器
        displayManager.unregisterDisplayListener(displayListener)
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }


    /*设置图片仓库的缩略图*/
    private fun setGalleryThumbnail(uri: Uri) {
        //在视图的线程中运行操作
        cameraUiContainerBinding?.photoViewButton?.let { photoViewButton ->
            photoViewButton.post {

                //删除缩略图填充
                photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

                //使用Glide将缩略图加载到圆形按钮中
                //即在拍摄照片之后，实现一个缩略图的效果
                Glide.with(photoViewButton)
                    .load(uri)
                    .apply(RequestOptions.circleCropTransform())
                    .into(photoViewButton)
            }
        }
    }


    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)



        //初始化后台执行器
        cameraExecutor = Executors.newSingleThreadExecutor()

        //启动广播接收器
        broadcastManager = LocalBroadcastManager.getInstance(view.context)


        //设置将要从我们的主要活动接收事件的意图过滤器
        //KEY_EVENT_ACTION对应MainActivity中定义的变量
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)


        //每次设备的方向改变时，更新用例的旋转
        displayManager.registerDisplayListener(displayListener, null)


        //初始化WindowManager以检索显示指标
        windowManager = WindowManager(view.context)


        //将输出路径赋予outputDirectory
        outputDirectory = MainActivity.getOutputDirectory(requireContext())


        //等待视图正确布局
        fragmentCameraBinding.viewFinder.post {

            //跟踪附着此视图的显示器
            displayId = fragmentCameraBinding.viewFinder.display.displayId

            //生成UI控件
            updateCameraUi()

            //设置摄像机及其用例
            setUpCamera()

            cameraUiContainerBinding?.buttonToVideo?.setOnClickListener {
                val button_to_video_controller = it.findNavController()
                button_to_video_controller.navigate(R.id.action_cameraFragment_to_videoCaptureFragment)
                onDestroy()
            }
        }
    }




    //setUpCamera及其相关内置函数

        /* 初始化CameraX，并准备绑定摄像机用例 */
        private fun setUpCamera() {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            cameraProviderFuture.addListener(Runnable {

                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                //根据可用的摄像机选择透镜面
                lensFacing = when {
                    hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                    hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                    else -> throw IllegalStateException("Back and front camera are unavailable")
                }

                //启用或禁用摄像机之间的切换
                updateCameraSwitchButton()

                //构建并绑定摄像机用例
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext()))
        }


        /* 如果设备有可用的后置摄像头，则返回true。否则为False */
        private fun hasBackCamera(): Boolean {
            return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
        }


        /* 如果设备有可用的前置摄像头，则返回true。否则为False */
        private fun hasFrontCamera(): Boolean {
            return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
        }


        /* 启用或禁用根据可用摄像机切换摄像机的按钮 */
        private fun updateCameraSwitchButton() {
            try {
                cameraUiContainerBinding?.cameraSwitchButton?.isEnabled =
                    hasBackCamera() && hasFrontCamera()
            } catch (exception: CameraInfoUnavailableException) {
                cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = false
            }
        }


        /* 声明并绑定预览、捕获和分析用例 */
        private fun bindCameraUseCases() {

            //获取用于设置摄像机全屏分辨率的屏幕指标
            //与分析图像有关
            val metrics = windowManager.getCurrentWindowMetrics().bounds
            Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

            val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
            Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

            val rotation = fragmentCameraBinding.viewFinder.display.rotation

            // CameraProvider
            val cameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

            // CameraSelector
            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            // Preview
            preview = Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation
                .setTargetRotation(rotation)
                .build()

            // ImageCapture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                //我们要求纵横比，但没有分辨率来匹配预览配置，但让CameraX优化任何最适合我们用例的特定分辨率
                .setTargetAspectRatio(screenAspectRatio)
                //设置初始目标旋转，如果在该用例的生命周期中旋转发生变化，我们将不得不再次调用此选项
                .setTargetRotation(rotation)
                .build()

            // ImageAnalysis
            imageAnalyzer = ImageAnalysis.Builder()
                //我们要求纵横比，但没有分辨率
                .setTargetAspectRatio(screenAspectRatio)
                // 设置初始目标旋转，如果在该用例的生命周期中旋转发生变化，我们将不得不再次调用此选项
                .setTargetRotation(rotation)
                .build()
                // 然后可以将分析器分配给实例
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        // 从我们的分析器返回的值被传递给附加的侦听器，我们在这里记录图像分析结果
                        //可以具体添加图像分析的选项
                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }

            // 必须在重新绑定用例之前解除绑定
            cameraProvider.unbindAll()

            try {
                // 可以在此处传递不同数量的用例camera提供对CameraControl和CameraRainfo的访问
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )

                // 附加取景器的曲面提供程序以预览用例
                preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
                observeCameraState(camera?.cameraInfo!!)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }


        private fun aspectRatio(width: Int, height: Int): Int {
            val previewRatio = max(width, height).toDouble() / min(width, height)
            //RATIO_4_3_VALUE与RATIO_16_9_VALUE定义在companion object中
            if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
                return AspectRatio.RATIO_4_3
            }
            return AspectRatio.RATIO_16_9
        }


        /**
         * 我们的自定义图像分析类。
         * 我们所需要做的就是用我们想要的操作覆盖函数“analyze”。在这里，我们通过查看YUV帧的Y平面来计算图像的平均亮度。
         */
        private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
            private val frameRateWindow = 8
            private val frameTimestamps = ArrayDeque<Long>(5)
            private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
            private var lastAnalyzedTimestamp = 0L
            var framesPerSecond: Double = -1.0
                private set


            /**
             * 用于添加侦听器，在计算每个luma时调用这些侦听器
             */
            fun onFrameAnalyzed(listener: LumaListener) = listeners.add(listener)


            /**
             * 用于从图像平面缓冲区提取字节数组的辅助扩展函数
             */
            private fun ByteBuffer.toByteArray(): ByteArray {
                rewind()    // 将缓冲区倒回零
                val data = ByteArray(remaining())
                get(data)   // 将缓冲区复制到字节数组中
                return data // 返回字节数组
            }


            /**
             *分析图像以产生结果。
             *调用方负责确保此分析方法能够快速执行，以防止图像采集管道中出现暂停。否则，将无法获取和分析新可用的图像。
             * 此方法返回后，传递给此方法的图像无效。调用方不应存储对此图像的外部引用，因为这些引用将变得无效。
             */
            override fun analyze(image: ImageProxy) {
                // 如果没有附加侦听器，则不需要执行分析
                if (listeners.isEmpty()) {
                    image.close()
                    return
                }

                // 跟踪分析的框架
                val currentTime = System.currentTimeMillis()
                frameTimestamps.push(currentTime)

                // 使用移动平均值计算FPS
                while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
                val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
                val timestampLast = frameTimestamps.peekLast() ?: currentTime
                framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                        frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

                // 分析可能需要任意长的时间，因为我们在不同的线程中运行，它不会暂停其他用例

                lastAnalyzedTimestamp = frameTimestamps.first

                // 因为ImageAnalysis中的格式是YUV，所以图像。平面[0]包含亮度平面
                val buffer = image.planes[0].buffer

                // 从回调对象提取图像数据
                val data = buffer.toByteArray()

                // 将数据转换为范围为0-255的像素值数组
                val pixels = data.map { it.toInt() and 0xFF }

                // 计算图像的平均亮度
                val luma = pixels.average()

                // 使用新值调用所有侦听器
                listeners.forEach { it(luma) }

                image.close()
            }
        }


        private fun observeCameraState(cameraInfo: CameraInfo) {
            cameraInfo.cameraState.observe(viewLifecycleOwner) { cameraState ->
                run {

                }

                cameraState.error?.let { error ->
                    when (error.code) {
                        // 打开错误
                        CameraState.ERROR_STREAM_CONFIG -> {
                            // 确保正确设置用例
                            Toast.makeText(
                                context,
                                "Stream config error",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        // 尝试打开时的错误
                        CameraState.ERROR_CAMERA_IN_USE -> {
                            // 关闭摄像机或要求用户关闭正在使用摄像机的另一个摄像机应用程序
                            Toast.makeText(
                                context,
                                "Camera in use",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
                            // 关闭应用程序中另一个打开的摄像头，或要求用户关闭使用该摄像头的另一个摄像头应用程序
                            Toast.makeText(
                                context,
                                "Max cameras in use",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
                            Toast.makeText(
                                context,
                                "Other recoverable error",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        // 正在关闭时的错误
                        CameraState.ERROR_CAMERA_DISABLED -> {
                            // 要求用户启用设备的摄像头
                            Toast.makeText(
                                context,
                                "Camera disabled",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        CameraState.ERROR_CAMERA_FATAL_ERROR -> {
                            // 要求用户重新启动设备以恢复摄像机功能
                            Toast.makeText(
                                context,
                                "Fatal error",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        // 关闭后的错误
                        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
                            // 要求用户禁用“请勿打扰”模式，然后重新打开摄像机
                            Toast.makeText(
                                context,
                                "Do not disturb mode enabled",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
















    /** 用于重新绘制相机UI控件的方法，每次配置更改时调用。 */
    private fun updateCameraUi() {

        // 删除以前的UI（如果有）
        cameraUiContainerBinding?.root?.let {
            fragmentCameraBinding.root.removeView(it)
        }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            fragmentCameraBinding.root,
            true
        )



        //在背景中，加载为图库缩略图拍摄的最新照片（如果有）
        //EXTENSION_WHITELIST定义在PhotoWarehouseFragment
        lifecycleScope.launch(Dispatchers.IO) {
            outputDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.toUpperCase(Locale.ROOT))
            }?.maxOrNull()?.let {
                setGalleryThumbnail(Uri.fromFile(it))
            }
        }

        //
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {

            //获得可修改图像捕获用例的稳定参考
            imageCapture?.let { imageCapture ->

                // 创建输出文件以保存图像
                // createFile函数与FILENAME, PHOTO_EXTENSION定义在companion object中
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

                // 设置图像捕获元数据
                val metadata = ImageCapture.Metadata().apply {

                    // 使用前置摄像头时镜像
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }

                // 创建包含文件和元数据的输出选项对象
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                    .setMetadata(metadata)
                    .build()

                // 设置拍照后触发的图像捕获侦听器
                imageCapture.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                            Log.d(TAG, "Photo capture succeeded: $savedUri")

                            // 我们只能使用API级别23+API更改前景可绘制
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                // 使用拍摄的最新图片更新库缩略图
                                setGalleryThumbnail(savedUri)
                            }


                            // 如果所选文件夹是外部媒体目录，则不需要这样做，否则其他应用程序将无法访问我们的图像，除非我们使用[MediaScannerConnection]扫描它们
                            val mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(savedUri.toFile().absolutePath),
                                arrayOf(mimeType)
                            ) { _, uri ->
                                Log.d(TAG, "Image capture scanned into media store: $uri")
                            }
                        }
                    })

                // 我们只能使用API级别23+API更改前景可绘制
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // 显示flash动画以指示已捕获照片
                    //ANIMATION_FAST_MILLIS与ANIMATION_SLOW_MILLIS是引用ViewExtensions中的变量
                    fragmentCameraBinding.root.postDelayed({
                        fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                        fragmentCameraBinding.root.postDelayed(
                            { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }
            }
        }

        // 用于切换摄像机的按钮的设置
        cameraUiContainerBinding?.cameraSwitchButton?.let {

            // 禁用该按钮，直到摄像机设置完毕
            it.isEnabled = false

            // 用于切换摄像机的按钮的侦听器。仅当按钮启用时调用
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // 重新绑定用例以更新选定的摄像机
                bindCameraUseCases()
            }
        }

        // 用于查看最新照片的按钮的侦听器
        cameraUiContainerBinding?.photoViewButton?.setOnClickListener {
            // 仅在库中有照片时导航
            if (true == outputDirectory.listFiles()?.isNotEmpty()) {
                Navigation.findNavController(
                    requireActivity(), R.id.nav_host_fragment_container
                ).navigate(CameraFragmentDirections
                    .actionCameraToPhotoWarehouse(outputDirectory.absolutePath))
            }
        }
    }










    /**
     * 充气摄像机控件，并在配置更改时手动更新用户界面，以避免从视图层次结构中删除和重新添加取景器；这在支持旋转的设备上提供了无缝的旋转过渡。
     * 注意：Android 8支持该标志，但对于运行Android 9或更低版本的设备，屏幕上仍有一个小闪光灯。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // 使用更新的显示指标重新绑定摄像机
        bindCameraUseCases()

        // 启用或禁用摄像机之间的切换
        updateCameraSwitchButton()
    }


    companion object {
        private const val TAG = "CameraXBasic"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"


        /** 用于创建时间戳文件的助手函数 */
        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(baseFolder, SimpleDateFormat(format, Locale.US)
                .format(System.currentTimeMillis()) + extension)
    }
}