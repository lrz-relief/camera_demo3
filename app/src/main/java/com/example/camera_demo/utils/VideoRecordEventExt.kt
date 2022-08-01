package com.example.camera_demo.utils

import androidx.camera.video.VideoRecordEvent

/**
 * 一个助手扩展函数，用于获取VideoRecordEvent的名称（字符串）。
 */
fun VideoRecordEvent.getNameString() : String {
    return when (this) {
        is VideoRecordEvent.Status -> "Status"
        is VideoRecordEvent.Start -> "Started"
        is VideoRecordEvent.Finalize-> "Finalized"
        is VideoRecordEvent.Pause -> "Paused"
        is VideoRecordEvent.Resume -> "Resumed"
        else -> throw IllegalArgumentException("Unknown VideoRecordEvent: $this")
    }
}