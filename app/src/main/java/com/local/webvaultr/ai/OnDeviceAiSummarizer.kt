package com.local.webvaultr.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CaptureCompletenessStatus(val badgeLabel: String) {
    FULL_ARCHIVE("✅ Full Archive"),
    PARTIAL_TEASER("⚠️ Partial / Server-Truncated Teaser"),
    LOGIN_OR_CHALLENGE("🔒 Account Session Required")
}

class OnDeviceAiSummarizer(private val context: Context? = null) {

    /**
     * 1. Evaluates capture completeness and quality based on text length, scroll height, and paragraph count.
     */
    fun evaluateCaptureQuality(
        extractedText: String,
        rawScrollHeight: Int,
        paragraphCount: Int
    ): CaptureCompletenessStatus {
        val trimmed = extractedText.trim()
        val lower = trimmed.lowercase()
        if (trimmed.length < 180 ||
            lower.contains("performing security verification") ||
            lower.contains("verify you are human") ||
            lower.contains("enable javascript and cookies")
        ) {
            return CaptureCompletenessStatus.LOGIN_OR_CHALLENGE
        }

        val wordCount = trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
        val endsWithTeaserPrompt = lower.endsWith("continue reading") ||
            lower.contains("getting your experience ready") ||
            lower.contains("subscribe to continue") ||
            lower.contains("already a subscriber") ||
            lower.contains("to continue reading, please login")

        if (wordCount < 160 || (paragraphCount in 1..4 && wordCount < 240) || endsWithTeaserPrompt) {
            return CaptureCompletenessStatus.PARTIAL_TEASER
        }
        return CaptureCompletenessStatus.FULL_ARCHIVE
    }

    /**
     * 2. Cleans extracted prose corpus by stripping marketing, subscription, widget code, and markdown navigation.
     */
    fun cleanExtractedProseCorpus(rawText: String): String {
        if (rawText.isBlank()) return ""

        val subscriptionPattern = Regex("(?im)^.*(sign up for our|subscribe to our|get .* unlimited access|to continue reading, please login|read distraction-free).*$")
        val accountPattern = Regex("(?im)^.*(already have an account\\?|already a subscriber\\?|manage cookie preferences|we are getting your experience ready|for subscribers).*$")
        val widgetScriptPattern = Regex("(?im)^.*(\\(function\\s*\\(|pymParent|datawrapper-height|addEventListener\\(`message`).*$")
        val markdownLinkListPattern = Regex("(?im)^\\s*[•*\\-]\\s*\\[[^\\]]+]\\([^)]+\\)\\s*$")

        val blocks = rawText.split(Regex("\\n{2,}"))
        val cleanParagraphs = mutableListOf<String>()

        for (block in blocks) {
            val trimmedBlock = block.trim()
            if (trimmedBlock.isBlank()) continue

            if (trimmedBlock.contains("pymParent", ignoreCase = true) ||
                trimmedBlock.contains("datawrapper-height", ignoreCase = true) ||
                trimmedBlock.contains("window.addEventListener", ignoreCase = true) ||
                trimmedBlock.contains("addEventListener(`message", ignoreCase = true)
            ) {
                continue
            }

            val cleanedLines = trimmedBlock.lines().filter { line ->
                val lineTrim = line.trim()
                lineTrim.isNotBlank() &&
                    !subscriptionPattern.matches(lineTrim) &&
                    !accountPattern.matches(lineTrim) &&
                    !widgetScriptPattern.matches(lineTrim) &&
                    !markdownLinkListPattern.matches(lineTrim)
            }

            if (cleanedLines.isNotEmpty()) {
                val cleanedBlock = cleanedLines.joinToString("\n").trim()
                if (cleanedBlock.isNotBlank()) {
                    cleanParagraphs.add(cleanedBlock)
                }
            }
        }

        return cleanParagraphs.joinToString("\n\n")
    }

    /**
     * 3. Generates executive takeaways & key figures on Dispatchers.Default with fast zero-RAM deterministic fallback.
     */
    suspend fun generateArticleTakeaways(title: String, bodyText: String): String = withContext(Dispatchers.Default) {
        val cleanBody = cleanExtractedProseCorpus(bodyText)
        val sentences = cleanBody.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length in 45..260 && !it.startsWith("####") && !it.startsWith(">") }

        val reportingVerbsRegex = Regex("(?i)\\b(said|announced|reported|according|voted|ruled|stated|concluded|found|projected)\\b")
        val figuresRegex = Regex("(\\$\\d+[\\d,.]*\\s*(?:million|billion|trillion)?|\\d+(?:\\.\\d+)?%|\\b\\d{4}\\b)")

        val scoredSentences = sentences.map { sentence ->
            var score = 0
            if (figuresRegex.containsMatchIn(sentence)) score += 3
            if (reportingVerbsRegex.containsMatchIn(sentence)) score += 2
            if (title.split(Regex("\\s+")).any { w -> w.length > 4 && sentence.contains(w, ignoreCase = true) }) score += 1
            Pair(sentence, score)
        }

        val topSentences = scoredSentences
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(4)

        val extractedFigures = figuresRegex.findAll(cleanBody)
            .map { it.value.trim() }
            .filter { it.length in 2..28 }
            .distinct()
            .take(6)
            .toList()

        buildString {
            appendLine("✨ EXECUTIVE TAKEAWAYS")
            if (title.isNotBlank()) {
                appendLine("Headline: $title")
            }
            appendLine()
            if (topSentences.isNotEmpty()) {
                topSentences.forEach { s ->
                    appendLine("• $s")
                }
            } else {
                appendLine("• ${cleanBody.take(180).ifBlank { "Offline archive captured successfully." }}")
            }

            if (extractedFigures.isNotEmpty()) {
                appendLine()
                appendLine("📊 Key Figures & Dates: ${extractedFigures.joinToString(", ")}")
            }
        }.trim()
    }

    /**
     * 4. Suggests a collection category based on existing collections or keyword regex categorization.
     */
    fun suggestCollectionCategory(
        title: String,
        summary: String,
        tags: List<String>,
        existingCollections: Collection<String>
    ): String {
        val corpus = "$title $summary ${tags.joinToString(" ")}".lowercase()

        // 1. Check existing collection names first
        for (col in existingCollections) {
            val colLower = col.trim().lowercase()
            if (colLower.length > 2 && corpus.contains(colLower)) {
                return col
            }
        }

        // 2. Keyword regex categorization
        val politicsRegex = Regex("\\b(vote|election|senate|congress|court|ruling|biden|trump|governor|democrat|republican|policy|bill|legislation|lawsuit|judge|attorney)\\b")
        val techRegex = Regex("\\b(ai|apple|google|microsoft|software|hardware|app|game|gaming|nvidia|chip|crypto|cyber|tech|robot|code|developer)\\b")
        val businessRegex = Regex("\\b(market|stock|fed|inflation|dollar|trade|economy|revenue|profit|bank|quarter|investor|shares|nasdaq|dow)\\b")
        val scienceRegex = Regex("\\b(health|medical|disease|virus|fda|cancer|climate|planet|space|nasa|biology|physics|doctor|vaccine|hospital|drug)\\b")

        return when {
            politicsRegex.containsMatchIn(corpus) -> "Politics & Policy"
            techRegex.containsMatchIn(corpus) -> "Tech & Gaming"
            businessRegex.containsMatchIn(corpus) -> "Business & Economy"
            scienceRegex.containsMatchIn(corpus) -> "Science & Health"
            else -> "Saved Research"
        }
    }
}
