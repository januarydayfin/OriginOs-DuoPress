package com.krayapp.duopress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutActionTest {
    @Test
    fun simpleRoundTrip() {
        val action = ShortcutAction("com.example.app", "main", "Main")

        val parsed = requireNotNull(ShortcutAction.parse(action.toActionId()))

        assertEquals(action, parsed)
        assertEquals("com.example.app", parsed.packageName)
        assertEquals("main", parsed.shortcutId)
        assertEquals("Main", parsed.label)
    }

    @Test
    fun roundTripPreservesSpecialCharactersInAllFields() {
        val action = ShortcutAction("ru.пример app:+:%:", "ид быстрый:+:%:", "Оплата + 100% : наличные")

        val actionId = action.toActionId()

        assertEquals(action, ShortcutAction.parse(actionId))
        assertTrue(actionId.contains("%2B"))
        assertTrue(actionId.contains("%25"))
        assertTrue(actionId.contains("%3A"))
        assertTrue(actionId.contains("+"))
    }

    @Test
    fun emptyAndNullLabelsAreNormalizedAndRoundTrip() {
        val emptyLabel = ShortcutAction("com.example.app", "main", "")
        val nullLabel = ShortcutAction("com.example.app", "main", null)

        assertEquals("", nullLabel.label)
        assertEquals("shortcut:com.example.app:main:", emptyLabel.toActionId())
        assertEquals(emptyLabel, ShortcutAction.parse(emptyLabel.toActionId()))
        assertEquals(emptyLabel, nullLabel)
    }

    @Test
    fun constructorRejectsBlankPackageOrShortcutId() {
        assertInvalid(null, "id")
        assertInvalid("", "id")
        assertInvalid(" \t\n", "id")
        assertInvalid("package", null)
        assertInvalid("package", "")
        assertInvalid("package", " \t\n")
    }

    @Test
    fun constructorPreservesIdentityWhitespace() {
        val action = ShortcutAction(" package ", " id ", null)

        assertEquals(" package ", action.packageName)
        assertEquals(" id ", action.shortcutId)
        assertEquals(action, ShortcutAction.parse(action.toActionId()))
    }

    @Test
    fun encodedSpacesAreNotTrimmedFromIdentity() {
        val wireId = "shortcut:+com.example.app+:%C2%A0:a%2Bb%25%3A"
        val parsed = requireNotNull(ShortcutAction.parse(wireId))

        assertEquals(" com.example.app ", parsed.packageName)
        assertEquals("\u00a0", parsed.shortcutId)
        assertEquals("a+b%:", parsed.label)
        assertEquals(wireId, parsed.toActionId())
    }

    @Test
    fun parsesRawUnicodeAndTrailingEmptyLabel() {
        val parsed = requireNotNull(ShortcutAction.parse("shortcut:com.example.猫:\u2003:"))

        assertEquals("com.example.猫", parsed.packageName)
        assertEquals("\u2003", parsed.shortcutId)
        assertEquals("", parsed.label)
        assertEquals("shortcut:com.example.%E7%8C%AB:%E2%80%83:", parsed.toActionId())
    }

    @Test
    fun legacyAsciiTrimRejectsControlOnlyIdentity() {
        assertInvalid("\u0000\u001f ", "id")
        assertNull(ShortcutAction.parse("shortcut:package:%00%1F+:"))
    }

    @Test
    fun equalityAndHashCodeUseAllThreeFields() {
        val action = ShortcutAction("pkg", "id", "label")
        val equivalent = requireNotNull(ShortcutAction.parse("shortcut:pkg:id:label"))

        assertEquals(action, equivalent)
        assertEquals(209552389, action.hashCode())
        assertEquals(action.hashCode(), equivalent.hashCode())
        assertFalse(action == ShortcutAction("other", "id", "label"))
        assertFalse(action == ShortcutAction("pkg", "other", "label"))
        assertFalse(action == ShortcutAction("pkg", "id", "other"))
    }

    @Test
    fun isActionChecksShortcutPrefix() {
        assertTrue(ShortcutAction.isAction("shortcut:package:id:label"))
        assertTrue(ShortcutAction.isAction("shortcut:"))
        assertFalse(ShortcutAction.isAction(null))
        assertFalse(ShortcutAction.isAction(""))
        assertFalse(ShortcutAction.isAction("com.example.app"))
        assertFalse(ShortcutAction.isAction("Shortcut:package:id:label"))
    }

    @Test
    fun parseRejectsNullAndOtherPrefixes() {
        assertNull(ShortcutAction.parse(null))
        assertNull(ShortcutAction.parse(""))
        assertNull(ShortcutAction.parse("com.example.app"))
        assertNull(ShortcutAction.parse("Shortcut:package:id:label"))
        assertNull(ShortcutAction.parse("special:package:id:label"))
    }

    @Test
    fun parseRejectsMissingAndExtraFields() {
        assertNull(ShortcutAction.parse("shortcut:"))
        assertNull(ShortcutAction.parse("shortcut:package"))
        assertNull(ShortcutAction.parse("shortcut:package:id"))
        assertNull(ShortcutAction.parse("shortcut:package:id:label:extra"))
    }

    @Test
    fun parseRejectsEmptyOrBlankPackageAndShortcutId() {
        assertNull(ShortcutAction.parse("shortcut::id:label"))
        assertNull(ShortcutAction.parse("shortcut:package::label"))
        assertNull(ShortcutAction.parse("shortcut:%20:id:label"))
        assertNull(ShortcutAction.parse("shortcut:package:%09:label"))
    }

    @Test
    fun parseRejectsMalformedPercentEncoding() {
        assertNull(ShortcutAction.parse("shortcut:%:id:label"))
        assertNull(ShortcutAction.parse("shortcut:package:id%GG:label"))
        assertNull(ShortcutAction.parse("shortcut:package:id:%A"))
    }

    @Test
    fun toActionIdIsCanonicalAndStable() {
        val action = ShortcutAction("com.example.пакет", "id: +%", "Ярлык + 100%: тест")

        val canonical = action.toActionId()
        val parsed = requireNotNull(ShortcutAction.parse(canonical))

        assertEquals(canonical, parsed.toActionId())
        assertEquals(canonical, requireNotNull(ShortcutAction.parse(parsed.toActionId())).toActionId())
        assertEquals(
            "shortcut:com.example.app:main:Label",
            requireNotNull(ShortcutAction.parse("shortcut:com%2eexample%2eapp:main:Label")).toActionId(),
        )
    }

    @Test
    fun kotlinContractAcceptsNamedNullableArgumentsAndExposesNonNullProperties() {
        val action = ShortcutAction(packageName = "com.example.app", shortcutId = "a+b", label = null)
        val packageName: String = action.packageName
        val shortcutId: String = action.shortcutId
        val label: String = action.label

        assertEquals("com.example.app", packageName)
        assertEquals("a+b", shortcutId)
        assertEquals("", label)
        assertEquals("shortcut:com.example.app:a%2Bb:", action.toActionId())
    }

    private fun assertInvalid(packageName: String?, shortcutId: String?) {
        assertThrows(IllegalArgumentException::class.java) {
            ShortcutAction(packageName, shortcutId, "label")
        }
    }
}
