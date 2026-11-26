package com.krayapp.duopress

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.krayapp.duopress.platform.resolveSwipeAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Прозрачная системная точка входа Vivo для двойного нажатия кнопки питания. */
@Suppress("DEPRECATION")
open class SwipeEntryActivity : ComponentActivity() {
    private var actionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allowOverKeyguard()
        overridePendingTransition(0, 0)
        handleSwipeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        overridePendingTransition(0, 0)
        handleSwipeIntent(intent)
    }

    private fun allowOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    private fun handleSwipeIntent(incomingIntent: Intent?) {
        actionJob?.cancel()
        val locked = isDeviceLocked()
        val lockscreenEnabled = locked && PrefsHelper.isLockscreenEnabled(this)
        // Читаем только настройку выбранной ветки, как в Java-реализации.
        val actionId = resolveSwipeAction(
            locked,
            lockscreenEnabled,
            lockscreenActionId = if (lockscreenEnabled) PrefsHelper.getLockscreenAction(this) else "",
            targetId = if (lockscreenEnabled) "" else PrefsHelper.getTargetPackage(this),
        )

        if (PrefsHelper.isVibrateEnabled(this)) {
            Vibe.trigger(this)
        }

        actionJob = lifecycleScope.launch {
            ActionRunner.runAction(this@SwipeEntryActivity, actionId, incomingIntent)
            if (actionId == PrefsHelper.SPECIAL_CAMERA) {
                // Камере нужно успеть открыть окно поверх локскрина до завершения Activity.
                Handler(Looper.getMainLooper()).postDelayed(this@SwipeEntryActivity::finish, 450L)
            } else {
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    private fun isDeviceLocked(): Boolean = try {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?
        keyguard != null && keyguard.isKeyguardLocked
    } catch (_: Exception) {
        false
    }

    companion object {
        const val ACTION_VIVO_SWIPE = "vivo.intent.action.NFC_BUS_SWINGSPLASH"
    }
}
