package com.local.pocketarchive.capture

import android.content.Context
import android.util.Xml
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.local.pocketarchive.ai.LocalAiEdgeProcessor
import com.local.pocketarchive.data.local.AppDatabase
import com.local.pocketarchive.data.repository.ArchiveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class RssItem(
    val title: String,
    val link: String,
    val description: String
)

class RssFeedScraperWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(appContext).archiveDao()
        val subscriptions = dao.getAllRssSubscriptions()
        if (subscriptions.isEmpty()) return@withContext Result.success()

        val captureManager = WebArchiveCaptureManager(appContext)
        val aiProcessor = LocalAiEdgeProcessor(appContext)
        val repository = ArchiveRepository(dao, captureManager, aiProcessor)

        for (sub in subscriptions) {
            try {
                val items = fetchAndParseRss(sub.feedUrl)
                val keywords = sub.keywordsCsv
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }

                // Process up to 5 new matching articles per sync to protect battery/storage
                var archivedCount = 0
                for (item in items) {
                    if (archivedCount >= 5) break
                    if (item.link.isBlank() || dao.isUrlArchived(item.link)) continue

                    val combinedText = "${item.title} ${item.description}".lowercase()
                    val matchesKeyword = keywords.isEmpty() || keywords.any { kw -> combinedText.contains(kw) }

                    if (matchesKeyword) {
                        repository.captureAndIndexUrl(item.link)
                        archivedCount++
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        Result.success()
    }

    private fun fetchAndParseRss(feedUrl: String): List<RssItem> {
        val items = mutableListOf<RssItem>()
        val url = URL(feedUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android; PageVault RSS Engine)")
        }

        connection.inputStream.use { stream ->
            items.addAll(parseXmlStream(stream))
        }
        return items
    }

    private fun parseXmlStream(inputStream: InputStream): List<RssItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        val result = mutableListOf<RssItem>()
        var eventType = parser.eventType
        var insideItem = false
        var title = ""
        var link = ""
        var description = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.lowercase() ?: ""
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (tagName == "item" || tagName == "entry") {
                        insideItem = true
                        title = ""
                        link = ""
                        description = ""
                    } else if (insideItem) {
                        when (tagName) {
                            "title" -> title = parser.nextText().trim()
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                val textLink = runCatching { parser.nextText().trim() }.getOrDefault("")
                                link = if (!href.isNullOrBlank()) href else textLink
                            }
                            "description", "summary", "content" -> {
                                description = runCatching { parser.nextText().trim() }.getOrDefault("")
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((tagName == "item" || tagName == "entry") && insideItem) {
                        if (link.isNotBlank()) {
                            result.add(RssItem(title, link, description))
                        }
                        insideItem = false
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }
}
