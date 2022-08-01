package com.example.camera_demo

import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.camera_demo.databinding.FragmentPhotoWarehouseBinding
import com.example.camera_demo.utils.padWithDisplayCutout
import com.example.camera_demo.utils.showImmersive
import java.io.File
import java.util.*

val EXTENSION_WHITELIST = arrayOf("JPG")

/**
 * 此fragment用于向用户展示拍摄的照片库
 */
class PhotoWarehouseFragment internal constructor() : Fragment() {

    /** Android ViewBinding */
    private var _fragmentGalleryBinding: FragmentPhotoWarehouseBinding? = null

    private val fragmentGalleryBinding get() = _fragmentGalleryBinding!!

    /** AndroidX navigation arguments */
    private val args: PhotoWarehouseFragmentArgs by navArgs()

    private lateinit var mediaList: MutableList<File>

    /** 适配器类，用于将包含一张照片或视频的片段显示为页面 */
    inner class MediaPagerAdapter(fm: FragmentManager) : FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getCount(): Int = mediaList.size
        override fun getItem(position: Int): Fragment = PhotoFragment.create(mediaList[position])
        override fun getItemPosition(obj: Any): Int = POSITION_NONE
    }




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 将其标记为保留片段，这样生命周期就不会在配置更改时重新启动
        retainInstance = true

        // 从导航参数中获取媒体的根目录
        val rootDirectory = File(args.rootDirectory)

        // 遍历根目录中的所有文件
        // 我们颠倒了列表的顺序，先展示最后的照片
        mediaList = rootDirectory.listFiles { file ->
            EXTENSION_WHITELIST.contains(file.extension.toUpperCase(Locale.ROOT))
        }?.sortedDescending()?.toMutableList() ?: mutableListOf()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentGalleryBinding = FragmentPhotoWarehouseBinding.inflate(inflater, container, false)
        return fragmentGalleryBinding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 正在检查媒体文件列表
        if (mediaList.isEmpty()) {
            fragmentGalleryBinding.deleteButton.isEnabled = false
            fragmentGalleryBinding.shareButton.isEnabled = false
        }

        // 填充ViewPager并实现两个媒体项的缓存
        fragmentGalleryBinding.photoViewPager.apply {
            offscreenPageLimit = 2
            adapter = MediaPagerAdapter(childFragmentManager)
        }

        // 确保切口“安全区域”避开屏幕缺口（如有）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 使用扩展方法，使用显示剪切边界填充包含UI的“内部”视图
            fragmentGalleryBinding.cutoutSafeArea.padWithDisplayCutout()
        }

        // 后退按钮按下
        fragmentGalleryBinding.backButton.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.nav_host_fragment_container).navigateUp()
        }

        // 分享按钮按下
        fragmentGalleryBinding.shareButton.setOnClickListener {

            mediaList.getOrNull(fragmentGalleryBinding.photoViewPager.currentItem)?.let { mediaFile ->

                // 创建共享意图
                val intent = Intent().apply {
                    // 从文件扩展名推断媒体类型
                    val mediaType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(mediaFile.extension)
                    // 从我们的FileProvider实现中获取URI
                    val uri = FileProvider.getUriForFile(
                        view.context, BuildConfig.APPLICATION_ID + ".provider", mediaFile)
                    // 设置适当的intent extra、type、action和标志
                    putExtra(Intent.EXTRA_STREAM, uri)
                    type = mediaType
                    action = Intent.ACTION_SEND
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                // 启动意图，让用户选择与哪个应用程序共享
                startActivity(Intent.createChooser(intent, getString(R.string.share_hint)))
            }
        }

        // 删除按钮按下
        fragmentGalleryBinding.deleteButton.setOnClickListener {

            mediaList.getOrNull(fragmentGalleryBinding.photoViewPager.currentItem)?.let { mediaFile ->

                AlertDialog.Builder(view.context, android.R.style.Theme_Material_Dialog)
                    .setTitle(getString(R.string.delete_title))
                    .setMessage(getString(R.string.delete_dialog))
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton(android.R.string.yes) { _, _ ->

                        // 删除当前照片
                        mediaFile.delete()

                        // 发送相关广播，通知其他应用删除
                        MediaScannerConnection.scanFile(
                            view.context, arrayOf(mediaFile.absolutePath), null, null)

                        // 通知我们的view pager
                        mediaList.removeAt(fragmentGalleryBinding.photoViewPager.currentItem)
                        fragmentGalleryBinding.photoViewPager.adapter?.notifyDataSetChanged()

                        // 如果所有照片都已删除，请返回相机
                        if (mediaList.isEmpty()) {
                            Navigation.findNavController(requireActivity(), R.id.nav_host_fragment_container).navigateUp()
                        }

                    }

                    .setNegativeButton(android.R.string.no, null)
                    .create().showImmersive()
            }
        }
    }

    override fun onDestroyView() {
        _fragmentGalleryBinding = null
        super.onDestroyView()
    }

}