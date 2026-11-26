package com.krayapp.duopress

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Хранилище настроек заглушки. Имя файла, ключи и значения совместимы со старыми версиями. */
open class PrefsHelper {
    companion object {
        private const val PREFS_NAME = "vivo_swipe_stub_prefs"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_DOUBLE_PRESS_PACKAGE = "double_press_package"
        private const val KEY_VIBRATE = "vibrate_on_trigger"
        private const val KEY_LAST_TRIGGER = "last_trigger_log"
        private const val KEY_TORCH_ON = "torch_on"
        private const val KEY_LOCKSCREEN_ENABLED = "lockscreen_enabled"
        private const val KEY_LOCKSCREEN_ACTION = "lockscreen_action"
        private const val KEY_VIBRATE_DURATION = "vibrate_duration_ms"
        private const val KEY_VIBRATE_PATTERN = "vibrate_pattern"
        private const val KEY_VPN_CLIENT = "vpn_client"
        private const val KEY_MENU_ITEMS = "menu_items_json"

        // Ограничения журнала для Intent, поступающих в экспортированную Activity.
        private const val LOG_MAX_TOTAL = 1000
        private const val LOG_MAX_EXTRA_VALUE = 100

        const val SPECIAL_PREFIX = "special:"
        const val SPECIAL_TORCH = "special:torch"
        const val SPECIAL_CAMERA = "special:camera"
        const val SPECIAL_CIRCLE_TO_SEARCH = "special:circle_to_search"
        const val SPECIAL_VPN = "special:vpn"
        const val SPECIAL_MENU = "special:menu"
        const val APP_PREFIX = "app:"
        const val MAX_MENU_ITEMS = 16

        const val DEFAULT_TARGET_PACKAGE = "ru.nspk.mirpay"
        const val DEFAULT_LOCKSCREEN_ACTION = SPECIAL_TORCH
        const val DEFAULT_VIBRATE_DURATION_MS = 40

        /**
         * Открывает один и тот же файл для чтения, записи и подписки репозитория.
         *
         * @param context контекст установленного варианта приложения
         * @return настройки этого варианта
         */
        internal fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Java String.trim() удалял только символы до U+0020 включительно.
        private fun String.legacyTrim(): String = trim { it <= ' ' }

        /**
         * Читает целевое действие, не меняя значение в файле.
         *
         * @param context контекст установленного варианта приложения
         * @return сохранённая цель без крайних ASCII-пробелов и управляющих символов либо Mir Pay
         */
        @JvmStatic
        fun getTargetPackage(context: Context): String {
            val pkg = preferences(context).getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE)?.legacyTrim()
            return if (pkg.isNullOrEmpty()) DEFAULT_TARGET_PACKAGE else pkg
        }

        /**
         * Сохраняет цель, обрезая её по прежним правилам.
         *
         * @param context контекст установленного варианта приложения
         * @param pkg цель для запуска; `null` сохраняет цель по умолчанию
         */
        @JvmStatic
        fun setTargetPackage(context: Context, pkg: String?) {
            preferences(context).edit().putString(KEY_TARGET_PACKAGE, pkg?.legacyTrim() ?: DEFAULT_TARGET_PACKAGE).apply()
        }

        /** Пакет или спец-значение для двойного нажатия; пустая строка означает «выключено». */
        @JvmStatic
        fun getDoublePressPackage(context: Context): String? =
            preferences(context).getString(KEY_DOUBLE_PRESS_PACKAGE, "")

        @JvmStatic
        fun setDoublePressPackage(context: Context, pkg: String?) {
            preferences(context).edit().putString(KEY_DOUBLE_PRESS_PACKAGE, pkg?.legacyTrim() ?: "").apply()
        }

        /** Отдельное действие на заблокированном экране включено по умолчанию. */
        @JvmStatic
        fun isLockscreenEnabled(context: Context): Boolean =
            preferences(context).getBoolean(KEY_LOCKSCREEN_ENABLED, true)

        @JvmStatic
        fun setLockscreenEnabled(context: Context, enabled: Boolean) {
            preferences(context).edit().putBoolean(KEY_LOCKSCREEN_ENABLED, enabled).apply()
        }

        /** Действие на заблокированном экране (спец-значение или пакет). */
        @JvmStatic
        fun getLockscreenAction(context: Context): String {
            val action = preferences(context).getString(KEY_LOCKSCREEN_ACTION, DEFAULT_LOCKSCREEN_ACTION)?.legacyTrim()
            return if (action.isNullOrEmpty()) DEFAULT_LOCKSCREEN_ACTION else action
        }

        @JvmStatic
        fun setLockscreenAction(context: Context, action: String?) {
            preferences(context).edit()
                .putString(KEY_LOCKSCREEN_ACTION, action?.legacyTrim() ?: DEFAULT_LOCKSCREEN_ACTION)
                .apply()
        }

        @JvmStatic
        fun isVibrateEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_VIBRATE, true)

        @JvmStatic
        fun setVibrateEnabled(context: Context, enabled: Boolean) {
            preferences(context).edit().putBoolean(KEY_VIBRATE, enabled).apply()
        }

        /** Длительность импульса вибрации в мс (20..500); сохранённое значение не меняется. */
        @JvmStatic
        fun getVibrateDurationMs(context: Context): Int =
            preferences(context).getInt(KEY_VIBRATE_DURATION, DEFAULT_VIBRATE_DURATION_MS).coerceIn(20, 500)

        /**
         * Сохраняет длительность без ограничения, которое применяется только при чтении.
         *
         * @param context контекст установленного варианта приложения
         * @param durationMs длительность импульса в миллисекундах
         */
        @JvmStatic
        fun setVibrateDurationMs(context: Context, durationMs: Int) {
            preferences(context).edit().putInt(KEY_VIBRATE_DURATION, durationMs).apply()
        }

        /** Паттерн вибрации: `single`, `double` или `long`. */
        @JvmStatic
        fun getVibratePattern(context: Context): String =
            when (val pattern = preferences(context).getString(KEY_VIBRATE_PATTERN, "single")) {
                "single", "double", "long" -> pattern
                else -> "single"
            }

        @JvmStatic
        fun setVibratePattern(context: Context, pattern: String?) {
            preferences(context).edit().putString(KEY_VIBRATE_PATTERN, pattern).apply()
        }

        /** Выбранный VPN-клиент: `auto`, `happ` или `incy`. */
        @JvmStatic
        fun getVpnClient(context: Context): String =
            when (val client = preferences(context).getString(KEY_VPN_CLIENT, "auto")) {
                "auto", "happ", "incy" -> client
                else -> "auto"
            }

        @JvmStatic
        fun setVpnClient(context: Context, client: String?) {
            preferences(context).edit().putString(KEY_VPN_CLIENT, client?.legacyTrim() ?: "auto").apply()
        }

        /** Извлекает пакет из `app:<packageName>`, иначе возвращает `null`. */
        @JvmStatic
        fun appPackageOf(id: String?): String? {
            val trimmed = id?.legacyTrim() ?: return null
            if (!trimmed.startsWith(APP_PREFIX)) return null
            return trimmed.substring(APP_PREFIX.length).legacyTrim().takeIf(String::isNotEmpty)
        }

        /** Возвращает сохранённый JSON без разбора и нормализации. */
        @JvmStatic
        fun getMenuItemsJson(context: Context): String? = preferences(context).getString(KEY_MENU_ITEMS, null)

        @JvmStatic
        fun setMenuItemsJson(context: Context, json: String?) {
            preferences(context).edit().putString(KEY_MENU_ITEMS, json).apply()
        }

        /** Синхронная запись из фонового редактора; при ошибке возвращаем прежний снимок в память. */
        internal fun commitMenuItems(context: Context, items: JSONArray) {
            val prefs = preferences(context)
            val previous = prefs.getString(KEY_MENU_ITEMS, null)
            if (!prefs.edit().putString(KEY_MENU_ITEMS, items.toString()).commit()) {
                prefs.edit().putString(KEY_MENU_ITEMS, previous).apply()
                throw java.io.IOException("Could not persist menu")
            }
        }

        /** Читает элементы меню или подставляет исходные четыре пункта, не обновляя настройки. */
        @JvmStatic
        fun getMenuItems(context: Context?): JSONArray {
            try {
                val raw = context?.let(::getMenuItemsJson)
                if (raw != null && raw.legacyTrim().isNotEmpty()) return JSONArray(raw)
            } catch (_: Exception) {
                // Повреждённые настройки не мешают открыть меню и не переписываются на чтении.
            }
            return JSONArray()
                .put(SPECIAL_TORCH)
                .put(SPECIAL_CAMERA)
                .put(SPECIAL_VPN)
                .put(SPECIAL_CIRCLE_TO_SEARCH)
        }

        @JvmStatic
        fun saveMenuItems(context: Context, items: JSONArray) {
            setMenuItemsJson(context, items.toString())
        }

        /** Резервное состояние фонарика, когда CameraManager.TorchCallback недоступен. */
        @JvmStatic
        fun isTorchOn(context: Context): Boolean = preferences(context).getBoolean(KEY_TORCH_ON, false)

        @JvmStatic
        fun setTorchOn(context: Context, on: Boolean) {
            preferences(context).edit().putBoolean(KEY_TORCH_ON, on).apply()
        }

        @JvmStatic
        fun getLastTriggerLog(context: Context): String? = preferences(context).getString(KEY_LAST_TRIGGER, "")

        @JvmStatic
        fun clearTriggerLog(context: Context) {
            preferences(context).edit().remove(KEY_LAST_TRIGGER).apply()
        }

        /**
         * Записывает последний запуск, ограничивая длину отдельных значений и всего журнала.
         *
         * @param context контекст приложения; если отсутствует, журнал не записывается
         * @param intent входящий запрос, из которого добавляются действие и параметры
         * @param launchedTarget цель запуска или `null`, если её ещё нет
         * @param status результат запуска для диагностики
         */
        @JvmStatic
        @Suppress("DEPRECATION")
        fun saveTriggerLog(context: Context?, intent: Intent?, launchedTarget: String?, status: String?) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val log = StringBuilder()
                log.append("Time: ").append(sdf.format(Date())).append('\n')
                if (intent != null) {
                    log.append("Action: ").append(safe(intent.action, LOG_MAX_EXTRA_VALUE)).append('\n')
                    val extras = intent.extras
                    if (extras != null && !extras.isEmpty) {
                        log.append("Extras: ")
                        for (key in extras.keySet()) {
                            val value = java.lang.String.valueOf(extras.get(key))
                            log.append(key).append('=').append(safe(value, LOG_MAX_EXTRA_VALUE)).append("; ")
                        }
                        log.append('\n')
                    }
                }
                log.append("Target: ").append(safe(launchedTarget, LOG_MAX_EXTRA_VALUE)).append('\n')
                log.append("Status: ").append(safe(status, LOG_MAX_EXTRA_VALUE))

                val written = safe(log.toString(), LOG_MAX_TOTAL)
                if (context != null) preferences(context).edit().putString(KEY_LAST_TRIGGER, written).apply()
            } catch (_: Exception) {
                // Ошибка диагностики не должна прерывать действие.
            }
        }

        private fun safe(value: String?, maxChars: Int): String = when {
            value == null -> ""
            value.length <= maxChars -> value
            else -> value.substring(0, maxChars - 3) + "..."
        }
    }
}
