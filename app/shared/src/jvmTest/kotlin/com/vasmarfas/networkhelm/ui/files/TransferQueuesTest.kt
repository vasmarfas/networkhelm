package com.vasmarfas.networkhelm.ui.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TransferQueuesTest {
    @Test
    fun aTransferOfARouterGoesOnWithoutItsWindowAndEndsWithTheSession() = runBlocking {
        val queues = TransferQueues()
        val gate = CompletableDeferred<Long>()
        queues.of("file").add("rd-test.rsc", upload = false) { gate.await() }
        assertSame(queues.of("file"), queues.of("file"))
        val transfer = queues.of("file").list.single()
        withTimeout(5_000) { while (transfer.state !is TransferState.Running) delay(10) }
        gate.complete(42)
        withTimeout(5_000) { while (transfer.state !is TransferState.Done) delay(10) }
        assertEquals(42, (transfer.state as TransferState.Done).bytes)

        queues.of("file").add("rd-test-2.rsc", upload = true) { CompletableDeferred<Long>().await() }
        val waiting = queues.of("file").list.last()
        withTimeout(5_000) { while (waiting.state !is TransferState.Running) delay(10) }
        queues.close()
        withTimeout(5_000) { while (waiting.state !is TransferState.Cancelled) delay(10) }
    }
}
