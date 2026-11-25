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
 * Firebase's Google Services plugin generates the Firebase options from app/google-services.json.
 * If those options are missing, report a clear configuration error rather than allowing the app to
 * continue until the first Firebase AI request fails with "Default FirebaseApp is not initialized".
 */
fun configureAppCheck(context: Context): Boolean {
    return try {
        val firebaseApp = FirebaseApp.getApps(context).firstOrNull()
            ?: FirebaseApp.initializeApp(context)
            ?: error(
                "Default FirebaseApp could not be initialized. " +
                    "Check app/google-services.json and its Android package name."
            )

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
        Log.e(TAG, "Firebase/App Check initialization failed; continuing app startup.", error)
        false
    }
}
