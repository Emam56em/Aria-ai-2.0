package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

private const val TAG = "AppCheckInit"

/**
 * Installs the appropriate Firebase App Check provider before Firebase AI is accessed.
 * Debug builds use the Debug provider so its token can be registered in Firebase Console.
 * Release builds use Play Integrity for device attestation.
 */
fun configureAppCheck(context: Context): Boolean {
    return try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        val firebaseApp = FirebaseApp.getInstance()
        val appCheck = FirebaseAppCheck.getInstance(firebaseApp)

        val providerFactory = if (BuildConfig.DEBUG) {
            Log.i(TAG, "Installing Firebase App Check Debug provider.")
            DebugAppCheckProviderFactory.getInstance()
        } else {
            Log.i(TAG, "Installing Firebase App Check Play Integrity provider.")
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }

        appCheck.installAppCheckProviderFactory(providerFactory)
        appCheck.setTokenAutoRefreshEnabled(true)
        true
    } catch (error: Exception) {
        Log.e(TAG, "Firebase App Check initialization failed.", error)
        false
    }
}
