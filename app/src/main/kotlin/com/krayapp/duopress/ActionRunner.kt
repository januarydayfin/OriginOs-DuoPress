package com.krayapp.duopress

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Общий исполнитель действий для системной точки входа Vivo и меню. */
class ActionRunner private constructor() {
    companion object {
        private const val OMNI_ENTRY_POINT_APP_LAUNCH = 1

        /** Убирает только крайние символы U+0000..U+0020, как Java String.trim(). */
        @JvmStatic
        fun normalize(id: String?): String? = id?.trim { it <= ' ' }?.takeIf(String::isNotEmpty)

        /** Выполняет действие и возвращает записанный в журнал статус. */
        @JvmStatic
        suspend fun runAction(context: Context, actionId: String?, sourceIntent: Intent?): String {
            val normalized = normalize(actionId)
            if (normalized == null) {
                PrefsHelper.saveTriggerLog(context, sourceIntent, null, context.getString(R.string.log_reason_not_set))
                return context.getString(R.string.log_reason_not_set)
            }

            var id = normalized
            try {
                if (ShortcutAction.isAction(id)) {
                    return launchShortcut(context, id, sourceIntent)
                }
                if (id.startsWith(PrefsHelper.SPECIAL_PREFIX)) {
                    return runSpecial(context, id, sourceIntent)
                }
                if (id.startsWith(PrefsHelper.APP_PREFIX)) {
                    val pkg = PrefsHelper.appPackageOf(id)
                    if (pkg == null) {
                        val bad = context.getString(R.string.log_app_not_found, id)
                        PrefsHelper.saveTriggerLog(context, sourceIntent, id, bad)
                        return bad
                    }
                    id = pkg
                }
                return launchApp(context, id, sourceIntent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = context.getString(R.string.log_action_failed, safeMsg(e))
                PrefsHelper.saveTriggerLog(context, sourceIntent, id, status)
                return status
            }
        }

        private suspend fun launchShortcut(
            context: Context,
            id: String,
            sourceIntent: Intent?,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): String = withContext(dispatcher) {
            try {
                val shortcut = ShortcutAction.parse(id)
                if (shortcut == null) {
                    return@withContext saveShortcutResult(
                        context, sourceIntent, id, context.getString(R.string.log_shortcut_invalid, id),
                    )
                }

                val status = ShizukuShortcuts.checkStatus()
                if (status != ShizukuShortcuts.Status.OK) {
                    val messageRes = when (status) {
                        ShizukuShortcuts.Status.UNAVAILABLE -> R.string.shortcut_shizuku_unavailable
                        ShizukuShortcuts.Status.PRE_V13 -> R.string.shortcut_shizuku_pre_v13
                        ShizukuShortcuts.Status.PERMISSION_DENIED -> R.string.shortcut_permission_required
                        ShizukuShortcuts.Status.UNSUPPORTED_UID -> R.string.shortcut_unsupported_uid
                        ShizukuShortcuts.Status.UNSUPPORTED_API -> R.string.shortcut_unsupported_api
                        else -> R.string.shortcut_shizuku_unavailable
                    }
                    return@withContext saveShortcutResult(context, sourceIntent, id, context.getString(messageRes))
                }

                ShizukuShortcuts.startShortcut(context, shortcut.packageName, shortcut.shortcutId)
                var label: String? = shortcut.label
                if (label == null || label.trim { it <= ' ' }.isEmpty()) {
                    label = shortcut.shortcutId
                }
                saveShortcutResult(context, sourceIntent, id, context.getString(R.string.log_shortcut_ok, label))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveShortcutResult(context, sourceIntent, id, context.getString(R.string.log_shortcut_failed, safeMsg(e)))
            } catch (e: LinkageError) {
                saveShortcutResult(context, sourceIntent, id, context.getString(R.string.log_shortcut_failed, safeMsg(e)))
            }
        }

        private fun saveShortcutResult(context: Context, sourceIntent: Intent?, id: String, result: String): String {
            PrefsHelper.saveTriggerLog(context, sourceIntent, id, result)
            return result
        }

        private fun runSpecial(context: Context, id: String, sourceIntent: Intent?): String = when (id) {
            PrefsHelper.SPECIAL_TORCH -> toggleFlashlight(context, sourceIntent, id)
            PrefsHelper.SPECIAL_CAMERA -> launchCamera(context, sourceIntent, id)
            PrefsHelper.SPECIAL_CIRCLE_TO_SEARCH -> triggerCircleToSearch(context, sourceIntent, id)
            PrefsHelper.SPECIAL_VPN -> {
                val clientSetting = PrefsHelper.getVpnClient(context)
                val vpnResult = VpnController.controlVpn(context, clientSetting, "toggle")
                PrefsHelper.saveTriggerLog(context, sourceIntent, id, vpnResult)
                vpnResult
            }
            PrefsHelper.SPECIAL_MENU -> {
                try {
                    val menu = Intent(context, MenuActivity::class.java)
                    menu.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    )
                    context.startActivity(menu)
                    val ok = context.getString(R.string.log_menu_opened)
                    PrefsHelper.saveTriggerLog(context, sourceIntent, id, ok)
                    ok
                } catch (e: Exception) {
                    val fail = context.getString(R.string.log_menu_failed, safeMsg(e))
                    PrefsHelper.saveTriggerLog(context, sourceIntent, id, fail)
                    fail
                }
            }
            else -> {
                val unknown = context.getString(R.string.log_unknown_special, id)
                PrefsHelper.saveTriggerLog(context, sourceIntent, id, unknown)
                unknown
            }
        }

        @JvmStatic
        fun toggleFlashlight(context: Context, sourceIntent: Intent?, targetPkg: String?): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                val msg = context.getString(R.string.log_torch_api)
                PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                return msg
            }
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager?
            if (cm == null) {
                val msg = context.getString(R.string.log_torch_no_manager)
                PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                return msg
            }
            try {
                val ids: Array<String>? = cm.cameraIdList
                if (ids.isNullOrEmpty()) {
                    val msg = context.getString(R.string.log_torch_no_flash)
                    PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                    return msg
                }
                val currentlyOn = PrefsHelper.isTorchOn(context)
                val next = !currentlyOn
                cm.setTorchMode(ids[0], next)
                PrefsHelper.setTorchOn(context, next)
                val msg = context.getString(if (next) R.string.log_torch_on else R.string.log_torch_off)
                PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                return msg
            } catch (e: Exception) {
                val msg = context.getString(R.string.log_torch_error, safeMsg(e))
                PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                return msg
            }
        }

        /** Сначала пробует защищённую камеру для локскрина, затем обычную. */
        @JvmStatic
        fun launchCamera(context: Context, sourceIntent: Intent?, targetPkg: String?): String {
            try {
                val pm = context.packageManager
                var secure = true
                var camera = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                if (camera.resolveActivity(pm) == null) {
                    camera = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    secure = false
                }
                if (camera.resolveActivity(pm) == null) {
                    val msg = context.getString(R.string.log_camera_not_found)
                    PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                    return msg
                }
                camera.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
                context.startActivity(camera)
                val ok = context.getString(if (secure) R.string.log_camera_ok_secure else R.string.log_camera_ok)
                PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, ok)
                return ok
            } catch (e: Exception) {
                val msg = context.getString(R.string.log_camera_error, safeMsg(e))
                PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                return msg
            }
        }

        /** Запускает Circle to Search прежним механизмом MiCTS. */
        @JvmStatic
        @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
        fun triggerCircleToSearch(context: Context, sourceIntent: Intent?, targetPkg: String?): String {
            try {
                // Адаптер остаётся package-private на JVM, как при вызове из Java.
                HiddenApiCompat.ensureExemptions()

                val bundle = Bundle()
                bundle.putLong("invocation_time_ms", SystemClock.elapsedRealtime())
                bundle.putInt("omni.entry_point", OMNI_ENTRY_POINT_APP_LAUNCH)
                bundle.putBoolean("micts_trigger", true)

                val serviceManagerClass = Class.forName("android.os.ServiceManager")
                val getService = serviceManagerClass.getMethod("getService", String::class.java)
                val visBinder = getService.invoke(null, "voiceinteraction") as IBinder?
                if (visBinder == null) {
                    val msg = context.getString(R.string.log_cts_no_service)
                    PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                    return msg
                }

                val iVimsClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService")
                val stubClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                val vims = asInterface.invoke(null, visBinder)
                if (vims == null) {
                    val msg = context.getString(R.string.log_cts_no_interface)
                    PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                    return msg
                }

                // Android 14+: showSessionFromSession с invocationContext; раньше — без него.
                val result = HiddenApiCompat.showSessionFromSession(
                    iVimsClass, vims, bundle, Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                )

                val success = result is Boolean && result
                val assistant = getCurrentDefaultAssistant(context)
                val msg = if (success) {
                    context.getString(R.string.log_cts_ok, assistant)
                } else {
                    context.getString(R.string.log_cts_false, assistant)
                }
                PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                return msg
            } catch (e: Exception) {
                val msg = context.getString(R.string.log_cts_error, safeMsg(e))
                PrefsHelper.saveTriggerLog(context, sourceIntent, targetPkg, msg)
                return msg
            }
        }

        private fun getCurrentDefaultAssistant(context: Context): String {
            try {
                val assistant = Settings.Secure.getString(context.contentResolver, "assistant")
                if (assistant.isNullOrEmpty()) return context.getString(R.string.assistant_none)
                val pkg = assistant.substringBefore('/')
                val label = getAppLabel(context, pkg)
                if (label == null || label == pkg) return assistant
                return "$label ($pkg)"
            } catch (_: Exception) {
                return context.getString(R.string.assistant_read_error)
            }
        }

        private fun getAppLabel(context: Context, pkg: String?): String? {
            if (pkg.isNullOrEmpty()) return pkg
            try {
                val pm = context.packageManager
                val label: CharSequence? = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
                if (!label.isNullOrEmpty()) return label.toString()
            } catch (_: Exception) {
            }
            return pkg
        }

        @JvmStatic
        fun launchApp(context: Context, targetPkg: String?, sourceIntent: Intent?): String {
            // Защита от прямых вызовов с id в формате меню ("app:<packageName>").
            val packageName = PrefsHelper.appPackageOf(targetPkg) ?: targetPkg

            if (getSelfPackage(context)!!.equals(packageName)) {
                val reason = context.getString(R.string.log_reason_self)
                PrefsHelper.saveTriggerLog(context, sourceIntent, packageName, reason)
                return reason
            }

            val pm = context.packageManager

            // Для Vivo Wallet первым пробуем его NFC-ключ (NewSwipeActivity).
            if (packageName == "com.vivo.wallet") {
                try {
                    val walletKeyIntent = Intent(SwipeEntryActivity.ACTION_VIVO_SWIPE)
                    walletKeyIntent.addCategory(Intent.CATEGORY_DEFAULT)
                    walletKeyIntent.component = ComponentName("com.vivo.wallet", "com.vivo.pay.swing.activity.NewSwipeActivity")
                    walletKeyIntent.putExtra("lb_from", "2")
                    walletKeyIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                    )
                    context.startActivity(walletKeyIntent)
                    val ok = context.getString(R.string.log_wallet_ok)
                    PrefsHelper.saveTriggerLog(context, sourceIntent, packageName, ok)
                    return ok
                } catch (_: Exception) {
                    // Пробуем обычные способы запуска.
                }
            }

            try {
                // Прямой Java-вызов launchApp мог передать null и затем попасть в fallback.
                @Suppress("ARGUMENT_TYPE_MISMATCH")
                val launchIntent = pm.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                    )
                    context.startActivity(launchIntent)
                    val ok = context.getString(R.string.log_app_ok, getAppLabel(context, packageName))
                    PrefsHelper.saveTriggerLog(context, sourceIntent, packageName, ok)
                    return ok
                }
            } catch (_: Exception) {
                // Переходим к fallback.
            }

            try {
                val mainQuery = Intent(Intent.ACTION_MAIN)
                mainQuery.addCategory(Intent.CATEGORY_LAUNCHER)
                mainQuery.setPackage(packageName)
                val matches = pm.queryIntentActivities(mainQuery, 0)
                val chosen = pickLauncherActivity(matches)
                if (chosen != null && chosen.activityInfo != null) {
                    val explicit = Intent(Intent.ACTION_MAIN)
                    explicit.addCategory(Intent.CATEGORY_LAUNCHER)
                    explicit.component = ComponentName(chosen.activityInfo.packageName, chosen.activityInfo.name)
                    explicit.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                    )
                    context.startActivity(explicit)
                    val ok = context.getString(R.string.log_app_ok, getAppLabel(context, packageName))
                    PrefsHelper.saveTriggerLog(context, sourceIntent, packageName, ok)
                    return ok
                }

                val mainOnly = Intent(Intent.ACTION_MAIN)
                mainOnly.setPackage(packageName)
                val mainOnlyMatches = pm.queryIntentActivities(mainOnly, 0)
                if (!mainOnlyMatches.isNullOrEmpty()) {
                    val ri = mainOnlyMatches[0]
                    if (ri.activityInfo != null) {
                        val explicit = Intent(Intent.ACTION_MAIN)
                        explicit.component = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                        explicit.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                        )
                        context.startActivity(explicit)
                        val ok = context.getString(R.string.log_app_ok, getAppLabel(context, packageName))
                        PrefsHelper.saveTriggerLog(context, sourceIntent, packageName, ok)
                        return ok
                    }
                }
            } catch (e: Exception) {
                val fail = context.getString(R.string.log_app_error, safeMsg(e))
                PrefsHelper.saveTriggerLog(context, sourceIntent, packageName, fail)
                return fail
            }

            val notFound = context.getString(R.string.log_app_not_found, packageName)
            PrefsHelper.saveTriggerLog(context, sourceIntent, packageName, notFound)
            return notFound
        }

        private fun pickLauncherActivity(matches: List<ResolveInfo?>?): ResolveInfo? {
            if (matches.isNullOrEmpty()) return null
            for (ri in matches) {
                if (ri == null || ri.activityInfo == null) continue
                if (ri.filter?.hasCategory(Intent.CATEGORY_LAUNCHER) == true) return ri
            }
            return matches[0]
        }

        @JvmStatic
        fun getSelfPackage(context: Context): String? = context.packageName

        @JvmStatic
        fun safeMsg(t: Throwable?): String {
            var cause = t ?: return ""
            while (true) {
                val next = (cause as? InvocationTargetException)?.targetException ?: cause.cause
                if (next == null || next === cause) break
                cause = next
            }
            return cause.message ?: cause.javaClass.simpleName
        }
    }
}
