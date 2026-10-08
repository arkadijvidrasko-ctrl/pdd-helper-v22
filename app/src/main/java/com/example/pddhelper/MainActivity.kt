package com.example.pddhelper

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var logView: TextView
    private lateinit var clearButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusText = TextView(this).apply { textSize = 16f }

        actionButton = Button(this).apply {
            setOnClickListener {
                if (isServiceEnabled()) updateStatus()
                else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val logButton = Button(this).apply {
            text = "Показать лог"
            setOnClickListener { showLog() }
        }

        clearButton = Button(this).apply {
            text = "Очистить лог"
            setOnClickListener { clearLog() }
        }

        logView = TextView(this).apply {
            textSize = 11f
            setPadding(16, 16, 16, 16)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        scroll.addView(logView)

        layout.addView(statusText)
        layout.addView(actionButton)
        layout.addView(logButton)
        layout.addView(clearButton)
        layout.addView(scroll)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        if (isServiceEnabled()) {
            statusText.text = "✅ Сервис включён"
            actionButton.text = "Обновить статус"
        } else {
            statusText.text = "❌ Сервис выключен"
            actionButton.text = "Включить PDD Helper"
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${PddAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun showLog() {
        val file = File(getExternalFilesDir(null), PddAccessibilityService.LOG_FILENAME)
        if (!file.exists() || file.length() == 0L) {
            logView.text = "Лог пуст. Открой приложение с билетами ПДД, потом вернись сюда."
            return
        }
        val text = file.readText()
        logView.text = if (text.length > 20000) text.takeLast(20000) else text
    }

    private fun clearLog() {
        val file = File(getExternalFilesDir(null), PddAccessibilityService.LOG_FILENAME)
        try { file.writeText("") } catch (_: Exception) {}
        logView.text = "Лог очищен"
    }
}
