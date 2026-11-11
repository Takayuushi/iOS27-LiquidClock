package com.example.ios27clock

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import java.io.File

class ForegroundMaskEditorActivity : Activity() {

    companion object {
        const val SOURCE_FILE_NAME = "liquid_clock_wallpaper_source.img"
        const val MANUAL_FOREGROUND_FILE_NAME =
            "liquid_clock_wallpaper_manual_foreground.png"
    }

    private lateinit var drawingView: MaskDrawingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_foreground_mask_editor)

        drawingView = findViewById(R.id.foreground_mask_drawing_view)

        val source = File(filesDir, SOURCE_FILE_NAME)
        val existingSelection = File(filesDir, MANUAL_FOREGROUND_FILE_NAME)

        if (!source.exists() || source.length() == 0L) {
            Toast.makeText(this, R.string.mask_editor_no_wallpaper, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            drawingView.loadImage(source, existingSelection)
        } catch (exception: Exception) {
            Toast.makeText(
                this,
                getString(R.string.mask_editor_load_failed, exception.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        findViewById<Button>(R.id.mask_undo_button).setOnClickListener {
            drawingView.undo()
        }

        findViewById<Button>(R.id.mask_clear_button).setOnClickListener {
            drawingView.clearSelection()
        }

        showOneTimeGuide()

        findViewById<Button>(R.id.mask_cancel_button).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.mask_save_button).setOnClickListener {
            saveSelection()
        }
    }

    private fun showOneTimeGuide() {
        val preferences = getSharedPreferences("mask_editor_guide", MODE_PRIVATE)
        if (preferences.getBoolean("shown", false)) return

        AlertDialog.Builder(this)
            .setTitle(R.string.mask_editor_title)
            .setMessage(R.string.mask_editor_instructions)
            .setPositiveButton(R.string.got_it) { dialog, _ ->
                preferences.edit().putBoolean("shown", true).apply()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun saveSelection() {
        val target = File(filesDir, MANUAL_FOREGROUND_FILE_NAME)
        val temporary = File(filesDir, "$MANUAL_FOREGROUND_FILE_NAME.tmp")

        try {
            drawingView.writeSelectionMask(temporary)
            check(temporary.exists() && temporary.length() > 0L) {
                "The object selection file was not created."
            }

            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }

            sendBroadcast(
                android.content.Intent(LiquidWallpaperService.ACTION_WALLPAPER_SOURCE_CHANGED)
                    .setPackage(packageName)
            )

            setResult(RESULT_OK)
            Toast.makeText(this, R.string.mask_editor_saved, Toast.LENGTH_SHORT).show()
            finish()
        } catch (exception: Exception) {
            temporary.delete()
            Toast.makeText(
                this,
                getString(R.string.mask_editor_save_failed, exception.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        if (::drawingView.isInitialized) drawingView.release()
        super.onDestroy()
    }
}
