package com.example.data.ai

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck

private const val TAG = "AppCheckInit"

/**
 * Initializes Firebase and installs the provider compiled for the active build type.
 * The debug and release provider implementations live in their respective source sets,
 * so the debug provider is not packaged in release builds.
 */
fun configureAppCheck(context: Context): Boolean {
    return try {
        val firebaseApp = FirebaseApp.getApps(context).firstOrNull()
            ?: FirebaseApp.initializeApp(context)
            ?: error(
                "Default FirebaseApp could not be initialized. " +
                    "Check app/google-services.json and its Android package name."
            )

        val appCheck = FirebaseAppCheck.getInstance(firebaseApp)
        installBuildSpecificAppCheckProvider(appCheck)
        appCheck.setTokenAutoRefreshEnabled(true)
        true
    } catch (error: Exception) {
        Log.e(TAG, "Firebase App Check initialization failed.", error)
        false
    }
}
