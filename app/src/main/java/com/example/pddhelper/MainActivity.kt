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
    private lateinit var logButton: Button
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusText = TextView(this).apply { textSize = 16f }

        actionButton = Button(this).apply {
            text = "Включить PDD Helper"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        logButton = Button(this).apply {
            text = "Показать лог"
            setOnClickListener { showLog() }
        }

        logView = TextView(this).apply {
            textSize = 12f
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
        layout.addView(scroll)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        statusText.text = if (isServiceEnabled())
            "✅ Сервис включён. Открой приложение с билетами ПДД."
        else
            "❌ Сервис выключен. Нажми кнопку ниже."
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
        // Показываем последние 8000 символов
        val text = file.readText()
        logView.text = if (text.length > 8000) text.takeLast(8000) else text
    }
}
