package com.example.ios27clock

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class ForegroundMaskEditorActivity : Activity() {

    companion object {
        const val SOURCE_FILE_NAME = "liquid_clock_wallpaper_source.img"
        const val MANUAL_FOREGROUND_FILE_NAME =
            "liquid_clock_wallpaper_manual_foreground.png"
    }

    private lateinit var drawingView: MaskDrawingView
    private lateinit var brushValue: TextView
    private var eraseMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_foreground_mask_editor)

        drawingView = findViewById(R.id.foreground_mask_drawing_view)
        brushValue = findViewById(R.id.foreground_brush_value)

        val source = File(filesDir, SOURCE_FILE_NAME)
        val existingMask = File(filesDir, MANUAL_FOREGROUND_FILE_NAME)

        if (!source.exists() || source.length() == 0L) {
            Toast.makeText(this, R.string.mask_editor_no_wallpaper, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            drawingView.loadImage(source, existingMask)
        } catch (exception: Exception) {
            Toast.makeText(
                this,
                getString(R.string.mask_editor_load_failed, exception.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        findViewById<Button>(R.id.mask_mode_button).setOnClickListener {
            eraseMode = !eraseMode
            drawingView.setEraseMode(eraseMode)
            it as Button
            it.text = getString(
                if (eraseMode) R.string.mask_mode_erase else R.string.mask_mode_paint
            )
        }

        findViewById<Button>(R.id.mask_undo_button).setOnClickListener {
            drawingView.undo()
        }

        findViewById<Button>(R.id.mask_clear_button).setOnClickListener {
            drawingView.clearSelection()
        }

        findViewById<SeekBar>(R.id.foreground_brush_seek).apply {
            max = 170
            progress = 54
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val brushDp = (progress + 10).coerceAtLeast(10)
                    drawingView.setBrushSizeDp(brushDp.toFloat())
                    brushValue.text = getString(R.string.mask_brush_size, brushDp)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        drawingView.setBrushSizeDp(64f)

        findViewById<Button>(R.id.mask_cancel_button).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.mask_save_button).setOnClickListener {
            saveSelection()
        }
    }

    private fun saveSelection() {
        val target = File(filesDir, MANUAL_FOREGROUND_FILE_NAME)
        val temporary = File(filesDir, "$MANUAL_FOREGROUND_FILE_NAME.tmp")

        try {
            drawingView.writeForegroundCutout(temporary)
            check(temporary.exists() && temporary.length() > 0L) {
                "The foreground selection file was not created."
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
        if (::drawingView.isInitialized) {
            drawingView.release()
        }
        super.onDestroy()
    }
}
