package com.local.webvaultr.util

import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.local.webvaultr.data.local.ArchivedPage
import kotlin.math.abs

object DomainColorHelper {

    private val websitePalette = listOf(
        Color(0xFFE53935), // BBC / News Crimson
        Color(0xFF1E88E5), // Tech Cobalt Blue
        Color(0xFF00897B), // Emerald Teal
        Color(0xFF8E24AA), // Deep Amethyst
        Color(0xFFF4511E), // Trebuchet Tangerine
        Color(0xFF3949AB), // Indigo Slate
        Color(0xFF00ACC1), // Cyber Cyan
        Color(0xFF43A047)  // Android Green
    )

    fun extractDisplaySiteName(page: ArchivedPage): String {
        val explicitSite = page.siteName.trim()
        if (explicitSite.isNotBlank()) return explicitSite
        val host = runCatching { Uri.parse(page.originalUrl).host ?: page.originalUrl }
            .getOrDefault(page.originalUrl)
            .removePrefix("www.")
            .removePrefix("m.")
        val domainCore = host.substringBefore(".")
        return when (domainCore.lowercase()) {
            "bbc" -> "BBC News"
            "nytimes" -> "NY Times"
            "theverge" -> "The Verge"
            "arstechnica" -> "Ars Technica"
            "wikipedia" -> "Wikipedia"
            "cnn" -> "CNN"
            "reuters" -> "Reuters"
            else -> domainCore.replaceFirstChar { it.uppercase() }.ifBlank { host }
        }
    }

    fun colorForPage(page: ArchivedPage): Color {
        val site = extractDisplaySiteName(page).lowercase()
        if (site.contains("bbc")) return Color(0xFFD32F2F)
        if (site.contains("verge") || site.contains("ars")) return Color(0xFFFF6D00)
        if (site.contains("wiki")) return Color(0xFF546E7A)
        val index = abs(site.hashCode()) % websitePalette.size
        return websitePalette[index]
    }

    fun formatWebsitePrefixedDescription(page: ArchivedPage): String {
        val siteName = extractDisplaySiteName(page)
        val authorPart = page.author.takeIf { it.isNotBlank() }?.let { "by $it — " } ?: ""
        val bodyPart = page.aiSummary.ifBlank {
            page.extractedTextSummary.take(180).ifBlank { "Offline snapshot ready." }
        }
        return "$siteName - $authorPart$bodyPart"
    }
}
