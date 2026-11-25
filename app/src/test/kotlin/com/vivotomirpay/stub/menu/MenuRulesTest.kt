package com.vivotomirpay.stub.menu

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuRulesTest {
    @Test
    fun visibleMenuDropsRecursiveDuplicatesAndStopsAt16() {
        val apps = (1..20).map { "app:org.example.app$it" }
        val raw = listOf(" special:menu ", " \t", " special:camera ", "special:camera", "\u00a0") + apps

        assertEquals(listOf("special:camera", "\u00a0") + apps.take(14), visibleMenuItems(raw))
        assertEquals(" special:camera ", raw[2])
    }

    @Test
    fun addDuplicateAndSeventeenthLeaveListUnchanged() {
        val full = listOf(" app:org.example.first ") + (2..16).map { "app:org.example.app$it" }

        assertEquals(MenuEditResult.Duplicate to full, edit(full, MenuEdit.Add("app:org.example.first")))
        assertEquals(MenuEditResult.Full to full, edit(full, MenuEdit.Add("app:org.example.new")))
        assertEquals(MenuEditResult.NoChange to full, edit(full, MenuEdit.Add(" special:menu ")))
        assertEquals(MenuEditResult.NoChange to full, edit(full, MenuEdit.Add(" \t")))
    }

    @Test
    fun removeFinalItemRetainsEmptyMenu() {
        val raw = listOf("special:camera")
        assertEquals(
            MenuEditResult.Applied to emptyList<String>(),
            edit(raw, MenuEdit.RemoveAt(0, raw)),
        )
        assertEquals(MenuEditResult.NoChange to emptyList<String>(), edit(emptyList(), MenuEdit.RemoveAt(0, emptyList())))
    }

    @Test
    fun movePersistsOrder() {
        val raw = listOf(" special:torch ", "", "special:menu", "special:camera", "special:camera")
        val moved = listOf("", "special:menu", "special:camera", " special:torch ", "special:camera")

        assertEquals(MenuEditResult.Applied to moved, edit(raw, MenuEdit.Move(0, 3, raw)))
        assertEquals(MenuEditResult.Applied to moved.dropLast(1), edit(moved, MenuEdit.RemoveAt(4, moved)))
        assertEquals(MenuEditResult.NoChange to raw, edit(raw, MenuEdit.Move(0, 5, raw)))
        assertEquals(MenuEditResult.NoChange to raw, edit(raw, MenuEdit.Move(1, 1, raw)))
        assertEquals(MenuEditResult.NoChange to raw, edit(raw, MenuEdit.Move(3, 4, raw)))
    }

    private fun edit(raw: List<String>, edit: MenuEdit) = applyMenuEdit(raw, edit, { it }, { it })
}
