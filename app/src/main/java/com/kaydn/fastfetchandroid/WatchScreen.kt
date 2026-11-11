package com.kaydn.fastfetchandroid

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration

// ==================================================
// WATCH SCREEN
// ==================================================
//
// The app is landscape everywhere (see the manifest), except on watches.
// A watch has no landscape, and a watch that obeys the request turns its
// whole screen sideways (seen on the Wear OS emulator).

internal object WatchScreen {

    fun isWatch(context: Context): Boolean {
        val type =
            context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK

        return type == Configuration.UI_MODE_TYPE_WATCH
    }

    /**
     * On a watch, drops the manifest's landscape request, so the watch
     * keeps its own orientation. Call it in onCreate, before the first
     * frame. Does nothing on other devices.
     */
    fun keepUpright(activity: Activity) {
        if (isWatch(activity)) {
            activity.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
