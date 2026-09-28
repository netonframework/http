package neton.http.h1

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WriteGateTest {
    @Test
    fun waitersRunInOrderAfterTheHolder() = runReactor {
        val gate = WriteGate()
        val order = ArrayList<Int>()
        gate.lock()
        val a = launch(start = CoroutineStart.UNDISPATCHED) { gate.withLock { order.add(1) } }
        val b = launch(start = CoroutineStart.UNDISPATCHED) { gate.withLock { order.add(2) } }
        order.add(0)
        gate.unlock()
        a.join(); b.join()
        assertEquals(listOf(0, 1, 2), order)
        assertFalse(gate.held)
    }

    @Test
    fun cancelledWaiterIsSkippedOrPassesTheGateOn() = runReactor {
        val gate = WriteGate()
        gate.lock()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { gate.withLock { error("must not run") } }
        var ran = false
        val next = launch(start = CoroutineStart.UNDISPATCHED) { gate.withLock { ran = true } }
        cancelled.cancel()                    // cancelled while queued
        gate.unlock()
        next.join(); cancelled.join()
        assertEquals(true, ran)
        assertFalse(gate.held)
        // Handed the gate and cancelled before running: the gate is passed on, not lost.
        gate.lock()
        val w = launch(start = CoroutineStart.UNDISPATCHED) { gate.withLock { } }
        var after = false
        val w2 = launch(start = CoroutineStart.UNDISPATCHED) { gate.withLock { after = true } }
        gate.unlock()                          // grants w, which has not run yet
        w.cancel()
        w.join(); yield(); w2.join()
        assertEquals(true, after)
        assertFalse(gate.held)
    }
}
