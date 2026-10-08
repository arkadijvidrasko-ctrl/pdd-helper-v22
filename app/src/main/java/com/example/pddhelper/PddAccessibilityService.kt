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

    // Один текстовый узел на экране
    private data class TextNode(
        val node: AccessibilityNodeInfo,
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

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace("ё", "е")
            .replace(Regex("[^a-zа-я0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return

        // ======= ШАГ 1. Собираем тексты и их координаты =======
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        traverseNode(root, allNodes)

        val textNodes = mutableListOf<TextNode>()
        for (n in allNodes) {
            if (n.childCount > 0) continue                // только листья (реальные TextView)
            val raw = n.text?.toString() ?: continue
            val norm = normalizeText(raw)
            if (norm.isEmpty()) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) continue
            textNodes.add(TextNode(n, norm, r))
        }
        val screenTexts = textNodes.map { it.norm }

        // ======= ШАГ 2 и 3. Ищем вопрос, у которого и текст, и все ответы есть на экране =======
        val candidates = mutableListOf<PddQuestion>()
        for (q in questions) {
            val nq = normalizeText(q.question)
            if (nq.length < 8) continue

            if (!questionOnScreen(nq, screenTexts)) continue

            val allAnswersOnScreen = q.answers.all { a ->
                val na = normalizeText(a)
                na.isNotEmpty() && screenTexts.any { it == na }
            }
            if (allAnswersOnScreen) candidates.add(q)
        }

        // ======= ШАГ 4. Проверяем однозначность =======
        // Если разные кандидаты дают разные правильные ответы — молчим (не гадаем).
        val distinctCorrect = candidates.map { normalizeText(it.correct_answer) }.distinct()
        if (distinctCorrect.size != 1) {
            hideDot()
            return
        }
        val correctNorm = distinctCorrect[0]

        // ======= ШАГ 5. Ищем текст правильного ответа на экране =======
        val correctNode = textNodes
            .filter { it.norm == correctNorm }
            .minByOrNull { it.rect.top }   // берём самый верхний, если вдруг дубликат

        if (correctNode == null) {
            hideDot()
            return
        }

        Log.d(TAG, "OK: '${candidates.first().question}' -> '${correctNorm}'")
        showDot(correctNode.rect)
    }

    // Проверяем, есть ли текст вопроса на экране (по началу, если текст обрезан)
    private fun questionOnScreen(qNorm: String, screenTexts: List<String>): Boolean {
        if (screenTexts.any { it == qNorm }) return true
        val prefix = qNorm.take(25)
        if (prefix.length < 8) return false
        return screenTexts.any { it.contains(prefix) }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        out.add(node)
        for (i in 0 until node.childCount) traverseNode(node.getChild(i), out)
    }

    // ======= Рисуем серый кружок 10×10 слева от текста ответа =======
    private fun showDot(rect: Rect) {
        val dotSize = 10

        if (dotView == null) {
            dotView = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#888888")) // серый
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
        // Слева от начала текста ответа, по вертикальному центру строки
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
