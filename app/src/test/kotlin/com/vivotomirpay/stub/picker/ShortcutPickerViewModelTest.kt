package com.vivotomirpay.stub.picker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.vivotomirpay.stub.ShizukuShortcuts.Status
import com.vivotomirpay.stub.platform.AppEntry
import com.vivotomirpay.stub.platform.AppScope
import com.vivotomirpay.stub.platform.PlatformCommand
import com.vivotomirpay.stub.platform.ShortcutEntry
import com.vivotomirpay.stub.platform.ShortcutGateway
import com.vivotomirpay.stub.platform.ShortcutKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutPickerViewModelTest : PickerViewModelTestFixture() {
    private val gateway = ControlledGateway()
    private val packageName = "com.example.app"
    private val shortcut = ShortcutEntry(packageName, "send+%:fast", "Pay +%: now", ShortcutKind.Dynamic, 4)

    @Test
    fun recreatedPickerRestoresSearchAndReloadsExpandedApp() = runTest {
        val app = AppEntry(packageName, "Example")
        catalog.entries = listOf(app, AppEntry("com.other", "Other"))
        val handle = SavedStateHandle()
        val original = model(handle)
        runCurrent()
        original.onIntent(ShortcutPickerIntent.Search("example"))
        original.onIntent(ShortcutPickerIntent.ToggleApp(packageName))
        runCurrent()
        original.viewModelScope.cancel()

        val recreated = model(handle)
        runCurrent()
        assertEquals("example", recreated.state.value.query)
        assertEquals(listOf(app), recreated.state.value.visibleApps.map { it.app })
        assertTrue(recreated.state.value.visibleApps.single().children != null)
        assertNull(recreated.state.value.platformRequest)
    }

    @Test
    fun copiedStateRebuildsLabelOnlyHitFromPreviouslyLoadedCollapsedApp() = runTest {
        restoreShortcutOnlyHit("Pay +%", collapseHit = false)
    }

    @Test
    fun copiedStateKeepsIdOnlyHitCollapsedWithoutExpandingItsApp() = runTest {
        restoreShortcutOnlyHit("send+%:", collapseHit = true)
    }

    private fun TestScope.restoreShortcutOnlyHit(query: String, collapseHit: Boolean) {
        val app = AppEntry(packageName, "Example")
        val unvisited = AppEntry("com.other", "Other")
        catalog.entries = listOf(app, unvisited)
        gateway.enqueue().complete(listOf(shortcut))
        gateway.enqueue().complete(listOf(shortcut))
        val handle = SavedStateHandle()
        val original = model(handle)
        runCurrent()
        original.onIntent(ShortcutPickerIntent.ToggleApp(packageName))
        runCurrent()
        assertEquals(ShortcutLoad.Loaded(listOf(shortcut)), original.state.value.visibleApps.first().children)
        original.onIntent(ShortcutPickerIntent.ToggleApp(packageName))
        runCurrent()
        assertNull(original.state.value.visibleApps.first().children)
        original.onIntent(ShortcutPickerIntent.Search(query))
        runCurrent()
        assertEquals(listOf(ShortcutAppRow(app, ShortcutLoad.Loaded(listOf(shortcut)))), original.state.value.visibleApps)
        if (collapseHit) {
            original.onIntent(ShortcutPickerIntent.ToggleApp(packageName))
            runCurrent()
        }
        val copied = SavedStateHandle(handle.keys().associateWith { key ->
            when (val value = handle.get<Any>(key)) {
                is ArrayList<*> -> ArrayList(value)
                else -> value
            }
        })
        original.viewModelScope.cancel()

        val restored = model(copied)
        runCurrent()
        val expectedChildren = if (collapseHit) null else ShortcutLoad.Loaded(listOf(shortcut))
        assertEquals(query, restored.state.value.query)
        assertEquals(listOf(ShortcutAppRow(app, expectedChildren)), restored.state.value.visibleApps)
        assertEquals(listOf(packageName, packageName), gateway.loads)
        assertNull(restored.state.value.platformRequest)
        assertNull(restored.state.value.permissionRequestId)
        restored.onIntent(ShortcutPickerIntent.Search(""))
        runCurrent()
        assertEquals(listOf(ShortcutAppRow(app, null), ShortcutAppRow(unvisited, null)), restored.state.value.visibleApps)
    }

    @Test
    fun duplicatePermissionClickRequestsOnce() = runTest {
        gateway.currentStatus = Status.PERMISSION_DENIED
        val model = model()
        runCurrent()
        model.onIntent(ShortcutPickerIntent.RequestPermission)
        val first = model.state.value.platformRequest
        assertEquals(PlatformCommand.RequestShortcutPermission, first?.command)
        val id = checkNotNull(first).id
        model.onIntent(ShortcutPickerIntent.RequestPermission)
        assertEquals(first, model.state.value.platformRequest)
        assertTrue(model.acknowledgePlatformRequest(id))
        assertFalse(model.acknowledgePlatformRequest(id))
        assertNull(model.state.value.platformRequest)
        model.onIntent(ShortcutPickerIntent.RequestPermission)
        assertNull(model.state.value.platformRequest)
        assertEquals(id, model.state.value.permissionRequestId)

        model.onPlatformResult(id, ShortcutPlatformResult.Denied)
        assertEquals(ShortcutAccess.PermissionDenied, model.state.value.access)
        assertNull(model.state.value.permissionRequestId)
    }

    @Test
    fun failedPermissionDispatchRechecksAccessAndOffersRecovery() = runTest {
        gateway.currentStatus = Status.PERMISSION_DENIED
        val model = model()
        runCurrent()
        model.onIntent(ShortcutPickerIntent.RequestPermission)
        val id = checkNotNull(model.state.value.platformRequest).id
        assertTrue(model.acknowledgePlatformRequest(id))

        gateway.currentStatus = Status.UNAVAILABLE
        gateway.launchable = true
        model.onPlatformResult(id, ShortcutPlatformResult.Failed)
        assertEquals(ShortcutAccess.Checking, model.state.value.access)
        assertNull(model.state.value.permissionRequestId)
        model.onIntent(ShortcutPickerIntent.RequestPermission)
        assertNull(model.state.value.platformRequest)
        runCurrent()
        assertEquals(ShortcutAccess.Unavailable, model.state.value.access)
        assertTrue(model.state.value.canOpenShizuku)
        assertNull(model.state.value.platformRequest)

        model.onIntent(ShortcutPickerIntent.OpenShizuku)
        val open = checkNotNull(model.state.value.platformRequest)
        assertEquals(PlatformCommand.OpenShizuku, open.command)
        assertTrue(model.acknowledgePlatformRequest(open.id))
        model.onPlatformResult(open.id, ShortcutPlatformResult.Opened)
        gateway.currentStatus = Status.PERMISSION_DENIED
        model.onIntent(ShortcutPickerIntent.RetryAccess)
        runCurrent()
        assertEquals(ShortcutAccess.PermissionAvailable, model.state.value.access)
        assertNull("Recovery must not request permission automatically", model.state.value.platformRequest)
    }

    @Test
    fun explicitPermissionDenialIsNotTreatedAsDispatchFailure() = runTest {
        gateway.currentStatus = Status.PERMISSION_DENIED
        val model = model()
        runCurrent()
        model.onIntent(ShortcutPickerIntent.RequestPermission)
        val id = checkNotNull(model.state.value.platformRequest).id
        assertTrue(model.acknowledgePlatformRequest(id))

        gateway.currentStatus = Status.UNAVAILABLE
        gateway.launchable = true
        model.onPlatformResult(id, ShortcutPlatformResult.Denied)
        runCurrent()
        assertEquals(ShortcutAccess.PermissionDenied, model.state.value.access)
        assertFalse(model.state.value.canOpenShizuku)
        assertNull(model.state.value.platformRequest)
        assertNull(model.state.value.permissionRequestId)
    }

    @Test
    fun stalePermissionResultAfterExitIgnored() = runTest {
        gateway.currentStatus = Status.PERMISSION_DENIED
        val model = model()
        runCurrent()
        model.onIntent(ShortcutPickerIntent.RequestPermission)
        val request = model.state.value.platformRequest
        assertNotNull(request)
        val id = checkNotNull(request).id
        assertTrue(model.acknowledgePlatformRequest(id))
        model.onIntent(ShortcutPickerIntent.Exit)
        val closedState = model.state.value

        val next = model()
        runCurrent()
        next.onIntent(ShortcutPickerIntent.RequestPermission)
        val nextId = checkNotNull(next.state.value.platformRequest).id
        assertNotEquals(id, nextId)
        assertTrue(next.acknowledgePlatformRequest(nextId))
        gateway.currentStatus = Status.OK
        model.onPlatformResult(id, ShortcutPlatformResult.Granted)
        next.onPlatformResult(id, ShortcutPlatformResult.Granted)
        runCurrent()

        assertEquals(closedState, model.state.value)
        assertEquals(nextId, next.state.value.permissionRequestId)
        assertEquals(ShortcutAccess.PermissionAvailable, next.state.value.access)
        assertTrue(catalog.scopes.isEmpty())
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun restorePendingPermissionDoesNotResubmit() = runTest {
        gateway.currentStatus = Status.PERMISSION_DENIED
        val handle = SavedStateHandle()
        val original = model(handle)
        runCurrent()
        original.onIntent(ShortcutPickerIntent.RequestPermission)
        val request = original.state.value.platformRequest
        assertNotNull(request)
        val oldId = checkNotNull(request).id
        assertTrue(original.acknowledgePlatformRequest(oldId))
        // Copy persisted values; don't reuse either the ViewModel or the handle.
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        original.viewModelScope.cancel()
        val restored = model(restoredHandle)
        runCurrent()

        assertNull(restored.state.value.platformRequest)
        assertEquals(ShortcutAccess.PermissionInterrupted, restored.state.value.access)
        assertNull(restored.state.value.permissionRequestId)
        restored.onPlatformResult(oldId, ShortcutPlatformResult.Granted)
        assertEquals(ShortcutAccess.PermissionInterrupted, restored.state.value.access)
        restored.onIntent(ShortcutPickerIntent.RetryAccess)
        runCurrent()
        assertNull(restored.state.value.platformRequest)
        restored.onIntent(ShortcutPickerIntent.RequestPermission)
        assertNotEquals(oldId, restored.state.value.platformRequest?.id)
        assertEquals(PlatformCommand.RequestShortcutPermission, restored.state.value.platformRequest?.command)
    }

    @Test
    fun matchesLoadedShortcutByLabelAndId() = runTest {
        catalog.entries = listOf(AppEntry(packageName, "Example"))
        val pending = gateway.enqueue()
        val model = model()
        runCurrent()
        model.onIntent(ShortcutPickerIntent.ToggleApp(packageName))
        runCurrent()
        model.onIntent(ShortcutPickerIntent.Search("fast"))
        runCurrent()
        assertTrue(model.state.value.visibleApps.isEmpty())
        pending.complete(listOf(shortcut))
        runCurrent()

        assertEquals(listOf(shortcut), visibleShortcuts(model))
        model.onIntent(ShortcutPickerIntent.Search(" PAY +% "))
        runCurrent()
        assertEquals(listOf(shortcut), visibleShortcuts(model))
        model.onIntent(ShortcutPickerIntent.ToggleApp(packageName))
        runCurrent()
        assertTrue(visibleShortcuts(model).isEmpty())
        model.onIntent(ShortcutPickerIntent.Search("com.example"))
        runCurrent()
        assertEquals(listOf(shortcut), visibleShortcuts(model))
        assertEquals(listOf(AppScope.Launcher), catalog.scopes)
        assertEquals(listOf(packageName), gateway.loads)
    }

    @Test
    fun retryFailureThenPickKeepsCanonicalId() = runTest {
        catalog.entries = listOf(AppEntry(packageName, "Example"))
        val first = gateway.enqueue()
        val retry = gateway.enqueue()
        val model = model()
        runCurrent()
        model.onIntent(ShortcutPickerIntent.ToggleApp(packageName))
        runCurrent()
        first.completeExceptionally(IllegalStateException("service lost"))
        runCurrent()
        assertEquals(
            ShortcutLoad.Failed("service lost"),
            model.state.value.visibleApps.singleOrNull()?.children,
        )
        model.onIntent(ShortcutPickerIntent.RetryShortcuts(packageName))
        runCurrent()
        retry.complete(listOf(shortcut))
        runCurrent()
        model.onIntent(ShortcutPickerIntent.PickShortcut(packageName, shortcut.id))
        runCurrent()

        assertEquals("shortcut:com.example.app:send%2B%25%3Afast:Pay+%2B%25%3A+now", repository.saved.value.targetId)
        assertTrue(model.state.value.selectionComplete)
        assertEquals(listOf(packageName, packageName), gateway.loads)
    }

    @Test
    fun selectionCompletionCanOnlyBeClaimedOnce() = runTest {
        val model = completedSelection()
        assertTrue(model.claimSelectionCompletion(isActiveEntry = true))
        assertFalse(model.claimSelectionCompletion(isActiveEntry = true))
    }

    @Test
    fun inactiveEntryKeepsSelectionCompletionPending() = runTest {
        val model = completedSelection()
        assertFalse(model.claimSelectionCompletion(isActiveEntry = false))
        assertTrue(model.state.value.selectionComplete)
        assertTrue(model.claimSelectionCompletion(isActiveEntry = true))
    }

    @Test
    fun exitedEntryCannotClaimSelectionCompletion() = runTest {
        val model = completedSelection()
        model.onIntent(ShortcutPickerIntent.Exit)
        assertFalse(model.claimSelectionCompletion(isActiveEntry = true))
    }

    private fun TestScope.completedSelection(): ShortcutPickerViewModel {
        catalog.entries = listOf(AppEntry(packageName, "Example"))
        gateway.enqueue().complete(listOf(shortcut))
        val model = model()
        assertFalse(model.claimSelectionCompletion(isActiveEntry = true))
        runCurrent()
        model.onIntent(ShortcutPickerIntent.ToggleApp(packageName))
        runCurrent()
        model.onIntent(ShortcutPickerIntent.PickShortcut(packageName, shortcut.id))
        runCurrent()
        assertTrue(model.state.value.selectionComplete)
        return model
    }

    private fun visibleShortcuts(model: ShortcutPickerViewModel) =
        (model.state.value.visibleApps.singleOrNull()?.children as? ShortcutLoad.Loaded)?.entries.orEmpty()

    private fun model(handle: SavedStateHandle = SavedStateHandle()) =
        track(ShortcutPickerViewModel(repository, catalog, gateway, handle, dispatcher))

    private class ControlledGateway : ShortcutGateway {
        var currentStatus = Status.OK
        var launchable = false
        private val results = ArrayDeque<CompletableDeferred<List<ShortcutEntry>>>()
        val loads = mutableListOf<String>()

        fun enqueue() = CompletableDeferred<List<ShortcutEntry>>().also(results::addLast)

        override fun status() = currentStatus
        override fun canRequestPermission() = true
        override suspend fun canOpenShizuku() = launchable
        override suspend fun shortcuts(packageName: String): List<ShortcutEntry> {
            loads.add(packageName)
            return results.removeFirst().await()
        }
    }
}
