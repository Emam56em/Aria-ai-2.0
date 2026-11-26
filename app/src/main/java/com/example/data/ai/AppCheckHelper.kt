package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

/**
 * Uses the Firebase Debug provider only in debug APKs so a test device can be
 * allow-listed in Firebase Console. Release builds continue to use reCAPTCHA
 * Enterprise; never ship a debug provider in production.
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
        if (BuildConfig.DEBUG) {
            appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.w(
                TAG,
                "Firebase App Check DEBUG provider installed. " +
                    "Register the debug token printed by DebugAppCheckProvider in Firebase Console."
            )
        } else {
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

            appCheck.installAppCheckProviderFactory(
                RecaptchaAppCheckProviderFactory.getInstance(siteKey)
            )
            Log.i(TAG, "Firebase App Check reCAPTCHA Enterprise provider installed for release.")
        }

        appCheck.setTokenAutoRefreshEnabled(true)
        true
    } catch (error: Exception) {
        Log.e(TAG, "Firebase/App Check initialization failed; continuing app startup.", error)
        false
    }
}
