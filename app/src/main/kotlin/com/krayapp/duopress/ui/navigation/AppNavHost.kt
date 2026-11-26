package com.krayapp.duopress.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.krayapp.duopress.R
import com.krayapp.duopress.ui.theme.AppTheme

class AppNavigator internal constructor(private val navController: NavHostController) {
    fun open(route: AppRoute) {
        if (navController.currentDestination?.hasRoute(route::class) == true) return
        val isRoot = rootSections.any { it.route == route }
        navController.navigate(route) {
            launchSingleTop = true
            if (isRoot) {
                popUpTo<AppRoute.Actions> { saveState = true }
                restoreState = true
            }
        }
    }

    internal fun actionsEntry(): NavBackStackEntry = navController.getBackStackEntry<AppRoute.Actions>()
    internal fun actionPickerEntry(): NavBackStackEntry = navController.getBackStackEntry<AppRoute.ActionPicker>()
    internal fun menuEditorEntry(): NavBackStackEntry = navController.getBackStackEntry<AppRoute.MenuEditor>()

    internal fun completeMenuAddition(entry: NavBackStackEntry, claim: (Boolean) -> Boolean,
                                      finishEditor: (NavBackStackEntry) -> Unit, exit: (NavBackStackEntry) -> Unit) {
        val editor = navController.previousBackStackEntry ?: return
        if (!editor.destination.hasRoute<AppRoute.MenuEditor>() || !isResumed(entry) || !claim(true)) return
        finishEditor(editor)
        exit(entry)
        navController.popBackStack()
    }

    internal fun isResumed(entry: NavBackStackEntry): Boolean =
        navController.currentBackStackEntry === entry && entry.lifecycle.currentState == Lifecycle.State.RESUMED

    internal fun back(entry: NavBackStackEntry, onBack: (NavBackStackEntry, NavBackStackEntry) -> Boolean) {
        if (navController.currentBackStackEntry !== entry) return
        val parent = navController.previousBackStackEntry ?: return
        if (!onBack(entry, parent)) navController.popBackStack()
    }

    internal fun completeActionSelection(
        entry: NavBackStackEntry,
        claim: (isActiveEntry: Boolean) -> Boolean,
        onExit: (NavBackStackEntry) -> Unit,
    ) {
        if (!isResumed(entry) || !claim(true)) return
        onExit(entry)
        navController.previousBackStackEntry
            ?.takeIf { it.destination.hasRoute<AppRoute.ActionPicker>() }
            ?.let(onExit)
        navController.popBackStack<AppRoute.Actions>(inclusive = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    render: @Composable (AppRoute, NavBackStackEntry, AppNavigator) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (NavBackStackEntry, NavBackStackEntry) -> Boolean = { _, _ -> false },
    menuEditorActions: @Composable (NavBackStackEntry) -> Unit = {},
) {
    val navController = rememberNavController()
    val navigator = remember(navController) { AppNavigator(navController) }
    val entry by navController.currentBackStackEntryAsState()
    val renderDestination by rememberUpdatedState(
        @Composable { route: AppRoute, screenEntry: NavBackStackEntry, screenNavigator: AppNavigator ->
            RouteContent(navController, screenEntry, active = screenEntry === entry) {
                render(route, screenEntry, screenNavigator)
            }
        },
    )
    val destination = entry?.destination
    val selectedSection = rootSections.firstOrNull { destination?.hasRoute(it.route::class) == true }
    val nestedTitle = destination?.nestedTitleRes()

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (nestedTitle != null) {
                TopAppBar(
                    title = { Text(stringResource(nestedTitle), Modifier.semantics { heading() }) },
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    actions = {
                        entry?.takeIf { it.destination.hasRoute<AppRoute.MenuEditor>() }?.let { menuEditorActions(it) }
                    },
                    navigationIcon = {
                        IconButton(onClick = { entry?.let { navigator.back(it, onBack) } }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.btn_back),
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selectedSection != null) {
                NavigationBar(
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                ) {
                    rootSections.forEach { section ->
                        NavigationBarItem(
                            selected = section == selectedSection,
                            onClick = { navigator.open(section.route) },
                            icon = { Icon(section.icon, contentDescription = null) },
                            label = { Text(stringResource(section.label), textAlign = TextAlign.Center) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppRoute.Actions,
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding),
            enterTransition = {
                if (targetState.destination.nestedTitleRes() != null) {
                    slideIntoContainer(SlideDirection.Left, tween(NESTED_SLIDE_DURATION_MS))
                } else EnterTransition.None
            },
            exitTransition = {
                if (targetState.destination.nestedTitleRes() != null) {
                    slideOutOfContainer(SlideDirection.Left, tween(NESTED_SLIDE_DURATION_MS))
                } else ExitTransition.None
            },
            popEnterTransition = {
                if (initialState.destination.nestedTitleRes() != null) {
                    slideIntoContainer(SlideDirection.Right, tween(NESTED_SLIDE_DURATION_MS))
                } else EnterTransition.None
            },
            popExitTransition = {
                if (initialState.destination.nestedTitleRes() != null) {
                    slideOutOfContainer(SlideDirection.Right, tween(NESTED_SLIDE_DURATION_MS))
                } else ExitTransition.None
            },
        ) {
            composable<AppRoute.Actions> { renderDestination(AppRoute.Actions, it, navigator) }
            composable<AppRoute.Settings> { renderDestination(AppRoute.Settings, it, navigator) }
            composable<AppRoute.Diagnostics> { renderDestination(AppRoute.Diagnostics, it, navigator) }
            composable<AppRoute.ActionPicker> { renderDestination(AppRoute.ActionPicker, it, navigator) }
            composable<AppRoute.DoublePressAppPicker> { renderDestination(AppRoute.DoublePressAppPicker, it, navigator) }
            composable<AppRoute.ShortcutPicker> { renderDestination(AppRoute.ShortcutPicker, it, navigator) }
            composable<AppRoute.MenuShortcutPicker> { renderDestination(AppRoute.MenuShortcutPicker, it, navigator) }
            composable<AppRoute.MenuAppPicker> { renderDestination(AppRoute.MenuAppPicker, it, navigator) }
            composable<AppRoute.Lockscreen> { renderDestination(AppRoute.Lockscreen, it, navigator) }
            composable<AppRoute.MenuEditor> { renderDestination(AppRoute.MenuEditor, it, navigator) }
        }
        // Use the same pre-pop path for Android Back and the app bar, not disposal on rotation.
        BackHandler(enabled = nestedTitle != null) {
            entry?.let { navigator.back(it, onBack) }
        }
    }
}

@Composable
private fun RouteContent(
    navController: NavHostController,
    entry: NavBackStackEntry,
    active: Boolean,
    content: @Composable () -> Unit,
) {
    // AnimatedContent keeps the outgoing screen composed after it stops being the current entry.
    val input = Modifier
        .pointerInput(navController, entry) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (navController.currentBackStackEntry !== entry) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .onPreviewKeyEvent { navController.currentBackStackEntry !== entry }
    val semantics = if (active) Modifier else Modifier.clearAndSetSemantics {}
    Box(modifier = input.then(semantics), propagateMinConstraints = true) { content() }
}

private data class RootSection(
    val route: AppRoute,
    @StringRes val label: Int,
    val icon: ImageVector,
)

private val rootSections = listOf(
    RootSection(AppRoute.Actions, R.string.nav_actions, Icons.Filled.PlayArrow),
    RootSection(AppRoute.Settings, R.string.nav_settings, Icons.Filled.Settings),
    RootSection(AppRoute.Diagnostics, R.string.nav_diagnostics, Icons.Filled.Info),
)

private val nestedTitles = mapOf(
    AppRoute.ActionPicker to R.string.picker_title_main,
    AppRoute.DoublePressAppPicker to R.string.picker_section_apps,
    AppRoute.ShortcutPicker to R.string.shortcut_picker_title,
    AppRoute.MenuShortcutPicker to R.string.shortcut_picker_title,
    AppRoute.MenuAppPicker to R.string.picker_section_apps,
    AppRoute.Lockscreen to R.string.card_lock_title,
    AppRoute.MenuEditor to R.string.editor_menu_title,
)

private const val NESTED_SLIDE_DURATION_MS = 140

@StringRes
private fun NavDestination.nestedTitleRes(): Int? =
    nestedTitles.entries.firstOrNull { hasRoute(it.key::class) }?.value

@Preview(showBackground = true)
@Composable
private fun AppNavHostPreview() {
    AppTheme {
        AppNavHost(render = { _, _, _ -> Text(stringResource(R.string.app_name)) })
    }
}
