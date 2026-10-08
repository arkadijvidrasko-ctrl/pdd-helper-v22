package com.example.pddhelper

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PddAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var dotView: View? = null
    private var questions: List<PddQuestion> = emptyList()

    private data class TextNode(val norm: String, val rect: Rect)

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadDatabase()
        log("Сервис запущен, загружено вопросов: ${questions.size}")
    }

    private fun loadDatabase() {
        try {
            assets.open("questions.json").use { input ->
                val reader = InputStreamReader(input)
                val type = object : TypeToken<List<PddQuestion>>() {}.type
                questions = Gson().fromJson(reader, type)
            }
        } catch (e: Exception) {
            log("ОШИБКА загрузки базы: ${e.message}")
        }
    }

    private fun normalizeText(text: String): String {
        var s = text.lowercase()
            .replace("ё", "е")
            .replace(Regex("[^a-zа-я0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        s = s.replace(Regex("^\\d+\\s+"), "")
        return s.trim()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        traverseNode(root, allNodes)

        val textNodes = mutableListOf<TextNode>()
        for (n in allNodes) {
            if (n.childCount > 0) continue
            val raw = n.text?.toString() ?: continue
            val norm = normalizeText(raw)
            if (norm.length < 2) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) continue
            textNodes.add(TextNode(norm, r))
        }
        val screenTexts = textNodes.map { it.norm }

        if (screenTexts.isEmpty()) {
            hideDot()
            return
        }

        val candidates = mutableListOf<PddQuestion>()
        for (q in questions) {
            val nq = normalizeText(q.question)
            if (nq.length < 10) continue
            if (!questionMatches(nq, screenTexts)) continue
            val allAnswersOnScreen = q.answers.all { a ->
                val na = normalizeText(a)
                na.isNotEmpty() && screenTexts.any { it == na }
            }
            if (allAnswersOnScreen) candidates.add(q)
        }

        if (candidates.isEmpty()) {
            hideDot()
            return
        }

        // Логируем, что нашли
        val sb = StringBuilder()
        sb.append("=== ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} ===\n")
        sb.append("Экран (первые 8 строк):\n")
        screenTexts.take(8).forEach { sb.append("  · $it\n") }
        sb.append("Кандидатов: ${candidates.size}\n")
        candidates.forEach { sb.append("  ▶ '${it.question}' -> '${it.correct_answer}'\n") }

        val distinctCorrect = candidates.map { normalizeText(it.correct_answer) }.distinct()
        if (distinctCorrect.size != 1) {
            sb.append("Неоднозначно (${distinctCorrect.size} разных ответов) — пропуск\n\n")
            log(sb.toString())
            hideDot()
            return
        }

        val correctNorm = distinctCorrect[0]
        val correctNode = textNodes
            .filter { it.norm == correctNorm }
            .minByOrNull { it.rect.top }

        if (correctNode == null) {
            sb.append("Текст ответа '$correctNorm' не найден на экране\n\n")
            log(sb.toString())
            hideDot()
            return
        }

        sb.append("ВЫБРАНО: '$correctNorm' @ ${correctNode.rect}\n\n")
        log(sb.toString())
        showDot(correctNode.rect)
    }

    private fun questionMatches(qNorm: String, screenTexts: List<String>): Boolean {
        if (screenTexts.any { it == qNorm }) return true
        if (qNorm.length >= 60) {
            val prefix = qNorm.take(60)
            if (screenTexts.any { it.contains(prefix) }) return true
        }
        return false
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        out.add(node)
        for (i in 0 until node.childCount) traverseNode(node.getChild(i), out)
    }

    private fun showDot(rect: Rect) {
        val dotSize = 10
        if (dotView == null) {
            dotView = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#888888"))
                }
            }
            val params = WindowManager.LayoutParams(
                dotSize, dotSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            windowManager.addView(dotView, params)
        }
        val params = dotView?.layoutParams as WindowManager.LayoutParams
        params.x = rect.left - 18
        params.y = rect.centerY() - dotSize / 2
        windowManager.updateViewLayout(dotView, params)
    }

    private fun hideDot() {
        if (dotView != null) {
            try { windowManager.removeView(dotView) } catch (_: Exception) {}
            dotView = null
        }
    }

    override fun onInterrupt() { hideDot() }
    override fun onDestroy() { super.onDestroy(); hideDot() }

    // ============ ЛОГ В ФАЙЛ ============
    private fun log(text: String) {
        try {
            val file = File(getExternalFilesDir(null), "pdd_log.txt")
            // Обрезаем файл, если стал больше 200 КБ
            if (file.exists() && file.length() > 200_000) {
                file.writeText("")
            }
            file.appendText(text)
        } catch (_: Exception) {}
    }

    companion object {
        const val LOG_FILENAME = "pdd_log.txt"
    }
}

data class PddQuestion(
    val question: String,
    val answers: List<String>,
    val correct_answer: String
)
