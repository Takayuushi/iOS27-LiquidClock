package com.example.ios27clock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast

class MainActivity : Activity() {

    private var waitingForOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.start_overlay_button).setOnClickListener {
            confirmOverlayStart()
        }

        findViewById<Button>(R.id.stop_overlay_button).setOnClickListener {
            stopService(Intent(this, LiquidLockService::class.java))
            Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()

        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false

            if (Settings.canDrawOverlays(this)) {
                startLiquidLockService()
            } else {
                Toast.makeText(
                    this,
                    R.string.overlay_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmOverlayStart() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_dialog_title)
            .setMessage(R.string.overlay_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                requestOverlayPermissionOrStart()
            }
            .show()
    }

    private fun requestOverlayPermissionOrStart() {
        if (Settings.canDrawOverlays(this)) {
            startLiquidLockService()
            return
        }

        waitingForOverlayPermission = true

        val permissionIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )

        startActivity(permissionIntent)
    }

    private fun startLiquidLockService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                R.string.overlay_permission_required,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val serviceIntent = Intent(
            this,
            LiquidLockService::class.java
        ).setAction(LiquidLockService.ACTION_SHOW)

        try {
            startForegroundService(serviceIntent)
            Toast.makeText(
                this,
                R.string.overlay_started,
                Toast.LENGTH_SHORT
            ).show()
        } catch (exception: SecurityException) {
            Toast.makeText(
                this,
                getString(R.string.service_start_failed, exception.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        } catch (exception: IllegalStateException) {
            Toast.makeText(
                this,
                getString(R.string.service_start_failed, exception.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
