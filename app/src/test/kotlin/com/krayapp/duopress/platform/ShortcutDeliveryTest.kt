package com.krayapp.duopress.platform

import com.krayapp.duopress.R
import com.krayapp.duopress.shortcutLaunchStatus
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutDeliveryTest {
    @Test
    fun cancelledPreparationDoesNotSendThePendingIntent() = runTest {
        val deliveries = mutableListOf<String>()
        launch {
            val job = coroutineContext.job
            sendPreparedShortcut(
                prepare = { job.cancel(); "obsolete" },
                send = deliveries::add,
            )
        }.join()
        assertEquals(emptyList<String>(), deliveries)
    }

    @Test
    fun android11ReportsRequestRatherThanSuccessfulLaunch() {
        assertEquals(R.string.log_shortcut_requested, shortcutLaunchStatus(30))
        assertEquals(R.string.log_shortcut_ok, shortcutLaunchStatus(31))
    }
}
