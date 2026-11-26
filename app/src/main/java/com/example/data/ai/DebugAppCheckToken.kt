package com.example.data.ai

import android.content.Context
import com.example.BuildConfig
import com.google.firebase.FirebaseApp

/**
 * Reads the Firebase App Check debug secret from the Debug SDK's local storage.
 *
 * The preference names are implementation details of firebase-appcheck-debug and may
 * change with an SDK update. This helper is strictly debug-build-only and must never
 * be used to expose a token in a release build.
 */
fun readDebugAppCheckToken(context: Context): String? {
    if (!BuildConfig.DEBUG) return null

    val firebaseApp = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: return null
    val preferencesName =
        "com.google.firebase.appcheck.debug.store.${firebaseApp.persistenceKey}"

    return context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        .getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
        ?.takeIf { it.isNotBlank() }
}
