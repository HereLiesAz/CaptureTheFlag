package com.hereliesaz.capturetheflag

import android.app.Activity
import com.hereliesaz.aznavrail.updates.AzUpdateChecker

/** Checks the installed distribution without accessing game data. */
internal fun checkAppUpdate(activity: Activity) {
    AzUpdateChecker.check(activity, "HereLiesAz/CaptureTheFlag")
}
