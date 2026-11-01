package com.local.pocketarchive.ai

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

data class AiEnrichmentResult(
    val summary: String,
    val tagsCsv: String,
    val ocrText: String
)

class LocalAiEdgeProcessor(private val context: Context) {

    companion object {
        const val RELATIVE_MODEL_PATH = "models/gemma_edge.bin"
    }

    private val ocrRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun enrichCapture(
        title: String,
        extractedDomText: String,
        screenshotFile: File
    ): AiEnrichmentResult = withContext(Dispatchers.Default) {
        val ocrText = runOcrOnScreenshot(screenshotFile)
        val combinedCorpus = "$title. $extractedDomText $ocrText".trim()

        val modelFile = File(context.filesDir, RELATIVE_MODEL_PATH)
        if (modelFile.exists() && modelFile.length() > 1024L) {
            runCatching {
                runMediaPipeLlmInference(modelFile.absolutePath, title, combinedCorpus, ocrText)
            }.getOrElse {
                runExtractiveFallback(title, combinedCorpus, ocrText)
            }
        } else {
            runExtractiveFallback(title, combinedCorpus, ocrText)
        }
    }

    private fun runMediaPipeLlmInference(
        modelPath: String,
        title: String,
        corpus: String,
        ocrText: String
    ): AiEnrichmentResult {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(256)
            .build()

        LlmInference.createFromOptions(context, options).use { llm ->
            val truncatedInput = corpus.take(3500)
            val prompt = """
                Summarize the following archived webpage in 2 concise bullet points, then on a new line starting with TAGS: list 5 lowercase comma-separated topic tags.
                Title: $title
                Content: $truncatedInput
            """.trimIndent()

            val rawResponse = llm.generateResponse(prompt).trim()
            val tagsLine = rawResponse.lines().firstOrNull { it.startsWith("TAGS:", ignoreCase = true) }
            val tagsCsv = tagsLine
                ?.substringAfter(":")
                ?.split(",")
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotBlank() }
                ?.take(5)
                ?.joinToString(", ")
                ?: extractTopKeywords(corpus)

            val cleanSummary = rawResponse.lines()
                .filterNot { it.startsWith("TAGS:", ignoreCase = true) }
                .joinToString("\n")
                .trim()
                .ifBlank { "Offline snapshot captured." }

            return AiEnrichmentResult(
                summary = cleanSummary,
                tagsCsv = tagsCsv,
                ocrText = ocrText.take(2000)
            )
        }
    }

    private fun runExtractiveFallback(
        title: String,
        corpus: String,
        ocrText: String
    ): AiEnrichmentResult {
        val sentences = corpus.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length in 40..220 }
            .take(2)
        val summary = if (sentences.isNotEmpty()) {
            sentences.joinToString(" ")
        } else {
            "Archived offline snapshot of $title"
        }
        return AiEnrichmentResult(
            summary = summary,
            tagsCsv = extractTopKeywords(corpus),
            ocrText = ocrText.take(2000)
        )
    }

    private fun extractTopKeywords(corpus: String): String {
        val stopWords = setOf(
            "the", "and", "for", "that", "this", "with", "from", "your", "have",
            "more", "about", "will", "home", "page", "http", "https", "com"
        )
        return corpus.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 && it !in stopWords }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString(", ") { it.key }
    }

    private suspend fun runOcrOnScreenshot(file: File): String = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext ""
        runCatching {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val visionText = ocrRecognizer.process(image).await()
            visionText.text.replace(Regex("\\s+"), " ").trim()
        }.getOrDefault("")
    }
}
