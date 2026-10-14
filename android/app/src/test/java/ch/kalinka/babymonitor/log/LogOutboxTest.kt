package ch.kalinka.babymonitor.log

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogOutboxTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun outbox(maxBytes: Long = 1_000_000) = LogOutbox(folder.root, maxBytes)

    @Test
    fun `a batch holds the lines in the order they were written`() {
        val outbox = outbox()
        outbox.append("one")
        outbox.append("two")

        assertEquals(listOf("one", "two"), outbox.nextBatch())
    }

    @Test
    fun `a batch that was not sent is offered again before anything newer`() {
        val outbox = outbox()
        outbox.append("old")
        outbox.nextBatch()
        outbox.append("new")

        assertEquals(listOf("old"), outbox.nextBatch())
    }

    @Test
    fun `once sent, the next batch is what was written meanwhile`() {
        val outbox = outbox()
        outbox.append("old")
        outbox.markSent(outbox.nextBatch().size)
        outbox.append("new")

        assertEquals(listOf("new"), outbox.nextBatch())
    }

    @Test
    fun `a partly sent batch keeps the rest`() {
        val outbox = outbox()
        listOf("one", "two", "three").forEach(outbox::append)
        outbox.nextBatch()
        outbox.markSent(2)

        assertEquals(listOf("three"), outbox.nextBatch())
    }

    @Test
    fun `lines survive the app starting again`() {
        outbox().append("before the crash")

        assertEquals(listOf("before the crash"), outbox().nextBatch())
    }

    @Test
    fun `past the cap new lines are dropped and the first ones kept`() {
        val outbox = outbox(maxBytes = 10)
        outbox.append("first line")
        outbox.append("second line")

        assertEquals(listOf("first line"), outbox.nextBatch())
    }
}
