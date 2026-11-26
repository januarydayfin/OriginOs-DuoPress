package com.krayapp.duopress.ui.navigation

import kotlinx.serialization.Serializable

sealed interface AppRoute {
    @Serializable data object Actions : AppRoute
    @Serializable data object Settings : AppRoute
    @Serializable data object Diagnostics : AppRoute
    @Serializable data object ActionPicker : AppRoute
    @Serializable data object ShortcutPicker : AppRoute
    @Serializable data object MenuShortcutPicker : AppRoute
    @Serializable data object Lockscreen : AppRoute
    @Serializable data object MenuEditor : AppRoute
}
