package com.example.pddhelper

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button

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

        layout.addView(statusText)
        layout.addView(actionButton)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        if (isServiceEnabled()) {
            statusText.text = "✅ Сервис включён. Открой приложение с билетами ПДД."
            actionButton.text = "Обновить статус"
        } else {
            statusText.text = "❌ Сервис выключен. Нажми кнопку ниже."
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
}
