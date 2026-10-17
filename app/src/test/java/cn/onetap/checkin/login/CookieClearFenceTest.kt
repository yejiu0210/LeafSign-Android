package cn.onetap.checkin.login

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CookieClearFenceTest {
    @Test fun waiterTimeoutDoesNotCancelOrReplacePendingPlatformClear(): Unit = runBlocking {
        var starts = 0
        lateinit var complete: () -> Unit
        val fence = CookieClearFence { callback -> starts++; complete = callback }
        val first = fence.pendingClear()
        assertNull(withTimeoutOrNull(5) { first.await(); true })
        assertFalse(first.isCancelled)
        val nextAccount = fence.pendingClear()
        assertSame(first, nextAccount)
        assertEquals(1, starts)
        assertFalse(nextAccount.isCompleted)
        complete()
        nextAccount.await()
        assertTrue(first.isCompleted)
    }
    @Test fun cancellationOfOldActivityDoesNotLetNewActivityOvertakeClear(): Unit = runBlocking {
        lateinit var complete: () -> Unit
        val fence = CookieClearFence { complete = it }
        val first = fence.pendingClear()
        val oldActivity = launch(start = CoroutineStart.UNDISPATCHED) { first.await() }
        oldActivity.cancelAndJoin()
        assertFalse(first.isCancelled)
        assertSame(first, fence.pendingClear())
        complete()
        fence.pendingClear().also { assertFalse(it === first); complete(); it.await() }
    }
    @Test fun nextCompletedSessionStartsItsOwnFreshClearAndFailureIsObservable(): Unit = runBlocking {
        var starts = 0
        val fence = CookieClearFence { done -> starts++; done() }
        fence.pendingClear().await(); fence.pendingClear().await()
        assertEquals(2, starts)
        val broken = CookieClearFence { throw IllegalStateException("test-only") }
        try { broken.pendingClear().await(); fail("Expected platform initialization failure") }
        catch (_: IllegalStateException) { }
    }
}
