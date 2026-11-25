package com.vivotomirpay.stub.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vivotomirpay.stub.R
import com.vivotomirpay.stub.ui.theme.AppTheme

class AppNavigator internal constructor(private val navController: NavHostController) {
    fun open(route: AppRoute) {
        val isRoot = rootSections.any { it.route == route }
        navController.navigate(route) {
            launchSingleTop = true
            if (isRoot) {
                popUpTo<AppRoute.Actions> { saveState = true }
                restoreState = true
            }
        }
    }

    fun back() {
        if (navController.previousBackStackEntry != null) navController.popBackStack()
    }

    fun completeActionSelection() {
        navController.popBackStack<AppRoute.Actions>(inclusive = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    render: @Composable (AppRoute, AppNavigator) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val navigator = remember(navController) { AppNavigator(navController) }
    val renderDestination by rememberUpdatedState(render)
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val selectedSection = rootSections.firstOrNull { destination?.hasRoute(it.route::class) == true }
    val nestedTitle = nestedTitles.entries.firstOrNull { destination?.hasRoute(it.key::class) == true }?.value

    Scaffold(
        modifier = modifier,
        topBar = {
            if (nestedTitle != null) {
                TopAppBar(
                    title = { Text(stringResource(nestedTitle)) },
                    navigationIcon = {
                        IconButton(onClick = navigator::back) {
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
                NavigationBar {
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
        ) {
            composable<AppRoute.Actions> { renderDestination(AppRoute.Actions, navigator) }
            composable<AppRoute.Settings> { renderDestination(AppRoute.Settings, navigator) }
            composable<AppRoute.Diagnostics> { renderDestination(AppRoute.Diagnostics, navigator) }
            composable<AppRoute.ActionPicker> { renderDestination(AppRoute.ActionPicker, navigator) }
            composable<AppRoute.ShortcutPicker> { renderDestination(AppRoute.ShortcutPicker, navigator) }
            composable<AppRoute.Lockscreen> { renderDestination(AppRoute.Lockscreen, navigator) }
            composable<AppRoute.MenuEditor> { renderDestination(AppRoute.MenuEditor, navigator) }
        }
    }
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
    AppRoute.ShortcutPicker to R.string.shortcut_picker_title,
    AppRoute.Lockscreen to R.string.card_lock_title,
    AppRoute.MenuEditor to R.string.editor_menu_title,
)

@Preview(showBackground = true)
@Composable
private fun AppNavHostPreview() {
    AppTheme {
        AppNavHost(render = { _, _ -> Text(stringResource(R.string.app_name)) })
    }
}
