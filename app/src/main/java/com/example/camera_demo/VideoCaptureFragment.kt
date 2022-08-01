package com.example.camera_demo

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.Configuration
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenCreated
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.findNavController
import com.example.camera_demo.databinding.FragmentVideoCaptureBinding
import com.example.camera_demo.utils.getAspectRatio
import com.example.camera_demo.utils.getAspectRatioString
import com.example.camera_demo.utils.getNameString
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class VideoCaptureFragment : Fragment(){
    // UI with ViewBinding
    private var _captureViewBinding: FragmentVideoCaptureBinding? = null//与capture_fragment绑定
    private val captureViewBinding get() = _captureViewBinding!!
    private val captureLiveStatus = MutableLiveData<String>()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.nav_host_fragment_container)//与main_activity关联，尝试一下，这里可能需要修改
    }

    private val cameraCapabilities = mutableListOf<CameraCapability>()

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordingState: VideoRecordEvent//录制状态

    // Camera UI  states and inputs
    enum class UiState {
        IDLE,       // 未录制，所有UI控件都处于活动状态。
        RECORDING,  // 摄像机正在录制，仅显示暂停/恢复和停止按钮。
        FINALIZED,  // 录制刚刚完成，请禁用所有录制UI控件。
        RECOVERY    // 供将来使用。
    }
    private var cameraIndex = 0
    private var qualityIndex = DEFAULT_QUALITY_IDX //默认的视频质量选择
    private var audioEnabled = true//默认允许录制音频

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    private var enumerationDeferred: Deferred<Unit>? = null

    data class CameraCapability(val camSelector: CameraSelector, val qualities:List<Quality>)
    /**
     * 查询和缓存此平台的摄像机功能，仅运行一次。
     */
    init {
        enumerationDeferred = lifecycleScope.async {
            whenCreated {
                val provider = ProcessCameraProvider.getInstance(requireContext()).await()

                provider.unbindAll()
                for (camSelector in arrayOf(
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    CameraSelector.DEFAULT_FRONT_CAMERA
                )) {
                    try {
                        // 获取相机。cameraInfo的查询功能
                        // we are not binding anything here.
                        if (provider.hasCamera(camSelector)) {
                            val camera = provider.bindToLifecycle(requireActivity(), camSelector)
                            QualitySelector
                                .getSupportedQualities(camera.cameraInfo)
                                .filter { quality ->
                                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                                        .contains(quality)
                                }.also {
                                    cameraCapabilities.add(CameraCapability(camSelector, it))
                                }
                        }
                    } catch (exc: java.lang.Exception) {
                        Log.e(TAG1, "Camera Face $camSelector is not supported")
                    }
                }
            }
        }
    }

    // main cameraX capture functions
    /**
     *  在本示例中，始终绑定预览+视频捕获用例组合（视频捕获可以独立工作）。该函数应始终在主线程上执行。
     */
    private suspend fun bindCaptureUsecase() {
        //与实现预览效果有关
        val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        //下面第一个函数
        val cameraSelector = getCameraSelector(cameraIndex)

        // 创建用户所需的QualitySelector（视频分辨率）：我们知道这是受支持的，将创建有效的QualitySelector。
        val quality = cameraCapabilities[cameraIndex].qualities[qualityIndex]
        val qualitySelector = QualitySelector.from(quality)

        captureViewBinding.previewView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            val orientation = this@VideoCaptureFragment.resources.configuration.orientation
            dimensionRatio = quality.getAspectRatioString(quality,
                (orientation == Configuration.ORIENTATION_PORTRAIT))
        }

        val preview = Preview.Builder()
            .setTargetAspectRatio(quality.getAspectRatio(quality))
            .build().apply {
                setSurfaceProvider(captureViewBinding.previewView.surfaceProvider)
            }


        // 建立一个记录器，它可以：
        // -将视频/音频录制到MediaStore（仅在此显示）、文件、ParcelFileDescriptor
        // -用于创建录制（录制执行录制）
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                videoCapture,
                preview
            )
        } catch (exc: Exception) {
            // we are on main thread, let's reset the controls on the UI.
            Log.e(TAG1, "Use case binding failed", exc)
            resetUIandState("bindToLifecycle failed: $exc")
        }
        enableUI(true)
    }

    /**
     * 检索请求的摄像机类型（面向镜头类型）。在此示例中，只有两种类型：
     *   idx is even number:  CameraSelector.LENS_FACING_BACK（偶数）
     *          odd number:   CameraSelector.LENS_FACING_FRONT（奇数）
     */
    private fun getCameraSelector(idx: Int) : CameraSelector {
        if (cameraCapabilities.size == 0) {
            Log.i(TAG1, "Error: This device does not have any camera, bailing out")
            requireActivity().finish()
        }
        return (cameraCapabilities[idx % cameraCapabilities.size].camSelector)
    }


    /**
     * ResetUI (restart):
     *    如果绑定失败，让我们对其进行另一次更改以重试。在未来的情况下，我们可能会失败，并通知用户状态
     */
    private fun resetUIandState(reason: String) {
        enableUI(true)
        showUI(UiState.IDLE, reason)

        cameraIndex = 0
        qualityIndex = DEFAULT_QUALITY_IDX
        audioEnabled = false
        //captureViewBinding.audioSelection.isChecked = audioEnabled
        //initializeQualitySectionsUI()
    }

    /**
     * Enable/disable UI:
     *    当录制不在会话中时，用户可以选择捕获参数
     *   录制开始后，需要禁用可启用的UI以避免冲突。
     */
    private fun enableUI(enable: Boolean) {
        arrayOf(captureViewBinding.cameraButton,
            captureViewBinding.captureButton,
            captureViewBinding.stopButton).forEach {
            it.isEnabled = enable
        }
        // 如果没有要切换的设备，请禁用摄像机按钮
        if (cameraCapabilities.size <= 1) {
            captureViewBinding.cameraButton.isEnabled = false
        }

    }


    /**
     * 初始化录制UI：
     * 录制时：隐藏音频、质量选择、更改摄像机界面；启用停止按钮
     * 否则：显示除停止按钮之外的所有按钮
     */
    private fun showUI(state: UiState, status:String = "idle") {
        captureViewBinding.let {
            when(state) {
                UiState.IDLE -> {
                    it.captureButton.setImageResource(R.drawable.ic_start)
                    it.stopButton.visibility = View.INVISIBLE

                    it.cameraButton.visibility= View.VISIBLE

                }
                UiState.RECORDING -> {
                    it.cameraButton.visibility = View.INVISIBLE


                    it.captureButton.setImageResource(R.drawable.ic_pause)
                    it.captureButton.isEnabled = true
                    it.stopButton.visibility = View.VISIBLE
                    it.stopButton.isEnabled = true
                }
                UiState.FINALIZED -> {
                    it.captureButton.setImageResource(R.drawable.ic_start)
                    it.stopButton.visibility = View.INVISIBLE
                }
                else -> {
                    val errorMsg = "Error: showUI($state) is not supported"
                    Log.e(TAG1, errorMsg)
                    return
                }
            }
            it.captureStatus.text = status
        }
    }



    /**
     *启动视频录制
     * -配置记录器以捕获到MediaStoreOutput
     * -注册RecordEvent侦听器
     * -应用来自用户的音频请求
     * -开始录制！
     * 完成此功能后，用户可以启动/暂停/恢复/停止录制，应用程序将侦听当前录制状态的VideoRecordEvent。
     */
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        // create MediaStoreOutputOptions for our recorder: resulting our recording!
        val name = "CameraX-recording-" +
                SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            requireActivity().contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        // configure Recorder and Start recording to the mediaStoreOutput.
        currentRecording = videoCapture.output
            .prepareRecording(requireActivity(), mediaStoreOutput)
            .apply { if (audioEnabled) withAudioEnabled() }
            .start(mainThreadExecutor, captureListener)

        Log.i(TAG1, "Recording started")
    }

    /**
     * CaptureEvent listener.
     */
    private val captureListener = Consumer<VideoRecordEvent> { event ->
        //缓存录制状态
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        updateUI(event)

        if (event is VideoRecordEvent.Finalize) {
            // 显示捕获的视频
            lifecycleScope.launch {
                navController.navigate(
                    //  这段可能有问题，检查一下
                    /*VideoCaptureFragment.actionVideoCaptureFragmentToVideoViewerFragment(
                        event.outputResults.outputUri
                    )*/
                    VideoCaptureFragmentDirections.actionVideoCaptureFragmentToVideoViewerFragment(
                        event.outputResults.outputUri
                    )
                )
            }
        }
    }





    /**
     * 一次初始化CameraFragment（作为片段布局创建过程的一部分）。
     * 此函数执行以下操作：
     * -初始化但禁用除质量选择之外的所有UI控件。
     * -设置质量选择回收器视图。
     * -将用例绑定到生命周期摄像机，启用UI控件。
     */
    private fun initCameraFragment() {
        initializeUI()
        viewLifecycleOwner.lifecycleScope.launch {
            if (enumerationDeferred != null) {
                enumerationDeferred!!.await()
                enumerationDeferred = null
            }
            //initializeQualitySectionsUI()

            bindCaptureUsecase()
        }
    }



    /**
     * 初始化UI。预览和捕获操作在此功能中配置。
     * 请注意，预览和捕获都是通过UI或CameraX回调初始化的（除了第一次在onCreateView（）中输入此片段时）
     */
    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        captureViewBinding.cameraButton.apply {
            setOnClickListener {
                cameraIndex = (cameraIndex + 1) % cameraCapabilities.size
                // 摄像机设备更改立即生效：
                //   -重置质量选择
                //   -重新启动预览
                qualityIndex = DEFAULT_QUALITY_IDX
                //initializeQualitySectionsUI()
                enableUI(false)
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCaptureUsecase()
                }
            }
            isEnabled = false
        }

        // 默认情况下禁用audioEnabled。
        //captureViewBinding.audioSelection.isChecked = audioEnabled
        /*
        captureViewBinding.audioSelection.setOnClickListener {
            audioEnabled = captureViewBinding.audioSelection.isChecked
        }
         */

        // 对用户触摸捕捉按钮作出反应
        captureViewBinding.captureButton.apply {
            setOnClickListener {
                if (!this@VideoCaptureFragment::recordingState.isInitialized ||
                    recordingState is VideoRecordEvent.Finalize)
                {
                    enableUI(false)  // 我们的eventListener将打开录制UI。
                    startRecording()
                } else {
                    when (recordingState) {
                        is VideoRecordEvent.Start -> {
                            currentRecording?.pause()
                            captureViewBinding.stopButton.visibility = View.VISIBLE
                        }
                        is VideoRecordEvent.Pause -> currentRecording?.resume()
                        is VideoRecordEvent.Resume -> currentRecording?.pause()
                        else -> throw IllegalStateException("recordingState in unknown state")
                    }
                }
            }
            isEnabled = false
        }

        captureViewBinding.stopButton.apply {
            setOnClickListener {
                // 停止：在转到查看片段之前，单击后将其隐藏
                captureViewBinding.stopButton.visibility = View.INVISIBLE
                if (currentRecording == null || recordingState is VideoRecordEvent.Finalize) {
                    return@setOnClickListener
                }

                val recording = currentRecording
                if (recording != null) {
                    recording.stop()
                    currentRecording = null
                }
                captureViewBinding.captureButton.setImageResource(R.drawable.ic_start)
            }
            // 确保停止按钮已初始化、禁用和不可见
            visibility = View.INVISIBLE
            isEnabled = false
        }

        captureLiveStatus.observe(viewLifecycleOwner) {
            captureViewBinding.captureStatus.apply {
                post { text = it }
            }
        }
        captureLiveStatus.value = getString(R.string.Idle)

        captureViewBinding.buttonToCamera.setOnClickListener{
            val button_to_camera = it.findNavController()
            button_to_camera.navigate(R.id.action_videoCaptureFragment_to_cameraFragment)
        }
    }




    /**
     * 根据CameraX VideoRecordEvent type更新UI：
     * -用户开始捕获。
     * -此应用程序禁用所有用户界面选择。
     * -该应用程序支持捕获运行时UI（暂停/恢复/停止）。
     * -用户通过运行时界面控制录制，最后点击“停止”结束。
     * -此应用程序通知CameraX录制停止录制。stop（）（或recording.close（））。
     * -CameraX通知此应用程序录制确实已停止，并出现Finalize事件。
     * -该应用程序启动VideoViewer片段以查看捕获的结果。
     */
    private fun updateUI(event: VideoRecordEvent) {
        val state = if (event is VideoRecordEvent.Status) recordingState.getNameString()
        else event.getNameString()
        when (event) {
            is VideoRecordEvent.Status -> {
                // 占位符：在这个when（）块之后，我们用新状态更新UI，
                // 这里什么都不用做。
            }
            is VideoRecordEvent.Start -> {
                showUI(UiState.RECORDING, event.getNameString())
            }
            is VideoRecordEvent.Finalize-> {
                showUI(UiState.FINALIZED, event.getNameString())
            }
            is VideoRecordEvent.Pause -> {
                captureViewBinding.captureButton.setImageResource(R.drawable.ic_resume)
            }
            is VideoRecordEvent.Resume -> {
                captureViewBinding.captureButton.setImageResource(R.drawable.ic_pause)
            }
        }

        val stats = event.recordingStats
        val size = stats.numBytesRecorded / 1000
        val time = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(stats.recordedDurationNanos)
        var text = "${state}: recorded ${size}KB, in ${time}second"
        if(event is VideoRecordEvent.Finalize)
            text = "${text}\nFile saved to: ${event.outputResults.outputUri}"

        captureLiveStatus.value = text
        Log.i(TAG1, "recording event: $text")
    }




    // 系统功能实现
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _captureViewBinding = FragmentVideoCaptureBinding.inflate(inflater, container, false)
        return captureViewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraFragment()
    }
    override fun onDestroyView() {
        _captureViewBinding = null
        super.onDestroyView()
    }


    companion object {
        // 如果用户界面没有输入，则默认质量选择
        const val DEFAULT_QUALITY_IDX = 0

        val TAG1:String = VideoCaptureFragment::class.java.simpleName
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

}