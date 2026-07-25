package com.example.deskpet.sensor

import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Watches common screenshot directories for new images.
 * Calls onScreenshot when a screenshot is detected.
 */
class ScreenshotObserver(
    private val onScreenshot: () -> Unit
) {
    private val observers = mutableListOf<FileObserver>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastTriggerTime = 0L

    companion object {
        private const val COOLDOWN_MS = 3000L
    }

    private val screenshotPaths = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            .resolve("Screenshots").absolutePath,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            .resolve("Screenshots").absolutePath,
        "/storage/emulated/0/Pictures/Screenshots",
        "/storage/emulated/0/DCIM/Screenshots"
    )

    fun start() {
        for (path in screenshotPaths) {
            val dir = File(path)
            if (!dir.exists()) continue

            val observer = object : FileObserver(dir, CREATE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && isImageFile(path)) {
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerTime > COOLDOWN_MS) {
                            lastTriggerTime = now
                            handler.post { onScreenshot() }
                        }
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") ||
                lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg")
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }
}
