package com.local.pocketarchive.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.CookieManager
import androidx.compose.ui.graphics.Color
import com.local.pocketarchive.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.abs

data class SavedSiteCredential(
    val domain: String,
    val username: String,
    val password: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class SiteBrandColors(
    val cardBackground: Color,
    val borderAccent: Color,
    val badgeBackground: Color,
    val badgeText: Color
)

object AppUiPreferences {
    private const val PREFS_NAME = "pagevault_ui_prefs"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_THEME = "theme_mode"

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.CYBER)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _hapticsEnabled.value = prefs.getBoolean(KEY_HAPTICS, true)
        val savedTheme = prefs.getString(KEY_THEME, ThemeMode.CYBER.name)
        _themeMode.value = runCatching { ThemeMode.valueOf(savedTheme!!) }
            .getOrDefault(ThemeMode.CYBER)
    }

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        _hapticsEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HAPTICS, enabled).apply()
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        _themeMode.value = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, mode.name).apply()
    }

    fun triggerHaptic(view: View, strong: Boolean = false) {
        if (!_hapticsEnabled.value) return
        val feedback = if (strong) {
            HapticFeedbackConstants.LONG_PRESS
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(feedback)
    }
}

object DomainBrandPalette {
    fun resolveForSite(url: String, siteName: String): SiteBrandColors {
        val key = "${url.lowercase()} ${siteName.lowercase()}"
        return when {
            // Tampa Bay Times -> Signature Forest / Emerald Green
            key.contains("tampabay") || key.contains("tampa bay") -> SiteBrandColors(
                cardBackground = Color(0xFF07261A),
                borderAccent = Color(0xFF00C875),
                badgeBackground = Color(0xFF006B3F),
                badgeText = Color(0xFFE6FFF2)
            )
            // Politico -> Signature Politico Red
            key.contains("politico") -> SiteBrandColors(
                cardBackground = Color(0xFF2B090E),
                borderAccent = Color(0xFFFF2A39),
                badgeBackground = Color(0xFFD62828),
                badgeText = Color(0xFFFFEAEC)
            )
            // The Verge -> Signature Electric Magenta / Coral
            key.contains("theverge") || key.contains("verge") -> SiteBrandColors(
                cardBackground = Color(0xFF270A1B),
                borderAccent = Color(0xFFF43F5E),
                badgeBackground = Color(0xFFBE123C),
                badgeText = Color(0xFFFFE4E6)
            )
            // NBC News -> Peacock Blue
            key.contains("nbcnews") || key.contains("nbc") -> SiteBrandColors(
                cardBackground = Color(0xFF071E30),
                borderAccent = Color(0xFF00B4D8),
                badgeBackground = Color(0xFF0284C7),
                badgeText = Color(0xFFE0F2FE)
            )
            // Washington Post / WSJ / NYT -> Editorial Royal Blue & Slate
            key.contains("washingtonpost") || key.contains("nytimes") || key.contains("wsj") -> SiteBrandColors(
                cardBackground = Color(0xFF0B162C),
                borderAccent = Color(0xFF3B82F6),
                badgeBackground = Color(0xFF1D4ED8),
                badgeText = Color(0xFFDBEAFE)
            )
            // Wired / Ars Technica -> Cyber Amber / Orange
            key.contains("wired") || key.contains("arstechnica") -> SiteBrandColors(
                cardBackground = Color(0xFF261205),
                borderAccent = Color(0xFFFF6B00),
                badgeBackground = Color(0xFFC2410C),
                badgeText = Color(0xFFFFEDD5)
            )
            // The Atlantic / New Yorker / Daily Beast -> Crimson / Burgundy
            key.contains("theatlantic") || key.contains("newyorker") || key.contains("thedailybeast") -> SiteBrandColors(
                cardBackground = Color(0xFF280B14),
                borderAccent = Color(0xFFE11D48),
                badgeBackground = Color(0xFF9F1239),
                badgeText = Color(0xFFFFE4E6)
            )
            // Boston Globe / LA Times / Seattle Times -> Warm Gold / Bronze
            key.contains("bostonglobe") || key.contains("latimes") || key.contains("seattletimes") -> SiteBrandColors(
                cardBackground = Color(0xFF241906),
                borderAccent = Color(0xFFF59E0B),
                badgeBackground = Color(0xFFB45309),
                badgeText = Color(0xFFFEF3C7)
            )
            // Scientific American / Foreign Policy / Business Insider -> Teal / Indigo
            key.contains("scientificamerican") || key.contains("foreignpolicy") || key.contains("businessinsider") -> SiteBrandColors(
                cardBackground = Color(0xFF092228),
                borderAccent = Color(0xFF14B8A6),
                badgeBackground = Color(0xFF0F766E),
                badgeText = Color(0xFFCCFBF1)
            )
            else -> {
                // Deterministic vibrant brand tint for any other domain
                val palettes = listOf(
                    SiteBrandColors(Color(0xFF1A0E2E), Color(0xFFA855F7), Color(0xFF6B21A8), Color(0xFFF3E8FF)),
                    SiteBrandColors(Color(0xFF08222B), Color(0xFF00F0FF), Color(0xFF0E7490), Color(0xFFCFFAFE)),
                    SiteBrandColors(Color(0xFF09261E), Color(0xFF10B981), Color(0xFF047857), Color(0xFFD1FAE5)),
                    SiteBrandColors(Color(0xFF290D1B), Color(0xFFEC4899), Color(0xFF9D174D), Color(0xFFFCE7F3))
                )
                palettes[abs(siteName.ifBlank { url }.hashCode()) % palettes.size]
            }
        }
    }
}

object SiteCredentialManager {
    private const val KEYSTORE_ALIAS = "PageVaultCredentialKey"
    private const val PREFS_NAME = "pagevault_secure_creds"
    private const val KEY_PAYLOAD = "encrypted_creds_json"

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existingKey = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) return existingKey.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + encrypted.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val ivSize = combined[0].toInt()
        val iv = combined.copyOfRange(1, 1 + ivSize)
        val encrypted = combined.copyOfRange(1 + ivSize, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun getSavedCredentials(context: Context): List<SavedSiteCredential> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PAYLOAD, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(decrypt(raw))
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        SavedSiteCredential(
                            domain = obj.optString("domain"),
                            username = obj.optString("username"),
                            password = obj.optString("password"),
                            updatedAtEpochMs = obj.optLong("updatedAtEpochMs", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveCredential(context: Context, domain: String, username: String, password: String) {
        val cleanDomain = domain.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore("/")
        if (cleanDomain.isBlank() || username.isBlank()) return

        val current = getSavedCredentials(context)
            .filterNot { it.domain.equals(cleanDomain, ignoreCase = true) }
            .toMutableList()
        current.add(0, SavedSiteCredential(cleanDomain, username.trim(), password, System.currentTimeMillis()))
        persistList(context, current)
    }

    fun removeCredential(context: Context, domain: String) {
        val updated = getSavedCredentials(context).filterNot { it.domain.equals(domain, ignoreCase = true) }
        persistList(context, updated)
    }

    fun clearAllCredentialsAndSessions(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    fun findCredentialForUrl(context: Context, url: String): SavedSiteCredential? {
        val lower = url.lowercase()
        return getSavedCredentials(context).firstOrNull { lower.contains(it.domain.lowercase()) }
    }

    private fun persistList(context: Context, list: List<SavedSiteCredential>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("domain", item.domain)
                    put("username", item.username)
                    put("password", item.password)
                    put("updatedAtEpochMs", item.updatedAtEpochMs)
                }
            )
        }
        val encrypted = encrypt(arr.toString())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PAYLOAD, encrypted).apply()
    }
}
