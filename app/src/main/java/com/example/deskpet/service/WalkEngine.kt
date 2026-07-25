package com.example.deskpet.service

import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import kotlin.random.Random

class WalkEngine(
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val petView: android.view.View,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val petSize: Int,
    private val onDirectionChanged: (Int) -> Unit //1=left, 1=right
) {
    private val handler = Handler(Looper.getMainLooper())
    private var isWalking = false
    private var dx = 0
    private var dy = 0
    private var stepsRemaining = 0
    private val STEP_INTERVAL = 35L // ms per frame
    private val SPEED = 2// pixels per step

    private val walkRunnable = object : Runnable {
        override fun run() {
            if (!isWalking || stepsRemaining <= 0) {
                isWalking = false
                return
            }
            // Move
            params.x += dx * SPEED
            params.y += dy * SPEED
            stepsRemaining--

            // Bounce off edges
            if (params.x < 0) { params.x = 0; dx = 1; onDirectionChanged(1) }
            if (params.x > screenWidth - petSize) { params.x = screenWidth - petSize; dx = -1; onDirectionChanged(-1) }
            if (params.y < 0) { params.y = 0; dy = 1 }
            if (params.y > screenHeight - petSize) { params.y = screenHeight - petSize; dy = -1 }

            try { windowManager.updateViewLayout(petView, params) } catch (_: Exception) {}
            handler.postDelayed(this, STEP_INTERVAL)
        }
    }

    fun startWalk() {
        if (isWalking) return
        isWalking = true
        // Random direction
        dx = if (Random.nextBoolean()) 1 else -1
        dy = when (Random.nextInt(3)) { 0 -> -1; 1 -> 1; else -> 0 }
        stepsRemaining = Random.nextInt(60, 150) // walk for 2-5 seconds
        onDirectionChanged(dx)
        handler.post(walkRunnable)
    }

    fun stop() {
        isWalking = false
        handler.removeCallbacks(walkRunnable)
    }

    fun fling(velocityX: Float, velocityY: Float) {
        // Quick fling movement
        isWalking = true
        dx = if (velocityX > 0) 1 else -1
        dy = if (velocityY > 0) 1 else -1
        stepsRemaining = 30
        onDirectionChanged(dx)
        handler.post(walkRunnable)
    }

    fun isCurrentlyWalking() = isWalking
}
