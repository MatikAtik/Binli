package com.example.binli

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

object AuthGateway {
    fun get(context: Context): FirebaseAuth? {
        if (BuildConfig.FIREBASE_API_KEY.isBlank() || BuildConfig.FIREBASE_APP_ID.isBlank() || BuildConfig.FIREBASE_PROJECT_ID.isBlank()) return null
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == "binly-auth" }
            ?: FirebaseApp.initializeApp(context.applicationContext, FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).build(), "binly-auth")
        return FirebaseAuth.getInstance(app)
    }
}
