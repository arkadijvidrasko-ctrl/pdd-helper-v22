package com.example.pddhelper

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

class PddAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var dotView: View? = null
    private var questions: List<PddQuestion> = emptyList()

    private data class TextNode(
        val norm: String,
        val rect: Rect
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadDatabase()
    }

    private fun loadDatabase() {
        try {
            assets.open("questions.json").use { input ->
                val reader = InputStreamReader(input)
                val type = object : TypeToken<List<PddQuestion>>() {}.type
                questions = Gson().fromJson(reader, type)
                Log.d(TAG, "Загружено вопросов: ${questions.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки базы: ${e.message}")
        }
    }

    // Нормализация текста: нижний регистр, ё→е, без знаков, схлопнутые пробелы,
    // без ведущего номера ("1 ", "2 ", ...)
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

        // Ищем ВСЕ вопросы, у которых и текст, и все ответы есть на экране
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

        Log.d(TAG, "Кандидатов: ${candidates.size}")
        candidates.forEach { Log.d(TAG, "  кандидат: '${it.question}' -> '${it.correct_answer}'") }

        // Правильные ответы у всех кандидатов должны совпадать
        val distinctCorrect = candidates.map { normalizeText(it.correct_answer) }.distinct()
        if (distinctCorrect.size != 1) {
            Log.d(TAG, "Неоднозначно (${distinctCorrect.size} разных ответов), пропускаем")
            hideDot()
            return
        }

        val correctNorm = distinctCorrect[0]
        if (correctNorm.isEmpty()) {
            hideDot()
            return
        }

        // Ищем текст правильного ответа, берём самый верхний
        val correctNode = textNodes
            .filter { it.norm == correctNorm }
            .minByOrNull { it.rect.top }

        if (correctNode == null) {
            Log.d(TAG, "Не нашли текст ответа '$correctNorm' на экране")
            hideDot()
            return
        }

        Log.d(TAG, "OK: правильный ответ '$correctNorm', позиция ${correctNode.rect}")
        showDot(correctNode.rect)
    }

    // Совпадение вопроса: сначала полное, затем по длинному префиксу
    private fun questionMatches(qNorm: String, screenTexts: List<String>): Boolean {
        // 1. Полное совпадение
        if (screenTexts.any { it == qNorm }) return true

        // 2. Совпадение по длинному префиксу (60 символов)
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

    companion object {
        private const val TAG = "PDD"
    }
}

data class PddQuestion(
    val question: String,
    val answers: List<String>,
    val correct_answer: String
)
