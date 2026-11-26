package com.example.data.ai

import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

internal fun installBuildSpecificAppCheckProvider(appCheck: FirebaseAppCheck) {
    appCheck.installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance()
    )
    Log.i(
        "AppCheckInit",
        "Firebase App Check Debug provider installed. Register the generated debug token in Firebase Console."
    )
}
