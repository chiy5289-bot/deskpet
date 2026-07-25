package com.example.deskpet

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.deskpet.service.OverlayService
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnToggle = findViewById<MaterialButton>(R.id.btn_toggle)
        val btnPerms = findViewById<MaterialButton>(R.id.btn_permissions)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        btnToggle.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                requestOverlayPermission()
                return@setOnClickListener
            }

            if (isServiceRunning) {
                stopService(Intent(this, OverlayService::class.java))
                isServiceRunning = false
                btnToggle.text = "召唤桌宠"
                tvStatus.text = "桌宠已隐藏"
            } else {
                val intent = Intent(this, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                isServiceRunning = true
                btnToggle.text = "收回桌宠"
                tvStatus.text = "桌宠已在屏幕上"
            }
        }

        btnPerms.setOnClickListener {
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val btnPerms = findViewById<MaterialButton>(R.id.btn_permissions)
        if (Settings.canDrawOverlays(this)) {
            btnPerms.text = "✓ 悬浮窗权限已授权"
            btnPerms.isEnabled = false
        } else {
            btnPerms.text = "授权悬浮窗权限"
            btnPerms.isEnabled = true
            tvStatus.text = "需要悬浮窗权限"
        }
    }
}
