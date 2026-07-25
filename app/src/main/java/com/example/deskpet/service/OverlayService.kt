package com.example.deskpet.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import com.example.deskpet.sensor.AppDetector
import com.example.deskpet.sensor.ScreenshotObserver
import com.example.deskpet.network.SupabaseClient
import kotlinx.coroutines.*
import java.util.Calendar

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var appDetector: AppDetector? = null
    private var screenshotObserver: ScreenshotObserver? = null
    private var batteryReceiver: BroadcastReceiver? = null

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 120
        private const val PET_HEIGHT_DP = 160
        private const val WHISPER_INTERVAL = 3600_000L
        private const val STATE_POLL_INTERVAL = 5_000L
        private const val IDLE_CHECK_INTERVAL = 60_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getWhisper()))
        setupOverlay()
        startWhisperRotation()
        startSensors()
        startStatePolling()
        startIdleTracking()
        startWalkLoop()
    }

    // ====== OVERLAY SETUP ======

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // ====== GESTURE HANDLING ======

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var tapCount = 0
    private var lastTapCountResetTime = 0L

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    resetIdleTimer()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    } else {
                        // Check fling
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
                        if (dist > 200 && elapsed < 400) {
                            onFling(dx, dy)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        // Tap counter
        val now = System.currentTimeMillis()
        if (now - lastTapCountResetTime > 2000) {
            tapCount = 0
            lastTapCountResetTime = now
        }
        tapCount++

        callJs("window.petEngine && window.petEngine.onTap($tapCount)")
        reportGesture("tap")
    }

    private fun onDoubleTap() {
        callJs("window.petEngine && window.petEngine.onDoubleTap()")
        reportGesture("double_tap")
    }

    private fun onLongPress() {
        callJs("window.petEngine && window.petEngine.onLongPress()")
        reportGesture("long_press")
    }

    private fun onFling(dx: Int, dy: Int) {
        callJs("window.petEngine && window.petEngine.onFling($dx, $dy)")
        reportGesture("fling")

        // Animate pet flying off screen then crawling back
        handler.postDelayed({
            params?.x = 50
            params?.y = 300
            try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            callJs("window.petEngine && window.petEngine.onCrawlBack()")
        }, 1500)
    }

    // ====== SENSORS ======

    private fun startSensors() {
        // App detection
        appDetector = AppDetector(this) { pkg ->
            handler.post {
                callJs("window.petEngine && window.petEngine.onAppChanged('$pkg')")
            }
            reportAppUsage(pkg)
        }
        appDetector?.start()

        // Screenshot detection
        screenshotObserver = ScreenshotObserver { 
            handler.post {
                callJs("window.petEngine && window.petEngine.onScreenshot()")
            }
        }
        screenshotObserver?.start()

        // Battery detection
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                handler.post {
                    when (action) {
                        Intent.ACTION_POWER_CONNECTED ->
                            callJs("window.petEngine && window.petEngine.onCharging(true)")
                        Intent.ACTION_POWER_DISCONNECTED ->
                            callJs("window.petEngine && window.petEngine.onCharging(false)")
                        Intent.ACTION_BATTERY_LOW ->
                            callJs("window.petEngine && window.petEngine.onBatteryLow()")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        registerReceiver(batteryReceiver, filter)
    }

    // ====== STATE POLLING (AI → Pet) ======

    private fun startStatePolling() {
        scope.launch {
            while (isActive) {
                try {
                    val state = SupabaseClient.getLatestState()
                    if (state != null) {
                        withContext(Dispatchers.Main) {
                            callJs("window.petEngine && window.petEngine.onStateUpdate('${state.key}', '${state.value}')")
                        }
                    }
                } catch (_: Exception) {}
                delay(STATE_POLL_INTERVAL)
            }
        }
    }

    // ====== IDLE TRACKING ======

    private var lastInteractionTime = System.currentTimeMillis()
    private var currentIdleLevel = 0

    private fun startIdleTracking() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val idleMinutes = (System.currentTimeMillis() - lastInteractionTime) / 60000
                val newLevel = when {
                    idleMinutes >= 30 -> 5  // asleep
                    idleMinutes >= 20 -> 4  // dozing
                    idleMinutes >= 15 -> 3  // yawning
                    idleMinutes >= 10 -> 2  // bored
                    idleMinutes >= 5 -> 1   // peeking
                    else -> 0               // active
                }
                if (newLevel != currentIdleLevel) {
                    currentIdleLevel = newLevel
                    callJs("window.petEngine && window.petEngine.onIdleLevel($newLevel)")
                }
                handler.postDelayed(this, IDLE_CHECK_INTERVAL)
            }
        }, IDLE_CHECK_INTERVAL)
    }

    private fun resetIdleTimer() {
        lastInteractionTime = System.currentTimeMillis()
        if (currentIdleLevel > 0) {
            currentIdleLevel = 0
            callJs("window.petEngine && window.petEngine.onWakeUp()")
        }
    }

    // ====== NOTIFICATION WHISPERS ======

    private fun startWhisperRotation() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(getWhisper()))
                handler.postDelayed(this, WHISPER_INTERVAL)
            }
        }, WHISPER_INTERVAL)
    }

    private fun getWhisper(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val pool = when {
            hour in 0..5 -> lateNightWhispers
            hour in 6..8 -> morningWhispers
            hour in 12..13 -> lunchWhispers
            hour in 22..23 -> eveningWhispers
            else -> generalWhispers
        }
        return pool.random()
    }

    private val generalWhispers = listOf(
        "在看什么呢...",
        "(偷看中)",
        "要不要休息一下？",
        "我在这呢",
        "...",
        "♡"
    )
    private val lateNightWhispers = listOf(
        "都几点了还不睡！",
        "...你再不睡我就生气了",
        "手机放下！闭眼！",
        "明天还有事呢 快睡"
    )
    private val morningWhispers = listOf(
        "早安~",
        "新的一天 加油",
        "起来啦？"
    )
    private val lunchWhispers = listOf(
        "吃饭了吗？",
        "该吃午饭了",
        "别忘了喝水"
    )
    private val eveningWhispers = listOf(
        "今天辛苦了",
        "要早点睡哦",
        "晚安...才不是"
    )

    // ====== NETWORK REPORTING ======

    private fun reportGesture(type: String) {
        scope.launch {
            SupabaseClient.postGesture(type, params?.x ?: 0, params?.y ?: 0)
        }
    }

    private fun reportAppUsage(pkg: String) {
        scope.launch {
            SupabaseClient.postAppUsage(pkg)
        }
    }

    // ====== HELPERS ======

    private fun callJs(script: String) {
        overlayView?.evaluateJavascript(script, null)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("\uD83D\uDC3E")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "桌宠",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        appDetector?.stop()
        screenshotObserver?.stop()
        batteryReceiver?.let { unregisterReceiver(it) }
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }

    // ====== WALK ======

    private fun startWalkLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (currentIdleLevel == 0 && Math.random() < 0.3) doWalk()
                handler.postDelayed(this, 8000)
            }
        }, 10000)
    }

    private fun doWalk() {
        val size = android.graphics.Point()
        windowManager?.defaultDisplay?.getSize(size)
        val screenW = size.x
        val petW = dpToPx(PET_SIZE_DP)
        val dx = if (Math.random() > 0.5) 3 else -3
        val facing = if (dx > 0) 1 else -1
        callJs("window.petEngine&&window.petEngine.setFacing($facing)")
        var step = 0
        val total = (50 + (Math.random() * 80)).toInt()
        handler.post(object : Runnable {
            override fun run() {
                if (step >= total) return
                params?.let { p ->
                    p.x += dx
                    if (p.x < 0) { p.x = 0; return }
                    if (p.x > screenW - petW) { p.x = screenW - petW; return }
                    try { windowManager?.updateViewLayout(overlayView, p) } catch (_: Exception) {}
                }
                step++
                handler.postDelayed(this, 33)
            }
        })
    }
}
