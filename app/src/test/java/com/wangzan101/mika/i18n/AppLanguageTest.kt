package com.wangzan101.mika.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    @Test
    fun fromTagPreservesNullAndEmptyAsSystem() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag(null) { "Hans" })
        assertEquals(AppLanguage.System, AppLanguage.fromTag("") { "Hans" })
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguage.fromTag(language.tag) { "Hans" })
        }
    }

    @Test
    fun promptLanguageNameForExplicitLanguages() {
        assertEquals("Simplified Chinese", AppLanguage.SimplifiedChinese.promptLanguageName(Locale.US))
        assertEquals("Traditional Chinese", AppLanguage.TraditionalChinese.promptLanguageName(Locale.US))
        assertEquals("Arabic", AppLanguage.Arabic.promptLanguageName(Locale.US))
        assertEquals("Persian", AppLanguage.Persian.promptLanguageName(Locale.US))
        assertEquals("English", AppLanguage.English.promptLanguageName(Locale.SIMPLIFIED_CHINESE))
        assertEquals("Japanese", AppLanguage.Japanese.promptLanguageName(Locale.US))
        assertEquals("Korean", AppLanguage.Korean.promptLanguageName(Locale.US))
        assertEquals("Spanish", AppLanguage.Spanish.promptLanguageName(Locale.US))
        assertEquals("Portuguese", AppLanguage.Portuguese.promptLanguageName(Locale.US))
        assertEquals("Russian", AppLanguage.Russian.promptLanguageName(Locale.US))
        assertEquals("Indonesian", AppLanguage.Indonesian.promptLanguageName(Locale.US))
    }

    @Test
    fun promptLanguageNameResolvesSystemAndFallsBackToEnglish() {
        assertEquals("English", AppLanguage.System.promptLanguageName(Locale.US))
        assertEquals("English", AppLanguage.System.promptLanguageName(Locale.FRANCE))
        assertEquals("English", AppLanguage.System.promptLanguageName(Locale.ROOT))
        assertEquals("Simplified Chinese", AppLanguage.System.promptLanguageName(Locale.SIMPLIFIED_CHINESE))
        assertEquals("Traditional Chinese", AppLanguage.System.promptLanguageName(Locale.TRADITIONAL_CHINESE))
        assertEquals("Japanese", AppLanguage.System.promptLanguageName(Locale.JAPANESE))
        assertEquals("Korean", AppLanguage.System.promptLanguageName(Locale.KOREAN))
        assertEquals("Indonesian", AppLanguage.System.promptLanguageName(Locale.forLanguageTag("in-ID")))
        assertEquals("Traditional Chinese", AppLanguage.System.promptLanguageName(Locale.forLanguageTag("zh-HK")) { "Hant" })
    }

    @Test
    fun ensureDefaultLanguageAppliesEnglishOnceOnLegacyFreshLaunch() {
        var initialized = false
        var savedTag: String? = null
        var hasSaved = false

        val firstRun = AppLanguage.ensureDefaultLanguage(
            initialized = initialized,
            systemManaged = false,
            hasSavedLegacyTag = { hasSaved },
            hasSystemLocales = { false },
            saveLegacyDefault = { tag ->
                savedTag = tag
                hasSaved = true
                initialized = true
            },
            setSystemDefault = { error("unexpected system update on legacy API") },
            markInitialized = { initialized = true },
        )
        assertTrue(firstRun)
        assertTrue(initialized)
        assertEquals("en", savedTag)

        // Switching back to Follow system clears the tag while keeping the initialization marker.
        savedTag = null
        hasSaved = false
        val secondRun = AppLanguage.ensureDefaultLanguage(
            initialized = initialized,
            systemManaged = false,
            hasSavedLegacyTag = { hasSaved },
            hasSystemLocales = { false },
            saveLegacyDefault = { savedTag = it },
            setSystemDefault = { error("unexpected system update on legacy API") },
            markInitialized = { initialized = true },
        )
        assertFalse(secondRun)
        assertNull(savedTag)
    }

    @Test
    fun ensureDefaultLanguagePreservesExistingLegacySelectionOnUpgrade() {
        var initialized = false
        var savedTag: String? = "ja"

        val upgraded = AppLanguage.ensureDefaultLanguage(
            initialized = initialized,
            systemManaged = false,
            hasSavedLegacyTag = { true },
            hasSystemLocales = { false },
            saveLegacyDefault = { savedTag = it },
            setSystemDefault = { error("unexpected system update on legacy API") },
            markInitialized = { initialized = true },
        )
        assertFalse(upgraded)
        assertTrue(initialized)
        assertEquals("ja", savedTag)
    }

    @Test
    fun ensureDefaultLanguageAppliesEnglishOnceOnSystemManagedFreshLaunch() {
        var initialized = false
        var systemTag: String? = null

        val firstRun = AppLanguage.ensureDefaultLanguage(
            initialized = initialized,
            systemManaged = true,
            hasSavedLegacyTag = { false },
            hasSystemLocales = { systemTag != null },
            saveLegacyDefault = { error("unexpected legacy write on system-managed API") },
            setSystemDefault = { systemTag = it },
            markInitialized = { initialized = true },
        )
        assertTrue(firstRun)
        assertTrue(initialized)
        assertEquals("en", systemTag)

        // User explicitly selects Follow system; subsequent launches keep it unset.
        systemTag = null
        val secondRun = AppLanguage.ensureDefaultLanguage(
            initialized = initialized,
            systemManaged = true,
            hasSavedLegacyTag = { false },
            hasSystemLocales = { systemTag != null },
            saveLegacyDefault = { error("unexpected legacy write on system-managed API") },
            setSystemDefault = { systemTag = it },
            markInitialized = { initialized = true },
        )
        assertFalse(secondRun)
        assertNull(systemTag)
    }

    @Test
    fun ensureDefaultLanguagePreservesExistingSystemManagedLocale() {
        var initialized = false
        var systemTag = "zh-Hans"

        val upgraded = AppLanguage.ensureDefaultLanguage(
            initialized = initialized,
            systemManaged = true,
            hasSavedLegacyTag = { false },
            hasSystemLocales = { true },
            saveLegacyDefault = { error("unexpected legacy write on system-managed API") },
            setSystemDefault = { systemTag = it },
            markInitialized = { initialized = true },
        )
        assertFalse(upgraded)
        assertTrue(initialized)
        assertEquals("zh-Hans", systemTag)
    }
}
