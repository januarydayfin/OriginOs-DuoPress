package com.vivotomirpay.stub;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;

/**
 * Совместимые вызовы HiddenApiBypass для Android 9+ и обычного reflection на более старых API.
 */
final class HiddenApiCompat {

    private static final String[] EXEMPTIONS = {
            "Landroid/content/pm/",
            "Landroid/os/ServiceManager",
            "Lcom/android/internal/app/IVoiceInteractionManagerService",
            "Lcom/android/internal/app/IVoiceInteractionManagerService$Stub"
    };

    private HiddenApiCompat() {
    }

    /**
     * Все потребители используют один union-список, поэтому параллельные вызовы не затирают
     * exemptions друг друга. До Android 9 обращение к HiddenApiBypass не выполняется.
     */
    static void ensureExemptions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28Impl.ensureExemptions(EXEMPTIONS);
        }
    }

    /** Вызывает showSessionFromSession с сигнатурой для текущей версии Android. */
    static Object showSessionFromSession(Class<?> serviceInterface, Object service,
                                         Bundle bundle, boolean withInvocationContext)
            throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Api28Impl.showSessionFromSession(
                    serviceInterface, service, bundle, withInvocationContext);
        }

        Method method = serviceInterface.getMethod(
                "showSessionFromSession", IBinder.class, Bundle.class, int.class);
        return method.invoke(service, null, bundle, 7);
    }

    /** Класс загружается только на Android 9+, где HiddenApiBypass поддерживается. */
    @TargetApi(Build.VERSION_CODES.P)
    private static final class Api28Impl {

        private Api28Impl() {
        }

        static void ensureExemptions(String[] exemptions) {
            HiddenApiBypass.setHiddenApiExemptions(exemptions);
        }

        static Object showSessionFromSession(Class<?> serviceInterface, Object service,
                                             Bundle bundle, boolean withInvocationContext)
                throws Exception {
            if (withInvocationContext) {
                return HiddenApiBypass.invoke(
                        serviceInterface, service, "showSessionFromSession",
                        null, bundle, 7, "hyperOS_home");
            }
            return HiddenApiBypass.invoke(
                    serviceInterface, service, "showSessionFromSession",
                    null, bundle, 7);
        }
    }
}
