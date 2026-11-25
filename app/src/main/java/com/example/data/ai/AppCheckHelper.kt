package com.example.data.ai

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

/**
 * Configures Firebase App Check without making attestation setup a fatal app-startup dependency.
 *
 * If the provider or site-key configuration is unavailable, Aria should still open so the user
 * can access the UI and receive a recoverable error when a protected Firebase feature is used.
 */
fun configureAppCheck(context: Context): Boolean {
    return try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        val firebaseApp = FirebaseApp.getInstance()
        val resourceId = context.resources.getIdentifier(
            RECAPTCHA_SITE_KEY_RES,
            "string",
            context.packageName
        )

        check(resourceId != 0) {
            "Firebase App Check reCAPTCHA site key resource is missing."
        }

        val siteKey = context.getString(resourceId).trim()
        check(siteKey.isNotEmpty()) {
            "Firebase App Check reCAPTCHA site key resource is blank."
        }

        FirebaseAppCheck.getInstance(firebaseApp).apply {
            installAppCheckProviderFactory(
                RecaptchaAppCheckProviderFactory.getInstance(siteKey)
            )
            setTokenAutoRefreshEnabled(true)
        }

        Log.i(TAG, "Firebase App Check reCAPTCHA Enterprise provider installed.")
        true
    } catch (error: Exception) {
        // Never crash the whole application because App Check setup failed.
        Log.e(TAG, "Firebase App Check initialization failed; continuing app startup.", error)
        false
    }
}
