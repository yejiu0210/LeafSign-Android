package cn.onetap.checkin.login

import android.webkit.CookieManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** A timed-out Activity must never let a late cookie clear erase a subsequent account's session. */
internal object WebLoginCookieClear {
    private val fence = CookieClearFence { done -> CookieManager.getInstance().removeAllCookies { done() } }
    suspend fun awaitClear() { fence.pendingClear().await() }
}

/** The operation owns its Deferred; cancellation/timeouts of individual waiters do not cancel it. */
internal class CookieClearFence(private val startClear: (done: () -> Unit) -> Unit) {
    private var pending: CompletableDeferred<Unit>? = null

    @Synchronized fun pendingClear(): Deferred<Unit> {
        pending?.takeIf { !it.isCompleted }?.let { return it }
        val operation = CompletableDeferred<Unit>()
        pending = operation
        try { startClear { operation.complete(Unit) } }
        catch (e: Exception) { operation.completeExceptionally(e) }
        return operation
    }
}
