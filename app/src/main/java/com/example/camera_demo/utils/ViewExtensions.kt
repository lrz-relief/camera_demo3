package com.example.camera_demo.utils

import android.os.Build
import android.view.DisplayCutout
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog



/** 将活动置于浸入式模式所需的所有标志的组合 */
const val FLAGS_FULLSCREEN =
    View.SYSTEM_UI_FLAG_LOW_PROFILE or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

//定义用于UI动画的毫秒数如下
const val ANIMATION_FAST_MILLIS = 50L
const val ANIMATION_SLOW_MILLIS = 100L

//模拟按钮点击，包括按下按钮触发适当动画时的小延迟。
fun ImageButton.simulateClick(delay: Long = ANIMATION_FAST_MILLIS) {
    performClick()
    isPressed = true
    invalidate()
    postDelayed({
        invalidate()
        isPressed = false
    }, delay)
}


/** 用设备切口（即凹口）提供的插图填充该视图 */
@RequiresApi(Build.VERSION_CODES.P)
fun View.padWithDisplayCutout() {

    /** 从剪贴画的安全插入中应用填充的辅助方法 */
    fun doPadding(cutout: DisplayCutout) = setPadding(
        cutout.safeInsetLeft,
        cutout.safeInsetTop,
        cutout.safeInsetRight,
        cutout.safeInsetBottom)

    // 使用指定为“安全区域”的显示窗口应用填充
    rootWindowInsets?.displayCutout?.let { doPadding(it) }

    // 为自查看以来的窗口插入设置侦听器。rootWindowInsets可能尚未就绪
    setOnApplyWindowInsetsListener { _, insets ->
        insets.displayCutout?.let { doPadding(it) }
        insets
    }
}



/** 与[AlertDialog.show]相同，但在对话框窗口中设置浸入式模式 */
fun AlertDialog.showImmersive() {
    // 将对话框设置为不可聚焦
    window?.setFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

    // 确保对话框的窗口处于全屏状态
    window?.decorView?.systemUiVisibility = FLAGS_FULLSCREEN

    // 在浸入式模式下显示对话框
    show()

    // 再次将对话框设置为可聚焦
    window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}