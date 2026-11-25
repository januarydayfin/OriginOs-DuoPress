package com.vivotomirpay.stub

import android.annotation.TargetApi
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Kotlin-доступ к прежнему package-private адаптеру для ShizukuShortcuts. */
@JvmSynthetic
internal fun ensureLegacyHiddenApiExemptions() = HiddenApiCompat.ensureExemptions()

/** Совместимые вызовы HiddenApiBypass для Android 9+ и обычного reflection на более старых API. */
private object HiddenApiCompat {
    private val EXEMPTIONS = arrayOf(
        "Landroid/content/pm/",
        "Landroid/os/ServiceManager",
        "Lcom/android/internal/app/IVoiceInteractionManagerService",
        "Lcom/android/internal/app/IVoiceInteractionManagerService\$Stub",
    )

    /**
     * Все потребители используют один union-список, поэтому параллельные вызовы не затирают
     * exemptions друг друга. До Android 9 обращение к HiddenApiBypass не выполняется.
     */
    @JvmStatic
    fun ensureExemptions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28Impl.ensureExemptions(EXEMPTIONS)
        }
    }

    /** Вызывает showSessionFromSession с сигнатурой для текущей версии Android. */
    @JvmStatic
    @Throws(Exception::class)
    fun showSessionFromSession(
        serviceInterface: Class<*>?,
        service: Any?,
        bundle: Bundle?,
        withInvocationContext: Boolean,
    ): Any? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Api28Impl.showSessionFromSession(serviceInterface, service, bundle, withInvocationContext)
        }

        val method = serviceInterface!!.getMethod(
            "showSessionFromSession", IBinder::class.java, Bundle::class.java, Int::class.javaPrimitiveType,
        )
        return method.invoke(service, null, bundle, 7)
    }

    /** Класс загружается только на Android 9+, где HiddenApiBypass поддерживается. */
    @TargetApi(Build.VERSION_CODES.P)
    private object Api28Impl {
        @JvmStatic
        fun ensureExemptions(exemptions: Array<String>) {
            HiddenApiBypass.setHiddenApiExemptions(*exemptions)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun showSessionFromSession(
            serviceInterface: Class<*>?,
            service: Any?,
            bundle: Bundle?,
            withInvocationContext: Boolean,
        ): Any? {
            if (withInvocationContext) {
                return HiddenApiBypass.invoke(
                    serviceInterface!!, service, "showSessionFromSession",
                    null, bundle, 7, "hyperOS_home",
                )
            }
            return HiddenApiBypass.invoke(
                serviceInterface!!, service, "showSessionFromSession",
                null, bundle, 7,
            )
        }
    }
}
