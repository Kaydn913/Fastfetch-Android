package com.kaydn.fastfetchandroid

import android.os.Build

// ==================================================
// EDITION
// ==================================================
//
// This code builds two apps. The normal app (:app) runs on Android 7.1
// and newer, with no upper limit. The legacy edition (:legacy) is for
// Android 4.1-7.0 and refuses newer Android, where the normal app is the
// one to use. Each module sets its own limit in its BuildConfig, so this
// file is shared as is.

internal object Edition {

    /**
     * True if this build may run on this device's Android version. Always
     * true in :app. The legacy bench build skips the check, so it can be
     * tested on a newer device.
     */
    fun supportsThisAndroid(): Boolean {
        return BuildConfig.OS_GATE_BYPASS ||
            Build.VERSION.SDK_INT <= BuildConfig.MAX_ANDROID_SDK
    }
}
