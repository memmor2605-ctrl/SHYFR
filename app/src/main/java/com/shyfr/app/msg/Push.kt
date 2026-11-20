package com.shyfr.app.msg

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.shyfr.app.BuildConfig
import com.shyfr.app.api.V2Backend
import kotlin.concurrent.thread

/**
 * FCM. Konfiguracja Firebase pochodzi WYŁĄCZNIE z parametrów budowania (-PfcmAppId, -PfcmApiKey, -PfcmProjectId, -PfcmSenderId); w repozytorium nie ma żadnych kluczy.
 * Brak parametrów = push wyłączony, aplikacja działa dalej na WorkManagerze (bez natychmiastowości).
 * Sygnał push NIE niesie treści: [ShyfrMessagingService.onMessageReceived] ignoruje ładunek i tylko zleca synchronizację.
 */
object PushRegistrar {
    fun configured() = BuildConfig.FCM_APP_ID.isNotBlank() && BuildConfig.FCM_API_KEY.isNotBlank() && BuildConfig.FCM_PROJECT_ID.isNotBlank()

    fun ensureFirebase(ctx: Context): Boolean {
        if (!configured()) return false
        if (FirebaseApp.getApps(ctx).isEmpty()) FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FCM_APP_ID).setApiKey(BuildConfig.FCM_API_KEY).setProjectId(BuildConfig.FCM_PROJECT_ID)
            .apply { if (BuildConfig.FCM_SENDER_ID.isNotBlank()) setGcmSenderId(BuildConfig.FCM_SENDER_ID) }.build())
        return true
    }

    /** Pobiera token i rejestruje go na serwerze dla bieżącego urządzenia (po zalogowaniu i po zmianie tokenu). */
    fun register(ctx: Context) {
        if (!ensureFirebase(ctx)) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            thread { runCatching { MessengerGraph.get(ctx).client.registerPushToken(token) } }
        }
    }

    /** Przy wylogowaniu: najpierw serwer (usuwa token urządzenia), potem lokalny token FCM. Wołać PRZED client.logout(). */
    fun unregister(ctx: Context) {
        if (!ensureFirebase(ctx)) return
        runCatching { MessengerGraph.get(ctx).client.deletePushToken() }
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }
}

class ShyfrMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val ctx = applicationContext
        runCatching { if (V2Backend.client(ctx).isSignedIn()) PushRegistrar.register(ctx) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        SyncScheduler.kick(applicationContext)    // treść ładunku jest ignorowana celowo
    }
}
