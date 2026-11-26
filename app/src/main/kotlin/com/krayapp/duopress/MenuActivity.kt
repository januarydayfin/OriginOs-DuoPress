package com.krayapp.duopress

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import androidx.lifecycle.lifecycleScope
import com.krayapp.duopress.data.SharedPrefsSettingsRepository
import com.krayapp.duopress.menu.ActionSheet
import com.krayapp.duopress.menu.MenuItemUi
import com.krayapp.duopress.menu.MenuSelection
import com.krayapp.duopress.menu.menuItemIcon
import com.krayapp.duopress.menu.visibleMenuItems
import com.krayapp.duopress.platform.optionalPickerValue
import com.krayapp.duopress.platform.pickerIcon
import com.krayapp.duopress.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Рабочая панель двойного нажатия и предпросмотра в прозрачном окне, в том числе над блокировкой. */
class MenuActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var selection: MenuSelection
    private var closing by mutableStateOf(false)
    private var menuItems by mutableStateOf<List<MenuItemUi>?>(null)
    private val forceFinish = Runnable { selection.onHidden() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allowOverKeyguard()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        drawBehindSystemBars()

        val decor = window.decorView
        selection = MenuSelection(
            nowMs = SystemClock::uptimeMillis,
            scheduleAt = { deadline, task -> handler.postAtTime({ task() }, deadline) },
            close = {
                closing = true
                // Закрываемся и без кадров анимации, например если другое действие уже увело окно.
                handler.postDelayed(forceFinish, 500L)
            },
            finish = {
                handler.removeCallbacks(forceFinish)
                decor.visibility = View.INVISIBLE
                finish()
            },
            dispatchImmediately = { ActionRunner.runAction(this, it, null) },
            dispatchCircleToSearch = circleDispatch(applicationContext),
        )
        decor.doOnAttach { attached ->
            attached.doOnDetach { selection.onWindowDetached() }
        }
        setContent {
            AppTheme {
                ActionSheet(
                    items = menuItems,
                    onSelected = selection::select,
                    onDismiss = selection::dismiss,
                    closing = closing,
                    onHidden = selection::onHidden,
                )
            }
        }
        lifecycleScope.launch { menuItems = loadMenuItems(this@MenuActivity) }
    }

    override fun onStop() {
        super.onStop()
        if (closing) selection.onHidden()
    }

    override fun onDestroy() {
        handler.removeCallbacks(forceFinish)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    private fun allowOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    @Suppress("DEPRECATION")
    private fun drawBehindSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }
}

// В отложенной задаче остаётся только application context, не Activity или её окно.
private fun circleDispatch(appContext: Context): () -> Unit = {
    ActionRunner.runAction(appContext, PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH, null)
}

private suspend fun loadMenuItems(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): List<MenuItemUi> = withContext(dispatcher) {
    val settings = SharedPrefsSettingsRepository(context).snapshot()
    val pm = context.packageManager
    visibleMenuItems(settings.menuItems).map { id ->
        ensureActive()
        val packageName = PrefsHelper.appPackageOf(id)
        val label = when (id) {
            PrefsHelper.SPECIAL_TORCH -> context.getString(R.string.menu_item_torch)
            PrefsHelper.SPECIAL_CAMERA -> context.getString(R.string.menu_item_camera)
            PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH -> context.getString(R.string.menu_item_cts)
            PrefsHelper.SPECIAL_VPN -> {
                val client = VpnController.resolveClientPackage(context, settings.vpnClient)
                val name = client?.let(VpnController::clientLabel) ?: context.getString(R.string.vpn_name_generic)
                context.getString(R.string.menu_item_vpn, name)
            }
            else -> if (packageName == null) id else {
                optionalPickerValue { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
                    ?.takeIf(String::isNotEmpty) ?: packageName
            }
        }
        val icon = packageName?.let { optionalPickerValue { context.pickerIcon(pm.getApplicationIcon(it)) } }
        MenuItemUi(id, label, menuItemIcon(id), icon)
    }
}
