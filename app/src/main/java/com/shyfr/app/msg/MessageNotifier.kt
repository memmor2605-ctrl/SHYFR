package com.shyfr.app.msg

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Powiadomienie bez treści: ani nadawcy, ani fragmentu wiadomości (ekran blokady, logi systemowe, push). */
object MessageNotifier {
    private const val CH = "shyfr_messages_v2"

    fun notifyNew(ctx: Context, count: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Wiadomości SHYFR 2.0", NotificationManager.IMPORTANCE_DEFAULT))
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { PendingIntent.getActivity(ctx, 0, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE) }
        val n = NotificationCompat.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("SHYFR").setContentText(if (count == 1) "Nowa wiadomość" else "Nowe wiadomości")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build()
        nm.notify(4101, n)
    }
}
