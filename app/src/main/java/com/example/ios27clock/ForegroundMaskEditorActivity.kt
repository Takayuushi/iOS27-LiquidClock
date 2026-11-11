package com.example.ios27clock

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Color
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
        drawingView.setMagneticLassoEnabled(true)
        } catch (exception: Exception) {
            Toast.makeText(
                this,
                getString(R.string.mask_editor_load_failed, exception.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        val addModeButton = findViewById<Button>(R.id.mask_add_mode_button)
        val eraseModeButton = findViewById<Button>(R.id.mask_erase_mode_button)
        val magneticButton = findViewById<Button>(R.id.mask_magnetic_button)

        addModeButton.setOnClickListener {
            drawingView.setEraseMode(false)
            updateToolButtons(addModeButton, eraseModeButton, magneticButton)
        }
        eraseModeButton.setOnClickListener {
            drawingView.setEraseMode(true)
            updateToolButtons(addModeButton, eraseModeButton, magneticButton)
        }
        magneticButton.setOnClickListener {
            drawingView.setMagneticLassoEnabled(!drawingView.isMagneticLassoEnabled)
            updateToolButtons(addModeButton, eraseModeButton, magneticButton)
        }
        updateToolButtons(addModeButton, eraseModeButton, magneticButton)

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


    private fun updateToolButtons(
        addButton: Button,
        eraseButton: Button,
        magneticButton: Button
    ) {
        val addSelected = !drawingView.isEraseMode
        val eraseSelected = drawingView.isEraseMode
        addButton.text = getString(
            if (addSelected) R.string.mask_mode_add_selected else R.string.mask_mode_add
        )
        eraseButton.text = getString(
            if (eraseSelected) R.string.mask_mode_erase_selected else R.string.mask_mode_erase
        )
        magneticButton.text = getString(
            if (drawingView.isMagneticLassoEnabled) {
                R.string.mask_magnetic_on
            } else {
                R.string.mask_magnetic_off
            }
        )
        addButton.backgroundTintList = ColorStateList.valueOf(
            if (addSelected) Color.rgb(77, 151, 210) else Color.rgb(59, 71, 86)
        )
        eraseButton.backgroundTintList = ColorStateList.valueOf(
            if (eraseSelected) Color.rgb(190, 91, 72) else Color.rgb(59, 71, 86)
        )
        magneticButton.backgroundTintList = ColorStateList.valueOf(
            if (drawingView.isMagneticLassoEnabled) {
                Color.rgb(77, 151, 210)
            } else {
                Color.rgb(59, 71, 86)
            }
        )
        addButton.setTextColor(Color.WHITE)
        eraseButton.setTextColor(Color.WHITE)
        magneticButton.setTextColor(Color.WHITE)
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
