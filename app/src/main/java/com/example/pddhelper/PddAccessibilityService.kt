package com.example.pddhelper

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.google.android.gms.tasks.Task
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastOcrTime = 0L
    private var ocrInProgress = false

    private data class TextNode(val node: AccessibilityNodeInfo, val norm: String, val rect: Rect)
    private data class OcrLine(val norm: String, val rect: Rect)

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
        if (root == null) { hideBadge(); return }

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

        if (screenTexts.isNotEmpty()) {
            // Обычный путь: текст читается через accessibility
            processScreen(event.packageName, textNodes.map { it.norm to it.rect })
            return
        }

        // Текст не читается — пробуем OCR
        tryOcr(event.packageName)
    }

    // === Обычная логика (по accessibility) ===
    private fun processScreen(pkg: String, lines: List<Pair<String, Rect>>) {
        val screenTexts = lines.map { it.first }
        val candidates = findCandidates(screenTexts)
        if (candidates.isEmpty()) { hideBadge(); return }

        val distinctCorrect = candidates.map { normalizeText(it.correct_answer) }.distinct()
        if (distinctCorrect.size != 1) { hideBadge(); return }
        val correctNorm = distinctCorrect[0]

        val correctLine = lines
            .filter { it.first == correctNorm }
            .minByOrNull { it.second.top } ?: run { hideBadge(); return }

        val answerNumber = findAnswerNumberFromTexts(correctLine.second, lines)
        val pos = Pair(correctLine.second.left - 36, correctLine.second.centerY() - 15)
        showBadge(answerNumber, pos.first, pos.second)
    }

    // === OCR-путь ===
    private fun tryOcr(pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            hideBadge()
            return
        }
        val now = System.currentTimeMillis()
        if (ocrInProgress || now - lastOcrTime < 1500) return
        lastOcrTime = now
        ocrInProgress = true

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer, screenshot.colorSpace
                    )
                    screenshot.hardwareBuffer.close()
                    if (bitmap == null) {
                        ocrInProgress = false
                        hideBadge()
                        return
                    }
                    runOcr(bitmap, pkg)
                }

                override fun onFailure(errorCode: Int) {
                    ocrInProgress = false
                    logLine("takeScreenshot failed: $errorCode")
                    hideBadge()
                }
            })
    }

    private fun runOcr(bitmap: Bitmap, pkg: String) {
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                ocrInProgress = false
                val lines = mutableListOf<Pair<String, Rect>>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val r = line.boundingBox ?: continue
                        val norm = normalizeText(line.text)
                        if (norm.length < 2) continue
                        lines.add(norm to r)
                    }
                }
                processOcrResult(pkg, lines)
            }
            .addOnFailureListener { e ->
                ocrInProgress = false
                logLine("OCR error: ${e.message}")
                hideBadge()
            }
    }

    private fun processOcrResult(pkg: String, lines: List<Pair<String, Rect>>) {
        val sb = StringBuilder()
        sb.append("\n=== ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} ===\n")
        sb.append("Пакет: $pkg (OCR)\n")
        sb.append("Распознано строк: ${lines.size}\n")
        lines.take(15).forEach { sb.append("   · '${it.first}' @ ${it.second}\n") }

        if (lines.isEmpty()) {
            sb.append("OCR пуст\n")
            logLine(sb.toString())
            hideBadge()
            return
        }

        val screenTexts = lines.map { it.first }
        val candidates = findCandidates(screenTexts)
        sb.append("Кандидатов: ${candidates.size}\n")
        candidates.forEach { sb.append("   ▶ '${it.question}' → '${it.correct_answer}'\n") }

        if (candidates.isEmpty()) { logLine(sb.toString()); hideBadge(); return }

        val distinctCorrect = candidates.map { normalizeText(it.correct_answer) }.distinct()
        if (distinctCorrect.size != 1) { logLine(sb.toString()); hideBadge(); return }
        val correctNorm = distinctCorrect[0]

        val correctLine = lines
            .filter { it.first == correctNorm }
            .minByOrNull { it.second.top }
        if (correctLine == null) {
            sb.append("Не нашли '$correctNorm' среди OCR-строк\n")
            logLine(sb.toString())
            hideBadge()
            return
        }

        val answerNumber = findAnswerNumberFromTexts(correctLine.second, lines)
        sb.append("ВЫБРАНО: '$correctNorm', номер: $answerNumber @ ${correctLine.second}\n")
        logLine(sb.toString())

        val pos = Pair(correctLine.second.left - 36, correctLine.second.centerY() - 15)
        showBadge(answerNumber, pos.first, pos.second)
    }

    // Общий поиск кандидатов по списку нормализованных строк
    private fun findCandidates(screenTexts: List<String>): List<PddQuestion> {
        val out = mutableListOf<PddQuestion>()
        for (q in questions) {
            val nq = normalizeText(q.question)
            if (nq.length < 10) continue
            if (ambiguousQuestionKeys.contains(nq)) continue
            if (!questionMatches(nq, screenTexts)) continue
            val allAnswersOnScreen = q.answers.all { a ->
                val na = normalizeText(a)
                na.isNotEmpty() && screenTexts.any { it == na || it.contains(na) || na.contains(it) && it.length > 8 }
            }
            if (allAnswersOnScreen) out.add(q)
        }
        return out
    }

    private fun questionMatches(qNorm: String, screenTexts: List<String>): Boolean {
        if (screenTexts.any { it == qNorm }) return true
        // Префикс
        val prefix = qNorm.take(minOf(qNorm.length, 40))
        if (prefix.length >= 10 && screenTexts.any { it.contains(prefix) || prefix.contains(it) && it.length > 20 }) return true
        return false
    }

    private fun findAnswerNumberFromTexts(answerRect: Rect, lines: List<Pair<String, Rect>>): String {
        var bestNum: String? = null
        var bestDist = Int.MAX_VALUE
        for ((text, rect) in lines) {
            if (!text.matches(Regex("^\\d+$"))) continue
            if (rect.centerY() > answerRect.centerY() + 25) continue
            if (rect.centerY() < answerRect.centerY() - 25) continue
            val dist = answerRect.left - rect.right
            if (dist in -100..300 && dist < bestDist) {
                bestDist = dist
                bestNum = text
            }
        }
        return bestNum ?: "?"
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
                    setColor(Color.parseColor("#888888"))
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
    override fun onDestroy() {
        super.onDestroy()
        try { textRecognizer.close() } catch (_: Exception) {}
        hideBadge()
    }

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
