package com.example.data.ai

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

/**
 * Installs the same Firebase-supported Android attestation provider in every build variant.
 *
 * Debug builds previously used the Debug provider, which requires a device-specific token to be
 * copied from Logcat and allow-listed manually in Firebase Console. That workflow is unsuitable
 * for this phone-only release process and caused unregistered-token failures.
 */
fun configureAppCheck(context: Context) {
    try {
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
    } catch (error: Exception) {
        Log.e(TAG, "Firebase App Check initialization failed.", error)
        throw IllegalStateException(
            "Aria could not initialize Firebase App Check. Check Firebase app registration and reCAPTCHA Enterprise configuration.",
            error
        )
    }
}
