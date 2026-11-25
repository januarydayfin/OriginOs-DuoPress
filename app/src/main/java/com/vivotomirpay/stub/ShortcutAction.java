package com.vivotomirpay.stub;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/** Неизменяемое значение действия, запускающего выбранный shortcut приложения. */
public final class ShortcutAction {

    public static final String PREFIX = "shortcut:";

    private static final String UTF_8 = "UTF-8";

    private final String packageName;
    private final String shortcutId;
    private final String label;

    /**
     * Создаёт действие. Имя пакета и ID не обрезаются через {@code trim()}, чтобы не менять
     * их идентичность, но значения из одних пробельных символов запрещены. Пустой label
     * нормализуется в пустую строку.
     */
    public ShortcutAction(String packageName, String shortcutId, String label) {
        if (isBlank(packageName)) {
            throw new IllegalArgumentException("packageName must not be null or blank");
        }
        if (isBlank(shortcutId)) {
            throw new IllegalArgumentException("shortcutId must not be null or blank");
        }
        this.packageName = packageName;
        this.shortcutId = shortcutId;
        this.label = label == null ? "" : label;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getShortcutId() {
        return shortcutId;
    }

    public String getLabel() {
        return label;
    }

    /** Возвращает true, если строка имеет префикс shortcut-действия. */
    public static boolean isAction(String actionId) {
        return actionId != null && actionId.startsWith(PREFIX);
    }

    /**
     * Разбирает строку формата {@code shortcut:<package>:<id>:<label>}.
     * Все три значения хранятся в URL-encoded UTF-8: двоеточия внутри значений не смешиваются
     * с разделителями, а {@code +} однозначно отличается от закодированного им пробела.
     * При некорректном формате или кодировании возвращает null.
     */
    public static ShortcutAction parse(String actionId) {
        if (!isAction(actionId)) {
            return null;
        }

        String[] fields = actionId.substring(PREFIX.length()).split(":", -1);
        if (fields.length != 3) {
            return null;
        }

        try {
            return new ShortcutAction(
                    decode(fields[0]),
                    decode(fields[1]),
                    decode(fields[2]));
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return null;
        }
    }

    /** Возвращает каноническое представление для сохранения в настройках. */
    public String toActionId() {
        return PREFIX + encode(packageName) + ":" + encode(shortcutId) + ":" + encode(label);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ShortcutAction)) {
            return false;
        }
        ShortcutAction that = (ShortcutAction) other;
        return packageName.equals(that.packageName)
                && shortcutId.equals(that.shortcutId)
                && label.equals(that.label);
    }

    @Override
    public int hashCode() {
        int result = packageName.hashCode();
        result = 31 * result + shortcutId.hashCode();
        result = 31 * result + label.hashCode();
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is not supported", e);
        }
    }

    private static String decode(String value) throws UnsupportedEncodingException {
        return URLDecoder.decode(value, UTF_8);
    }
}
