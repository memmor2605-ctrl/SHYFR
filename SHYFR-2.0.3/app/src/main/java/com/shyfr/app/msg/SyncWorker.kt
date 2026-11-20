package com.shyfr.app.msg

import android.content.Context
import androidx.work.*
import com.shyfr.app.api.ApiException
import java.util.concurrent.TimeUnit

/**
 * Synchronizacja w tle: wysyła Outbox, pobiera oczekujące wiadomości, odszyfrowuje lokalnie, potwierdza odbiór.
 * Ograniczenia Androida: Doze i oszczędzanie baterii mogą opóźnić okresową pracę (min. 15 min); natychmiastowość zapewnia dopiero FCM.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val parts = try { MessengerGraph.get(applicationContext) } catch (e: Exception) { return Result.success() }   // brak konfiguracji serwera
        if (!parts.client.isSignedIn()) return Result.success()
        parts.provision?.let { runCatching { it() } }   // uzupełnia pulę kluczy jednorazowych, gdy spadnie poniżej progu
        val r = parts.repo.pump()
        if (r.received > 0 && !MessengerGraph.foreground) MessageNotifier.notifyNew(applicationContext, r.received)
        val e = r.error
        return when {
            e == null -> Result.success()
            e is ApiException.SessionRevoked || e is ApiException.NotSignedIn -> Result.success()   // nie ponawiamy bez sesji
            e.retryable -> Result.retry()                                                          // backoff wykładniczy WorkManagera
            else -> Result.success()
        }
    }
}

object SyncScheduler {
    private const val PERIODIC = "shyfr-sync-periodic"; private const val ONCE = "shyfr-sync-once"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Okresowa synchronizacja (co 15 min, tylko z siecią). Wołać po zalogowaniu i przy starcie. */
    fun schedule(ctx: Context) {
        val w = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, w)
    }

    /** Jednorazowa synchronizacja "teraz, gdy tylko jest sieć": po wysłaniu wiadomości offline, po sygnale FCM, po odzyskaniu internetu. */
    fun kick(ctx: Context) {
        val w = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, w)
    }

    fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC); WorkManager.getInstance(ctx).cancelUniqueWork(ONCE) }
}
