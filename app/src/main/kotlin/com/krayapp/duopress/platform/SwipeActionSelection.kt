package com.krayapp.duopress.platform

import com.krayapp.duopress.PrefsHelper

/** На локскрине отдельным действием может быть только камера или фонарик. */
internal fun resolveSwipeAction(
    locked: Boolean,
    lockscreenEnabled: Boolean,
    lockscreenActionId: String,
    targetId: String,
): String = when {
    !locked || !lockscreenEnabled -> targetId
    lockscreenActionId == PrefsHelper.SPECIAL_CAMERA -> PrefsHelper.SPECIAL_CAMERA
    else -> PrefsHelper.SPECIAL_TORCH
}
