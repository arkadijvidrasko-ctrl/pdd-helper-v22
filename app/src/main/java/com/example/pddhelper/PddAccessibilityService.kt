package com.example.pddhelper

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PddAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var badgeView: TextView? = null
    private var questions: List<PddQuestion> = emptyList()
    private var ambiguousQuestionKeys: Set<String> = emptySet()

    private data class TextNode(val node: AccessibilityNodeInfo, val norm: String, val rect: Rect)

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadDatabase()
        logLine("=== Сервис запущен === База: ${questions.size}, неоднозначных: ${ambiguousQuestionKeys.size}")
    }

    private fun loadDatabase() {
        try {
            assets.open("questions.json").use { input ->
                val reader = InputStreamReader(input)
                val type = object : TypeToken<List<PddQuestion>>() {}.type
                questions = Gson().fromJson(reader, type)
            }
            val byQuestion = questions.groupBy { normalizeText(it.question) }
            val ambiguous = mutableSetOf<String>()
            for ((q, list) in byQuestion) {
                val corrects = list.map { normalizeText(it.correct_answer) }.distinct()
                if (corrects.size > 1) ambiguous.add(q)
            }
            ambiguousQuestionKeys = ambiguous
        } catch (e: Exception) {
            logLine("Ошибка загрузки базы: ${e.message}")
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
        if (event == null) return
        if (event.packageName == packageName) return

        val root = rootInActiveWindow
        if (root == null) {
            // Диагностика: возможно защита
            val sb = StringBuilder()
            sb.append("\n=== ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} ===\n")
            sb.append("Пакет: ${event.packageName}\n")
            sb.append("root = null (возможна защита или нет активного окна)\n")
            logLine(sb.toString())
            hideBadge()
            return
        }

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
            textNodes.add(TextNode(n, norm, r))
        }
        val screenTexts = textNodes.map { it.norm }

        // === Диагностика: если текстов нет, но узлы есть ===
        if (screenTexts.isEmpty()) {
            val sb = StringBuilder()
            sb.append("\n=== ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} ===\n")
            sb.append("Пакет: ${event.packageName}\n")
            sb.append("Узлов: ${allNodes.size}, текстовых: 0\n")
            sb.append("Причина: текст не читается через accessibility.\n")
            sb.append("Возможные защиты:\n")
            sb.append("  • FLAG_SECURE на окне\n")
            sb.append("  • WebView без accessibility\n")
            sb.append("  • Canvas/SurfaceView\n")
            sb.append("  • Кастомные View без text\n")

            // Смотрим, что есть в узлах: contentDescription, viewIdResourceName, className
            sb.append("Первые 15 узлов (className / viewId / contentDesc / text):\n")
            allNodes.take(15).forEachIndexed { idx, n ->
                val cn = n.className?.toString() ?: "?"
                val vid = n.viewIdResourceName ?: "-"
                val cd = n.contentDescription?.toString() ?: "-"
                val tx = n.text?.toString() ?: "-"
                sb.append("  [$idx] $cn | $vid | cd='$cd' | text='$tx'\n")
            }
            logLine(sb.toString())
            hideBadge()
            return
        }

        // === Поиск кандидатов ===
        val candidates = mutableListOf<PddQuestion>()
        for (q in questions) {
            val nq = normalizeText(q.question)
            if (nq.length < 10) continue
            if (ambiguousQuestionKeys.contains(nq)) continue
            if (!questionMatches(nq, screenTexts)) continue
            val allAnswersOnScreen = q.answers.all { a ->
                val na = normalizeText(a)
                na.isNotEmpty() && screenTexts.any { it == na }
            }
            if (allAnswersOnScreen) candidates.add(q)
        }

        if (candidates.isEmpty()) { hideBadge(); return }

        val distinctCorrect = candidates.map { normalizeText(it.correct_answer) }.distinct()
        if (distinctCorrect.size != 1) { hideBadge(); return }

        val correctNorm = distinctCorrect[0]
        val correctNode = textNodes
            .filter { it.norm == correctNorm }
            .minByOrNull { it.rect.top }

        if (correctNode == null) { hideBadge(); return }

        val answerNumber = findAnswerNumber(correctNode, allNodes)
        val pos = findBadgePosition(correctNode)

        val sb = StringBuilder()
        sb.append("\n=== ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} ===\n")
        sb.append("Пакет: ${event.packageName}\n")
        sb.append("Вопрос: '${candidates.first().question}'\n")
        sb.append("Правильный: '$correctNorm' под номером $answerNumber\n")
        sb.append("Позиция бейджа: ($pos)\n")
        logLine(sb.toString())

        showBadge(answerNumber, pos.first, pos.second)
    }

    private fun findAnswerNumber(correct: TextNode, allNodes: List<AccessibilityNodeInfo>): String {
        var bestNum: String? = null
        var bestDist = Int.MAX_VALUE
        for (n in allNodes) {
            val t = n.text?.toString()?.trim() ?: continue
            if (!t.matches(Regex("^\\d+\\.?$"))) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() <= 0) continue
            if (r.centerY() > correct.rect.centerY() + 20) continue
            val dist = correct.rect.left - r.right
            if (dist in 0..200 && dist < bestDist) {
                bestDist = dist
                bestNum = t.replace(".", "").trim()
            }
        }
        return bestNum ?: "?"
    }

    private fun findBadgePosition(correct: TextNode): Pair<Int, Int> {
        val size = 30
        val parent = correct.node.parent
        if (parent != null) {
            var leftmost = correct.rect.left
            for (i in 0 until parent.childCount) {
                val sib = parent.getChild(i) ?: continue
                val t = sib.text?.toString()?.trim() ?: continue
                if (t.matches(Regex("^\\d+\\.?$"))) {
                    val r = Rect()
                    sib.getBoundsInScreen(r)
                    if (r.width() > 0 && r.left < leftmost) leftmost = r.left
                }
            }
            return Pair(leftmost - size - 6, correct.rect.centerY() - size / 2)
        }
        return Pair(correct.rect.left - size - 6, correct.rect.centerY() - size / 2)
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

    private fun showBadge(number: String, x: Int, y: Int) {
        val size = 30
        if (badgeView == null) {
            badgeView = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#888888")) // серый
                }
            }
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            windowManager.addView(badgeView, params)
        }
        badgeView?.text = number
        val params = badgeView?.layoutParams as WindowManager.LayoutParams
        params.width = size
        params.height = size
        params.x = x
        params.y = y
        windowManager.updateViewLayout(badgeView, params)
    }

    private fun hideBadge() {
        if (badgeView != null) {
            try { windowManager.removeView(badgeView) } catch (_: Exception) {}
            badgeView = null
        }
    }

    override fun onInterrupt() { hideBadge() }
    override fun onDestroy() { super.onDestroy(); hideBadge() }

    private fun logLine(text: String) {
        try {
            val file = File(getExternalFilesDir(null), LOG_FILENAME)
            if (file.exists() && file.length() > 500_000) file.writeText("")
            file.appendText(text)
        } catch (_: Exception) {}
    }

    companion object {
        const val LOG_FILENAME = "pdd_log.txt"
    }
}

data class PddQuestion(val question: String, val answers: List<String>, val correct_answer: String)
