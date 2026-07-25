package com.example.deskpet.sensor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Timer
import java.util.TimerTask

/**
 * Detects foreground app changes using UsageStatsManager.
 * Requires PACKAGE_USAGE_STATS permission (user grants in Settings).
 */
class AppDetector(
    private val context: Context,
    private val onAppChanged: (String) -> Unit
) {
    private var timer: Timer? = null
    private var lastApp: String = ""
    private var appSwitchTimestamps = mutableListOf<Long>()

    companion object {
        private const val POLL_INTERVAL = 3000L
        private const val FAST_SWITCH_WINDOW = 60_000L
        private const val FAST_SWITCH_THRESHOLD = 3
    }

    fun start() {
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val current = getForegroundApp()
                if (current.isNotEmpty() && current != lastApp) {
                    lastApp = current
                    trackFastSwitch()
                    onAppChanged(current)
                }
            }
        }, 0, POLL_INTERVAL)
    }

    private fun getForegroundApp(): String {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5000, now)
            val event = UsageEvents.Event()
            var foreground = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foreground = event.packageName
                }
            }
            foreground
        } catch (_: Exception) {
            ""
        }
    }

    private fun trackFastSwitch() {
        val now = System.currentTimeMillis()
        appSwitchTimestamps.add(now)
        appSwitchTimestamps.removeAll { now - it > FAST_SWITCH_WINDOW }
        // Fast switching detected — could trigger special animation
    }

    fun isFastSwitching(): Boolean {
        return appSwitchTimestamps.size >= FAST_SWITCH_THRESHOLD
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }
}
