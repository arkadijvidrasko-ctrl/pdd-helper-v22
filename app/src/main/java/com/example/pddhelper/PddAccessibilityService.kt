package com.example.pddhelper

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadDatabase()
    }

    private fun loadDatabase() {
        try {
            val inputStream = assets.open("questions.json")
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<List<PddQuestion>>() {}.type
            questions = Gson().fromJson(reader, type)
            android.util.Log.d("PDD", "Загружено вопросов: ${questions.size}")
        } catch (e: Exception) {
            android.util.Log.e("PDD", "Ошибка загрузки базы: ${e.message}")
        }
    }

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-zа-яё0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        traverseNode(rootNode, allNodes)

        // Собираем нормализованные тексты всех узлов
        val screenTexts = allNodes.mapNotNull { node ->
            val text = node.text?.toString()?.let { normalizeText(it) }
            if (text.isNullOrEmpty()) null else text
        }

        // Ищем совпадение по вопросу + ответам
        var matchedQuestion: PddQuestion? = null

        for (question in questions) {
            val normQuestion = normalizeText(question.question)
            if (normQuestion.isEmpty()) continue

            // Есть ли текст вопроса на экране?
            if (!screenTexts.any { it == normQuestion }) continue

            // Есть ли ВСЕ варианты ответов этого вопроса на экране?
            val allAnswersFound = question.answers.all { answer ->
                val normAnswer = normalizeText(answer)
                normAnswer.isNotEmpty() && screenTexts.any { it == normAnswer }
            }

            if (allAnswersFound) {
                matchedQuestion = question
                break
            }
        }

        // Если совпадение найдено — ищем координаты правильного ответа
        if (matchedQuestion != null) {
            val correctAnswerNorm = normalizeText(matchedQuestion.correct_answer)
            val correctNode = allNodes.find {
                normalizeText(it.text?.toString() ?: "") == correctAnswerNorm
            }

            if (correctNode != null) {
                val rect = Rect()
                correctNode.getBoundsInScreen(rect)
                showDot(rect)
                return
            }
        }

        hideDot()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, nodes: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        nodes.add(node)
        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), nodes)
        }
    }

    private fun showDot(rect: Rect) {
        if (dotView == null) {
            dotView = View(this).apply {
                setBackgroundColor(android.graphics.Color.GREEN)
            }
            val params = WindowManager.LayoutParams(
                20, 20,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            windowManager.addView(dotView, params)
        }

        val params = dotView?.layoutParams as WindowManager.LayoutParams
        params.x = rect.left - 30
        params.y = rect.centerY() - 10
        windowManager.updateViewLayout(dotView, params)
    }

    private fun hideDot() {
        if (dotView != null) {
            try { windowManager.removeView(dotView) } catch (e: Exception) {}
            dotView = null
        }
    }

    override fun onInterrupt() { hideDot() }
    override fun onDestroy() { super.onDestroy(); hideDot() }
}

data class PddQuestion(
    val question: String,
    val answers: List<String>,
    val correct_answer: String
)
