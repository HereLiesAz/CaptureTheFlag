package com.hereliesaz.capturetheflag

import android.app.Activity
import com.hereliesaz.aznavrail.updates.AzUpdateChecker
import com.hereliesaz.aznavrail.updates.play.AzPlayUpdates

/** Checks the installed distribution without accessing game data. */
internal fun checkAppUpdate(activity: Activity) {
    AzUpdateChecker.check(activity, "HereLiesAz/CaptureTheFlag", playSource = AzPlayUpdates)
}
