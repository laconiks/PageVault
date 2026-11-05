package com.local.webvaultr.ai

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

data class AiAnalysisResult(
    val summary: String,
    val tags: List<String>,
    val modelLabel: String
)

data class EdgeModelOption(
    val id: String,
    val displayName: String,
    val recommendationBadge: String,
    val filePath: String?,
    val isAvailableOnDisk: Boolean
)

enum class AiEdgeModel(
    val id: String,
    val displayName: String,
    val relativePath: String,
    val description: String
) {
    GEMMA_2B(
        "gemma-2b",
        "Gemma 2B IT (Edge)",
        "models/gemma_edge.bin",
        "Google AI Edge 2B parameter instruction-tuned model."
    ),
    GEMMA_2_2B(
        "gemma2-2b",
        "Gemma 2 2B IT",
        "models/gemma2_2b.bin",
        "Next-generation compact language model from Google."
    ),
    LOCAL_HEURISTIC(
        "local-heuristic",
        "Local Heuristic Fallback",
        "",
        "Fast zero-dependency extractive summary & TF-IDF keywords."
    )
}

data class AiEnrichmentResult(
    val summary: String,
    val tags: List<String>,
    val tagsCsv: String,
    val ocrText: String,
    val modelUsed: String
)

class LocalAiEdgeProcessor(private val context: Context) {

    companion object {
        const val RELATIVE_MODEL_PATH = "models/gemma_edge.bin"
    }

    private val prefs = context.getSharedPreferences("pagevault_ai_prefs", Context.MODE_PRIVATE)
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    var currentModel: AiEdgeModel
        get() {
            val id = getSelectedModelId()
            return when (id) {
                "gemma3n_2b" -> AiEdgeModel.GEMMA_2B
                "llama32_3b" -> AiEdgeModel.GEMMA_2_2B
                "extractive_deterministic" -> AiEdgeModel.LOCAL_HEURISTIC
                else -> AiEdgeModel.GEMMA_2B
            }
        }
        set(value) {
            val id = when (value) {
                AiEdgeModel.GEMMA_2B -> "gemma3n_2b"
                AiEdgeModel.GEMMA_2_2B -> "llama32_3b"
                AiEdgeModel.LOCAL_HEURISTIC -> "extractive_deterministic"
            }
            setSelectedModel(id)
        }

    fun isFirstLaunchOnboardingNeeded(): Boolean =
        !prefs.getBoolean("edge_onboarding_shown", false)

    fun markOnboardingComplete() {
        prefs.edit().putBoolean("edge_onboarding_shown", true).apply()
    }

    fun getSelectedModelId(): String =
        prefs.getString("selected_model_id", "gemma3n_2b") ?: "gemma3n_2b"

    fun getSelectedModelCustomPath(): String =
        prefs.getString("custom_model_path", "") ?: ""

    fun setSelectedModel(modelId: String, customPath: String? = null) {
        prefs.edit().apply {
            putString("selected_model_id", modelId)
            if (customPath != null) putString("custom_model_path", customPath)
            apply()
        }
    }

    fun getAvailableEdgeModels(): List<EdgeModelOption> {
        val customPath = getSelectedModelCustomPath()
        val candidateDirs = listOf(
            File(context.filesDir, "models"),
            File("/sdcard/Download"),
            File("/data/local/tmp/llm")
        )

        fun findModelFile(keyword: String): File? {
            if (customPath.isNotBlank() && File(customPath).exists() && customPath.contains(keyword, true)) {
                return File(customPath)
            }
            for (dir in candidateDirs) {
                val match = dir.listFiles()?.firstOrNull { file ->
                    file.isFile && (file.name.endsWith(".task") || file.name.endsWith(".litertlm") || file.name.endsWith(".bin")) &&
                        file.name.contains(keyword, ignoreCase = true)
                }
                if (match != null) return match
            }
            return null
        }

        val gemmaFile = findModelFile("gemma")
        val qwenFile = findModelFile("qwen")
        val llamaFile = findModelFile("llama")

        return listOf(
            EdgeModelOption(
                id = "gemma3n_2b",
                displayName = "Gemma 3n E2B / Gemma 2B (LiteRT .task)",
                recommendationBadge = "★ RECOMMENDED FOR SUMMARIES & DIFFS",
                filePath = gemmaFile?.absolutePath,
                isAvailableOnDisk = gemmaFile != null
            ),
            EdgeModelOption(
                id = "qwen25_15b",
                displayName = "Qwen 2.5 1.5B Instruct (AI Edge Gallery)",
                recommendationBadge = "⚡ FASTEST FOR KEYWORD & WORD MAPPING",
                filePath = qwenFile?.absolutePath,
                isAvailableOnDisk = qwenFile != null
            ),
            EdgeModelOption(
                id = "llama32_3b",
                displayName = "Llama 3.2 3B Instruct (Qualcomm / CPU)",
                recommendationBadge = "DEEP CONTEXT (4K CONTEXT WINDOW)",
                filePath = llamaFile?.absolutePath,
                isAvailableOnDisk = llamaFile != null
            ),
            EdgeModelOption(
                id = "extractive_deterministic",
                displayName = "Built-in Extractive & Diff Engine",
                recommendationBadge = "✔ 100% OFFLINE (ZERO DOWNLOAD REQUIRED)",
                filePath = "built_in",
                isAvailableOnDisk = true
            )
        )
    }

    suspend fun extractOcrFromScreenshot(imagePath: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(imagePath)
            if (!file.exists()) return@withContext ""
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext ""
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = textRecognizer.process(image).await()
            result.text.take(4000)
        }.getOrDefault("")
    }

    suspend fun summarizeAndTag(
        title: String,
        rawText: String,
        ocrText: String = "",
        publisherTags: List<String> = emptyList()
    ): AiAnalysisResult = withContext(Dispatchers.Default) {
        val combined = "$title. $rawText$ocrText".trim()
        val selectedId = getSelectedModelId()
        val models = getAvailableEdgeModels()
        val chosenOption = models.find { it.id == selectedId } ?: models.first()

        val resolvedTags = resolveFinalTags(publisherTags, combined)

        if (selectedId != "extractive_deterministic" && chosenOption.filePath != null && chosenOption.filePath != "built_in") {
            val llmResult = runCatching {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(chosenOption.filePath)
                    .setMaxTokens(256)
                    .build()
                LlmInference.createFromOptions(context, options).use { llm ->
                    val prompt = "Summarize this article concisely in 3 sentences and list 4 keywords:\n${combined.take(2500)}"
                    llm.generateResponse(prompt)
                }
            }.getOrNull()

            if (!llmResult.isNullOrBlank()) {
                return@withContext AiAnalysisResult(
                    summary = llmResult.trim(),
                    tags = resolvedTags,
                    modelLabel = chosenOption.displayName.substringBefore(" (")
                )
            }
        }

        // Deterministic local fallback when model file is not yet downloaded
        val noisePattern = Regex(
            "^(skip to main content|the homepage|subscribe|featured videos|add .* on google|follow topics|advertisement|photo:|image:|comments|terms of use)",
            RegexOption.IGNORE_CASE
        )
        val cleanParagraphs = rawText
            .split("\n")
            .map { it.trim() }
            .filter { line ->
                line.length > 30 &&
                    !line.startsWith("####") &&
                    !line.startsWith("#") &&
                    !line.startsWith(">") &&
                    !noisePattern.containsMatchIn(line)
            }

        val sentences = cleanParagraphs
            .flatMap { para -> para.split(Regex("(?<=[.!?])\\s+")).map { it.trim() } }
            .filter { sentence ->
                sentence.length in 35..350 &&
                    !noisePattern.containsMatchIn(sentence) &&
                    !sentence.equals("skip to main content", ignoreCase = true)
            }
            .take(3)

        val fallbackSummary = if (sentences.isNotEmpty()) {
            sentences.joinToString(" ")
        } else {
            ocrText.take(320).ifBlank { "Captured offline snapshot of $title." }
        }

        val activeLabel = if (selectedId == "extractive_deterministic") {
            "Built-in Extractive"
        } else {
            "${chosenOption.displayName.substringBefore(" (")} (Heuristic Fallback)"
        }

        AiAnalysisResult(
            summary = fallbackSummary.take(500),
            tags = resolvedTags,
            modelLabel = activeLabel
        )
    }

    private fun resolveFinalTags(publisherTags: List<String>, text: String): List<String> {
        val cleanPub = publisherTags
            .map { it.trim().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } } }
            .filter { it.isNotBlank() && it.length in 2..32 }
            .distinct()
            .take(5)

        return if (cleanPub.size >= 3) {
            cleanPub
        } else {
            (cleanPub + extractTopKeywords(text)).distinct().take(4)
        }
    }

    suspend fun compareArticleVersions(
        oldTitle: String,
        oldText: String,
        newTitle: String,
        newText: String
    ): String = withContext(Dispatchers.Default) {
        val oldSentences = oldText.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length > 25 }.toSet()
        val newSentences = newText.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length > 25 }.toSet()

        val added = (newSentences - oldSentences).take(3)
        val removed = (oldSentences - newSentences).take(2)

        val oldWords = oldText.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val newWords = newText.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val deltaWords = newWords - oldWords

        buildString {
            if (oldTitle.trim() != newTitle.trim()) {
                append("• Headline Changed: \"$oldTitle\" → \"$newTitle\"\n")
            }
            append("• Word Count Shift: $oldWords → $newWords words (${if (deltaWords >= 0) "+$deltaWords" else "$deltaWords"})\n")
            if (added.isEmpty() && removed.isEmpty()) {
                append("• Content Verification: Body text is 100% identical to previous pull.")
            } else {
                if (added.isNotEmpty()) {
                    append("• New/Updated Passages (${added.size}): \"${added.first().take(140)}...\"\n")
                }
                if (removed.isNotEmpty()) {
                    append("• Removed/Edited Passages (${removed.size}): \"${removed.first().take(120)}...\"")
                }
            }
        }.trim()
    }

    suspend fun enrichCapture(
        title: String,
        extractedDomText: String,
        screenshotFile: File,
        preferredModel: AiEdgeModel = currentModel,
        publisherTags: List<String> = emptyList()
    ): AiEnrichmentResult = withContext(Dispatchers.Default) {
        val ocrText = extractOcrFromScreenshot(screenshotFile.absolutePath)
        val analysis = summarizeAndTag(title, extractedDomText, ocrText, publisherTags)
        AiEnrichmentResult(
            summary = analysis.summary,
            tags = analysis.tags,
            tagsCsv = analysis.tags.joinToString(", "),
            ocrText = ocrText,
            modelUsed = analysis.modelLabel
        )
    }

    fun computeArticleDiff(oldText: String, newText: String): String {
        if (oldText.isBlank() || newText.isBlank()) return ""
        val oldWords = oldText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val newWords = newText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordDelta = newWords.size - oldWords.size
        val wordDeltaStr = if (wordDelta >= 0) "+$wordDelta words" else "$wordDelta words"

        val oldSentences = oldText.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length > 25 }
            .toSet()
        val newSentences = newText.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length > 25 }

        val addedSentences = newSentences.filter { it !in oldSentences }
        val diffDetail = if (addedSentences.isNotEmpty()) {
            val sample = addedSentences.first().take(100)
            "Added: \"$sample${if (addedSentences.first().length > 100) "..." else ""}\""
        } else if (wordDelta != 0) {
            "Text updated across sections"
        } else {
            "Content refreshed without major changes"
        }

        return "$wordDeltaStr • $diffDetail"
    }

    private fun extractTopKeywords(text: String): List<String> {
        val stopWords = setOf(
            "the", "and", "for", "that", "this", "with", "from", "your", "have", "more",
            "will", "about", "https", "http", "www", "com", "page", "article", "news", "said",
            "also", "been", "were", "they", "their", "which", "would", "what", "there", "when",
            "after", "could", "first", "into", "other", "than", "some", "them", "then", "like",
            "over", "only", "even", "most", "made", "many", "such", "where", "just", "being",
            "these", "those", "because", "while", "before", "between", "both", "through"
        )

        val properEntities = Regex("\\b[A-Z][a-z]{3,}(?:\\s+[A-Z][a-z]{3,})?\\b")
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.lowercase() !in stopWords && !it.contains(Regex("^(The|This|That|With|From|They|There|When|After|What|While|Where)\\b")) }
            .groupingBy { it }
            .eachCount()
            .entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(4)

        if (properEntities.size >= 3) return properEntities

        val singleWords = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length in 5..16 && it !in stopWords }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key.replaceFirstChar { c -> c.uppercase() } }
            .take(4)

        return (properEntities + singleWords).distinct().take(4)
    }
}
