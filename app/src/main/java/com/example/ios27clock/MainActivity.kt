package com.example.ios27clock

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PICK_WALLPAPER = 4101
        private const val SOURCE_FILE_NAME = "liquid_clock_wallpaper_source.img"
        private const val MANUAL_FOREGROUND_FILE_NAME = "liquid_clock_wallpaper_manual_foreground.png"
        private const val AUTO_FOREGROUND_FILE_NAME = "liquid_clock_wallpaper_foreground.png"
    }

    private lateinit var selectionStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectionStatus = findViewById(R.id.wallpaper_selection_status)

        findViewById<Button>(R.id.choose_wallpaper_button).setOnClickListener {
            openWallpaperPicker()
        }

        findViewById<Button>(R.id.edit_foreground_button).setOnClickListener {
            openForegroundMaskEditor()
        }

        findViewById<Button>(R.id.apply_live_wallpaper_button).setOnClickListener {
            openLiveWallpaperPreview()
        }

        updateSelectionStatus()
    }

    override fun onResume() {
        super.onResume()
        updateSelectionStatus()
    }

    private fun openWallpaperPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivityForResult(intent, REQUEST_PICK_WALLPAPER)
        } catch (exception: Exception) {
            Toast.makeText(
                this,
                getString(R.string.wallpaper_picker_unavailable),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("The document picker result is handled for the native Activity workflow.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_PICK_WALLPAPER || resultCode != RESULT_OK) {
            return
        }

        val uri: Uri = data?.data ?: return

        try {
            copySelectedImage(uri)
            File(filesDir, MANUAL_FOREGROUND_FILE_NAME).delete()
            File(filesDir, AUTO_FOREGROUND_FILE_NAME).delete()
            selectionStatus.setText(R.string.wallpaper_preparing_depth)

            val sourceFile = File(filesDir, SOURCE_FILE_NAME)
            WallpaperDepthProcessor.process(this, sourceFile) { depthReady ->
                sendBroadcast(
                    Intent(LiquidWallpaperService.ACTION_WALLPAPER_SOURCE_CHANGED)
                        .setPackage(packageName)
                )
                updateSelectionStatus()

                Toast.makeText(
                    this,
                    if (depthReady) {
                        R.string.wallpaper_depth_ready
                    } else {
                        R.string.wallpaper_depth_fallback
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (exception: Exception) {
            Toast.makeText(
                this,
                getString(
                    R.string.wallpaper_import_failed,
                    exception.message ?: "Unknown error"
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun copySelectedImage(uri: Uri) {
        val destination = File(filesDir, SOURCE_FILE_NAME)
        val temporary = File(filesDir, "$SOURCE_FILE_NAME.tmp")

        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected image could not be opened." }
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }

        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }

        check(destination.exists() && destination.length() > 0L) {
            "The selected image was empty."
        }
    }

    private fun openForegroundMaskEditor() {
        val source = File(filesDir, SOURCE_FILE_NAME)
        if (!source.exists() || source.length() == 0L) {
            Toast.makeText(this, R.string.wallpaper_not_selected, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, ForegroundMaskEditorActivity::class.java))
    }

    private fun openLiveWallpaperPreview() {
        val component = ComponentName(
            this,
            LiquidWallpaperService::class.java
        )

        val previewIntent = Intent(
            WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
        ).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            component
        )

        try {
            startActivity(previewIntent)
        } catch (exception: Exception) {
            try {
                startActivity(
                    Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                )
            } catch (fallbackException: Exception) {
                Toast.makeText(
                    this,
                    R.string.live_wallpaper_preview_unavailable,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateSelectionStatus() {
        val wallpaperFile = File(filesDir, SOURCE_FILE_NAME)
        selectionStatus.text = if (wallpaperFile.exists() && wallpaperFile.length() > 0L) {
            getString(R.string.wallpaper_selected)
        } else {
            getString(R.string.wallpaper_not_selected)
        }
    }
}
