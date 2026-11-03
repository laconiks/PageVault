package com.local.pocketarchive.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.local.pocketarchive.ai.AiEdgeModel
import com.local.pocketarchive.ai.EdgeModelOption
import com.local.pocketarchive.capture.PaywallBypassHelper
import com.local.pocketarchive.data.local.ArchivedPage
import com.local.pocketarchive.data.local.CaptureStatus
import com.local.pocketarchive.data.local.RssSubscription
import com.local.pocketarchive.ui.theme.ThemeMode
import com.local.pocketarchive.util.AppUiPreferences
import com.local.pocketarchive.util.DomainBrandPalette
import com.local.pocketarchive.util.SiteCredentialManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

fun getDomainAccentColor(siteOrUrl: String): Color {
    val palette = listOf(
        Color(0xFF00E5FF), // Cyan
        Color(0xFFFFB300), // Gold
        Color(0xFF7C4DFF), // Violet
        Color(0xFF00E676), // Matrix Green
        Color(0xFFFF4081), // Neon Pink
        Color(0xFF40C4FF)  // Sky Laser
    )
    val hash = siteOrUrl.lowercase().trim().hashCode().let { if (it < 0) -it else it }
    return palette[hash % palette.size]
}

@Composable
fun GeminiRainbowSpinner(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.5.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "GeminiRainbowSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val rainbowBrush = remember {
        Brush.sweepGradient(
            listOf(
                Color(0xFF4285F4), // Google Blue
                Color(0xFF9B72CB), // Gemini Purple
                Color(0xFFD96570), // Coral Red
                Color(0xFFF4B400), // Amber Yellow
                Color(0xFF4285F4)  // Google Blue
            )
        )
    }

    Canvas(modifier = modifier.rotate(rotation)) {
        val strokePx = strokeWidth.toPx()
        val diameter = size.minDimension - strokePx
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        drawArc(
            brush = rainbowBrush,
            startAngle = 0f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun MarkdownViewerDialog(
    page: ArchivedPage,
    onDismiss: () -> Unit,
    onShareMarkdown: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val localView = LocalView.current
    var copied by remember { mutableStateOf(false) }

    val rawContent = remember(page.extractedTextSummary, page.aiSummary) {
        page.extractedTextSummary.ifBlank { page.aiSummary }.ifBlank { "No text content extracted for this capture." }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Markdown Document",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 460.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = rawContent,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        AppUiPreferences.triggerHaptic(localView)
                        clipboardManager.setText(AnnotatedString(rawContent))
                        copied = true
                    }
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (copied) "Copied!" else "Copy Text")
                }
                Button(
                    onClick = {
                        AppUiPreferences.triggerHaptic(localView)
                        onShareMarkdown()
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share .md")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    AppUiPreferences.triggerHaptic(localView)
                    onDismiss()
                }
            ) {
                Text("Close")
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InteractiveSiteLoginDialog(
    initialDomain: String = "",
    onDismiss: () -> Unit,
    onLoginSaved: () -> Unit
) {
    val context = LocalContext.current
    val localView = LocalView.current
    var domainInput by remember { mutableStateOf(initialDomain) }
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var targetUrl by remember {
        mutableStateOf(if (initialDomain.isNotBlank()) "https://$initialDomain" else "https://")
    }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Subscriber Login & Cookie Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Log in once. PageVault saves your session & cookies locally.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text("Domain (e.g. nytimes.com)") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f)
                    )
                    Button(
                        onClick = {
                            AppUiPreferences.triggerHaptic(localView)
                            val clean = domainInput.trim().removePrefix("https://").removePrefix("http://")
                            val newUrl = if (clean.startsWith("http")) clean else "https://$clean"
                            targetUrl = newUrl
                            webViewInstance?.loadUrl(newUrl)
                        },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Text("Go")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text("Username / Email") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewInstance = this
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    userAgentString = PaywallBypassHelper.CHROME_MOBILE_USER_AGENT
                                }
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        CookieManager.getInstance().flush()
                                    }
                                }
                                if (targetUrl.isNotBlank() && targetUrl != "https://") {
                                    loadUrl(targetUrl)
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            AppUiPreferences.triggerHaptic(localView)
                            val cleanDomain = domainInput.trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
                            if (cleanDomain.isNotBlank()) {
                                if (usernameInput.isNotBlank()) {
                                    SiteCredentialManager.saveCredential(context, cleanDomain, usernameInput, passwordInput)
                                }
                                CookieManager.getInstance().flush()
                                onLoginSaved()
                            }
                            onDismiss()
                        }
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Session & Close")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun VaultArticleCard(
    page: ArchivedPage,
    revisions: List<ArchivedPage> = emptyList(),
    isSelected: Boolean,
    captureStageText: String = "Step 1/4: Connecting & bypassing paywall...",
    onCardClick: () -> Unit,
    onCardLongClick: () -> Unit,
    onExportPdf: () -> Unit,
    onDelete: () -> Unit,
    onRecaptureNoJs: (() -> Unit)? = null,
    onExportMarkdown: (() -> Unit)? = null,
    onOpenMarkdown: ((ArchivedPage) -> Unit)? = null,
    onRepull: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val localView = LocalView.current
    var expanded by remember { mutableStateOf(false) }
    var showRevisionsAccordion by remember { mutableStateOf(false) }

    val domainLabel = remember(page.siteName, page.originalUrl) {
        page.siteName.ifBlank {
            runCatching { Uri.parse(page.originalUrl).host?.removePrefix("www.") ?: "Web" }.getOrDefault("Web")
        }
    }
    val brandColors = remember(page.originalUrl, page.siteName) {
        DomainBrandPalette.resolveForSite(page.originalUrl, page.siteName)
    }
    val secondsStr = remember(page.captureDurationMs) {
        if (page.captureDurationMs > 0) {
            String.format(Locale.US, "⏱ %.1fs", page.captureDurationMs / 1000.0)
        } else {
            "⏱ Instant"
        }
    }
    val dateFormatted = remember(page.timestampEpochMillis) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(page.timestampEpochMillis))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = {
                    AppUiPreferences.triggerHaptic(localView)
                    onCardClick()
                },
                onLongClick = {
                    AppUiPreferences.triggerHaptic(localView, strong = true)
                    onCardLongClick()
                }
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else brandColors.cardBackground,
        tonalElevation = 4.dp,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            brush = Brush.horizontalGradient(
                colors = if (isSelected) listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                else listOf(brandColors.borderAccent.copy(alpha = 0.85f), brandColors.borderAccent.copy(alpha = 0.3f))
            )
        )
    ) {
        Column {
            // Restore Wide Article Preview Banner Image (155.dp full-width)
            if (!page.screenshotFilePath.isNullOrBlank() && File(page.screenshotFilePath).exists()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(155.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AsyncImage(
                        model = File(page.screenshotFilePath),
                        contentDescription = "Article Preview",
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Gradient bottom scrim blending into brand card background
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        brandColors.cardBackground.copy(alpha = 0.6f),
                                        brandColors.cardBackground
                                    ),
                                    startY = 60f
                                )
                            )
                    )
                }
            }

            Column(modifier = Modifier.padding(14.dp)) {
                // Top Header Row: Brand Badge + Generation Time in Seconds + Version + Date
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp).padding(end = 6.dp)
                            )
                        }
                        Surface(
                            color = brandColors.badgeBackground,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.5.dp, brandColors.borderAccent)
                        ) {
                            Text(
                                text = domainLabel.uppercase(),
                                color = brandColors.badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Generation Duration Badge in Seconds
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = secondsStr,
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                        if (page.versionNumber > 1) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "v${page.versionNumber} REVISION",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = dateFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Title and Original URL
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) 5 else 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Clickable Original URL -> Opens directly in Google Chrome / Browser
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            runCatching {
                                val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(page.originalUrl)).apply {
                                    setPackage("com.android.chrome")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try {
                                    context.startActivity(chromeIntent)
                                } catch (_: Exception) {
                                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(page.originalUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(fallbackIntent)
                                }
                            }
                        }
                        .padding(vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = "Open in Chrome",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = page.originalUrl,
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Dynamic Plain-English Capture Status with Helper Subtitle
                if (page.status == CaptureStatus.PENDING) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GeminiRainbowSpinner(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = captureStageText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "DOM locking freezes all styles, scripts, and media into an RFC-compliant snapshot for 100% offline access with zero server dependency.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val summaryPrefix = buildString {
                        if (domainLabel.isNotBlank()) append(domainLabel)
                        if (page.author.isNotBlank()) append(" • ${page.author}")
                        if (isNotEmpty()) append(" — ")
                    }
                    val bodySummary = page.aiSummary.ifBlank { page.extractedTextSummary }
                    if (bodySummary.isNotBlank()) {
                        Text(
                            text = "$summaryPrefix$bodySummary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) 12 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (page.changeDiffSummary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CompareArrows,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Diff: ${page.changeDiffSummary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = if (expanded) 4 else 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Collapsed Version History & AI Diff Accordion (Revisions grouped inside main card)
                if (revisions.size > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRevisionsAccordion = !showRevisionsAccordion }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "🕒 Version History & AI Diff (${revisions.size} Versions)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                imageVector = if (showRevisionsAccordion) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (showRevisionsAccordion) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            revisions.forEach { rev ->
                                val isCurrent = rev.uuid == page.uuid
                                val revDate = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(rev.timestampEpochMillis))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        0.5.dp,
                                        if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "v${rev.versionNumber}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                if (isCurrent) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "• CURRENT",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            Text(
                                                text = revDate,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (rev.changeDiffSummary.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "AI Diff vs previous: ${rev.changeDiffSummary}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    val intent = Intent(context, com.local.pocketarchive.ui.OfflineMhtmlViewerActivity::class.java).apply {
                                                        putExtra("MHTML_PATH", rev.mhtmlFilePath)
                                                        putExtra("SCREENSHOT_PATH", rev.screenshotFilePath)
                                                        putExtra("PAGE_TITLE", rev.title)
                                                        putExtra("ORIGINAL_URL", rev.originalUrl)
                                                    }
                                                    context.startActivity(intent)
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("View Snapshot", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // AI Tags
                if (page.aiTags.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        page.aiTags.take(4).forEach { tag ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = CircleShape
                            ) {
                                Text(
                                    text = "#$tag",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Clear, Self-Explanatory Action Pills Row
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 📄 PDF Snapshot Pill
                    Surface(
                        onClick = onExportPdf,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    "📄 PDF Snapshot",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Printable layout",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 📝 Markdown Pill (Opens formatted viewer + copy/share)
                    if (onOpenMarkdown != null || onExportMarkdown != null) {
                        Surface(
                            onClick = {
                                AppUiPreferences.triggerHaptic(localView)
                                if (onOpenMarkdown != null) {
                                    onOpenMarkdown(page)
                                } else {
                                    onExportMarkdown?.invoke()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        "📝 Markdown",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "View formatted text",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // 🔄 Pull v$nextVersion Pill
                    if (onRepull != null) {
                        val nextV = (revisions.maxOfOrNull { it.versionNumber } ?: page.versionNumber) + 1
                        Surface(
                            onClick = onRepull,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "🔄 Pull v$nextV",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // ⚡ No-JS Pill
                    if (onRecaptureNoJs != null) {
                        Surface(
                            onClick = onRecaptureNoJs,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.FlashOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "⚡ No-JS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Expand / Collapse Summary toggle
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Expand Summary",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Delete Pill
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Global 1-Sentence Compact View Row:
 * Collapses the article card into a sleek 1-sentence headline + summary row
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultCompactRow(
    page: ArchivedPage,
    isSelected: Boolean,
    onCardClick: () -> Unit,
    onCardLongClick: () -> Unit,
    onExportPdf: () -> Unit,
    onExportMarkdown: (() -> Unit)? = null,
    onOpenMarkdown: ((ArchivedPage) -> Unit)? = null,
    onDelete: () -> Unit
) {
    val localView = LocalView.current
    val domainLabel = remember(page.siteName, page.originalUrl) {
        page.siteName.ifBlank {
            runCatching { Uri.parse(page.originalUrl).host?.removePrefix("www.") ?: "Web" }.getOrDefault("Web")
        }
    }
    val brandColors = remember(page.originalUrl, page.siteName) {
        DomainBrandPalette.resolveForSite(page.originalUrl, page.siteName)
    }
    val oneSentenceSummary = remember(page.aiSummary, page.extractedTextSummary) {
        val raw = page.aiSummary.ifBlank { page.extractedTextSummary }
        val firstSentence = raw.split(Regex("(?<=[.!?])\\s+")).firstOrNull()?.trim().orEmpty()
        if (firstSentence.isNotBlank()) firstSentence else "Saved offline page."
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {
                    AppUiPreferences.triggerHaptic(localView)
                    onCardClick()
                },
                onLongClick = {
                    AppUiPreferences.triggerHaptic(localView, strong = true)
                    onCardLongClick()
                }
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else brandColors.cardBackground,
        tonalElevation = 2.dp,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 0.8.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else brandColors.borderAccent.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = brandColors.borderAccent,
                modifier = Modifier.size(10.dp)
            ) {}
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = domainLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.ExtraBold,
                        color = brandColors.borderAccent
                    )
                    if (page.versionNumber > 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "v${page.versionNumber}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = oneSentenceSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        AppUiPreferences.triggerHaptic(localView)
                        onExportPdf()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = "PDF", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                if (onOpenMarkdown != null || onExportMarkdown != null) {
                    IconButton(
                        onClick = {
                            AppUiPreferences.triggerHaptic(localView)
                            if (onOpenMarkdown != null) {
                                onOpenMarkdown(page)
                            } else {
                                onExportMarkdown?.invoke()
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = "Markdown", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(
                    onClick = {
                        AppUiPreferences.triggerHaptic(localView)
                        onDelete()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun RssScraperManagerDialog(
    subscriptions: List<RssSubscription>,
    onAddSubscription: (String, String) -> Unit,
    onDeleteSubscription: (RssSubscription) -> Unit,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit
) {
    var feedUrl by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RssFeed, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("RSS Keyword Auto-Vault", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Automatically scrape & archive articles from RSS/Atom feeds matching your keywords in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = feedUrl,
                    onValueChange = { feedUrl = it },
                    label = { Text("RSS Feed URL (e.g. feeds.bbci.co.uk/news/rss.xml)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("Keywords (comma-separated, blank = all)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (feedUrl.isNotBlank()) {
                            onAddSubscription(feedUrl, keywords)
                            feedUrl = ""
                            keywords = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Auto-Scraper Feed")
                }

                if (subscriptions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Active Feeds (${subscriptions.size})", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                        items(subscriptions, key = { it.id }) { sub ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sub.feedUrl, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = if (sub.keywordsCsv.isBlank()) "Keywords: ALL" else "Keywords: ${sub.keywordsCsv}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { onDeleteSubscription(sub) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSyncNow) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sync Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun SettingsAndAboutDialog(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    availableModels: List<EdgeModelOption> = emptyList(),
    selectedModelId: String = "gemma3n_2b",
    onSelectAiEdgeModel: (String, String?) -> Unit = { _, _ -> },
    currentModel: AiEdgeModel = AiEdgeModel.GEMMA_2B,
    onModelSelected: (AiEdgeModel) -> Unit = {},
    onExportDiagnosticLog: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var customPathInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("WebVaultr Settings & About", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Appearance Theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = currentTheme == mode,
                            onClick = { onThemeSelected(mode) },
                            label = { Text(mode.name) }
                        )
                    }
                }
                HorizontalDivider()

                // Master Haptic Feedback Toggle
                val context = LocalContext.current
                val localView = LocalView.current
                val hapticsEnabled by AppUiPreferences.hapticsEnabled.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Global Haptic Feedback", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Tactile responses for taps, gestures, and button clicks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = hapticsEnabled,
                        onCheckedChange = {
                            AppUiPreferences.setHapticsEnabled(context, it)
                            AppUiPreferences.triggerHaptic(localView)
                        }
                    )
                }
                HorizontalDivider()

                // Subscriber Credentials & Cookie Session Manager (AES-256)
                var savedCreds by remember { mutableStateOf(SiteCredentialManager.getSavedCredentials(context)) }
                var showLoginDialog by remember { mutableStateOf(false) }

                Text("Subscriber Credentials & Sessions (AES-256)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Log into publications to sync cookies and capture paywalled articles locally.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (savedCreds.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        savedCreds.forEach { cred ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(cred.domain, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Text("User: ${cred.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(
                                        onClick = {
                                            AppUiPreferences.triggerHaptic(localView)
                                            SiteCredentialManager.removeCredential(context, cred.domain)
                                            savedCreds = SiteCredentialManager.getSavedCredentials(context)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            AppUiPreferences.triggerHaptic(localView)
                            showLoginDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("+ Add Site Login")
                    }
                    OutlinedButton(
                        onClick = {
                            AppUiPreferences.triggerHaptic(localView)
                            SiteCredentialManager.clearAllCredentialsAndSessions(context)
                            savedCreds = emptyList()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Clear Sessions")
                    }
                }

                if (showLoginDialog) {
                    InteractiveSiteLoginDialog(
                        onDismiss = { showLoginDialog = false },
                        onLoginSaved = {
                            savedCreds = SiteCredentialManager.getSavedCredentials(context)
                        }
                    )
                }
                HorizontalDivider()
                Text("Google AI Edge Model Selector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (availableModels.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableModels.forEach { option ->
                            val isSelected = option.id == selectedModelId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectAiEdgeModel(option.id, null) }
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = option.displayName,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Active",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = option.recommendationBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (option.isAvailableOnDisk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                        if (selectedModelId == "custom_path") {
                            OutlinedTextField(
                                value = customPathInput,
                                onValueChange = {
                                    customPathInput = it
                                    onSelectAiEdgeModel("custom_path", it)
                                },
                                label = { Text("Custom Model Path (.task / .litertlm)") },
                                placeholder = { Text("/sdcard/Download/model.task") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiEdgeModel.entries.forEach { model ->
                            FilterChip(
                                selected = currentModel == model,
                                onClick = { onModelSelected(model) },
                                label = { Text(model.displayName) }
                            )
                        }
                    }
                }
                HorizontalDivider()
                // 100% Local / Manual RSS Privacy Ethos Banner
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "100% Local / Manual RSS Privacy Ethos",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Zero external analytics, zero cloud telemetry. All article parsing, DOM unmasking, MHTML snapshots, OCR, and AI summaries run entirely on your device with complete user sovereignty.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                HorizontalDivider()
                Text("About WebVaultr v0.8.1", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "WebVaultr is a private, local-first web preservation and on-device AI archival engine. Captures RFC-compliant MHTML snapshots, full-height screenshots, ML Kit OCR, and LiteRT edge summaries with zero cloud dependencies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Architected & Engineered by:", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "@laconiks",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "Feedback & Contact: laconik@gmail.com",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "github.com/laconiks/webvaultr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
                Text("Diagnostics & Troubleshooting", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Export on-device troubleshooting logs containing device hardware make/model and capture events with timestamps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onExportDiagnosticLog,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export Troubleshooting Log")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun AiEdgeGalleryOnboardingDialog(
    availableModels: List<EdgeModelOption>,
    selectedModelId: String,
    onSelectModel: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var customPath by remember { mutableStateOf("") }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        "Google AI Edge Onboarding",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "OPEN ANDROID ETHOS • 100% OFFLINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Open Android Ethos & Zero-Cloud Guarantee",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "PageVault analyzes, summarizes, and diffs web captures entirely on your device using LiteRT & MediaPipe LLM. No personal reading data or tokens ever leave your hardware.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Recommended Models:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = {
                            uriHandler.openUri("https://ai.google.dev/edge")
                        }
                    ) {
                        Text("AI Edge Gallery ↗", style = MaterialTheme.typography.labelSmall)
                    }
                }

                availableModels.forEach { option ->
                    val isSelected = option.id == selectedModelId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 0.5.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectModel(option.id, null) }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = option.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Active",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = option.recommendationBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (option.isAvailableOnDisk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                            )
                            if (option.filePath != null && option.filePath != "built_in") {
                                Text(
                                    text = "Path: ${option.filePath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (selectedModelId == "custom_path") {
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = {
                            customPath = it
                            onSelectModel("custom_path", it)
                        },
                        label = { Text("Model File Absolute Path") },
                        placeholder = { Text("/sdcard/Download/model.task") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Confirm & Launch Vault")
            }
        }
    )
}