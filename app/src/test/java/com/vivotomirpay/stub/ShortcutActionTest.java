package com.vivotomirpay.stub;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ShortcutActionTest {

    @Test
    public void simpleRoundTrip() {
        ShortcutAction action = new ShortcutAction("com.example.app", "main", "Main");

        ShortcutAction parsed = ShortcutAction.parse(action.toActionId());

        assertEquals(action, parsed);
        assertEquals("com.example.app", parsed.getPackageName());
        assertEquals("main", parsed.getShortcutId());
        assertEquals("Main", parsed.getLabel());
    }

    @Test
    public void roundTripPreservesSpecialCharactersInAllFields() {
        ShortcutAction action = new ShortcutAction(
                "ru.пример app:+:%:",
                "ид быстрый:+:%:",
                "Оплата + 100% : наличные");

        String actionId = action.toActionId();
        ShortcutAction parsed = ShortcutAction.parse(actionId);

        assertEquals(action, parsed);
        assertTrue(actionId.contains("%2B"));
        assertTrue(actionId.contains("%25"));
        assertTrue(actionId.contains("%3A"));
        assertTrue(actionId.contains("+"));
    }

    @Test
    public void emptyAndNullLabelsAreNormalizedAndRoundTrip() {
        ShortcutAction emptyLabel = new ShortcutAction("com.example.app", "main", "");
        ShortcutAction nullLabel = new ShortcutAction("com.example.app", "main", null);

        assertEquals("", nullLabel.getLabel());
        assertEquals("shortcut:com.example.app:main:", emptyLabel.toActionId());
        assertEquals(emptyLabel, ShortcutAction.parse(emptyLabel.toActionId()));
        assertEquals(emptyLabel, nullLabel);
    }

    @Test
    public void constructorRejectsBlankPackageOrShortcutId() {
        assertInvalid(null, "id");
        assertInvalid("", "id");
        assertInvalid(" \t\n", "id");
        assertInvalid("package", null);
        assertInvalid("package", "");
        assertInvalid("package", " \t\n");
    }

    @Test
    public void constructorPreservesIdentityWhitespace() {
        ShortcutAction action = new ShortcutAction(" package ", " id ", null);

        assertEquals(" package ", action.getPackageName());
        assertEquals(" id ", action.getShortcutId());
        assertEquals(action, ShortcutAction.parse(action.toActionId()));
    }

    @Test
    public void isActionChecksShortcutPrefix() {
        assertTrue(ShortcutAction.isAction("shortcut:package:id:label"));
        assertTrue(ShortcutAction.isAction("shortcut:"));
        assertFalse(ShortcutAction.isAction(null));
        assertFalse(ShortcutAction.isAction(""));
        assertFalse(ShortcutAction.isAction("com.example.app"));
        assertFalse(ShortcutAction.isAction("Shortcut:package:id:label"));
    }

    @Test
    public void parseRejectsNullAndOtherPrefixes() {
        assertNull(ShortcutAction.parse(null));
        assertNull(ShortcutAction.parse(""));
        assertNull(ShortcutAction.parse("com.example.app"));
        assertNull(ShortcutAction.parse("Shortcut:package:id:label"));
        assertNull(ShortcutAction.parse("special:package:id:label"));
    }

    @Test
    public void parseRejectsMissingAndExtraFields() {
        assertNull(ShortcutAction.parse("shortcut:"));
        assertNull(ShortcutAction.parse("shortcut:package"));
        assertNull(ShortcutAction.parse("shortcut:package:id"));
        assertNull(ShortcutAction.parse("shortcut:package:id:label:extra"));
    }

    @Test
    public void parseRejectsEmptyOrBlankPackageAndShortcutId() {
        assertNull(ShortcutAction.parse("shortcut::id:label"));
        assertNull(ShortcutAction.parse("shortcut:package::label"));
        assertNull(ShortcutAction.parse("shortcut:%20:id:label"));
        assertNull(ShortcutAction.parse("shortcut:package:%09:label"));
    }

    @Test
    public void parseRejectsMalformedPercentEncoding() {
        assertNull(ShortcutAction.parse("shortcut:%:id:label"));
        assertNull(ShortcutAction.parse("shortcut:package:id%GG:label"));
        assertNull(ShortcutAction.parse("shortcut:package:id:%A"));
    }

    @Test
    public void toActionIdIsCanonicalAndStable() {
        ShortcutAction action = new ShortcutAction(
                "com.example.пакет", "id: +%", "Ярлык + 100%: тест");

        String canonical = action.toActionId();
        ShortcutAction parsed = ShortcutAction.parse(canonical);

        assertEquals(canonical, parsed.toActionId());
        assertEquals(canonical, ShortcutAction.parse(parsed.toActionId()).toActionId());
        assertEquals(
                "shortcut:com.example.app:main:Label",
                ShortcutAction.parse("shortcut:com%2eexample%2eapp:main:Label").toActionId());
    }

    private static void assertInvalid(String packageName, String shortcutId) {
        try {
            new ShortcutAction(packageName, shortcutId, "label");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Ожидаемая валидация обязательных полей.
        }
    }
}
