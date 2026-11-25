package com.vivotomirpay.stub

import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Действие, запускающее ярлык приложения. Имя пакета и ID проверяются, но не обрезаются,
 * чтобы не менять их идентичность. Отсутствующая подпись становится пустой строкой.
 *
 * @param packageName имя пакета, которому принадлежит ярлык
 * @param shortcutId идентификатор ярлыка внутри пакета
 * @param label подпись ярлыка для показа в настройках
 * @throws IllegalArgumentException если имя пакета или идентификатор отсутствует либо пуст после Java `trim()`
 */
class ShortcutAction(packageName: String?, shortcutId: String?, label: String?) {
    val packageName: String
    val shortcutId: String
    val label: String

    init {
        require(packageName != null && packageName.trim { it <= ' ' }.isNotEmpty()) {
            "packageName must not be null or blank"
        }
        require(shortcutId != null && shortcutId.trim { it <= ' ' }.isNotEmpty()) {
            "shortcutId must not be null or blank"
        }
        this.packageName = packageName
        this.shortcutId = shortcutId
        this.label = label.orEmpty()
    }

    /** Возвращает каноническое представление для сохранения в настройках. */
    fun toActionId(): String = PREFIX + encode(packageName) + ":" + encode(shortcutId) + ":" + encode(label)

    override fun equals(other: Any?): Boolean =
        this === other || other is ShortcutAction &&
            packageName == other.packageName && shortcutId == other.shortcutId && label == other.label

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + shortcutId.hashCode()
        result = 31 * result + label.hashCode()
        return result
    }

    companion object {
        const val PREFIX = "shortcut:"

        // Перегрузки с именем кодировки доступны на API 25.
        private const val UTF_8 = "UTF-8"

        /** Проверяет только префикс, не разбирая поля. */
        @JvmStatic
        fun isAction(actionId: String?): Boolean = actionId != null && actionId.startsWith(PREFIX)

        /**
         * Разбирает `shortcut:<package>:<id>:<label>` с URL-кодированием UTF-8.
         * Пустое последнее поле допустимо.
         *
         * @param actionId сохранённая строка действия
         * @return ярлык или `null`, если формат или кодирование неверны
         */
        @JvmStatic
        fun parse(actionId: String?): ShortcutAction? {
            if (actionId == null || !isAction(actionId)) return null
            val fields = actionId.substring(PREFIX.length).split(':')
            if (fields.size != 3) return null
            return try {
                ShortcutAction(decode(fields[0]), decode(fields[1]), decode(fields[2]))
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: UnsupportedEncodingException) {
                null
            }
        }

        private fun encode(value: String): String = try {
            URLEncoder.encode(value, UTF_8)
        } catch (error: UnsupportedEncodingException) {
            throw IllegalStateException("UTF-8 is not supported", error)
        }

        private fun decode(value: String): String = URLDecoder.decode(value, UTF_8)
    }
}
