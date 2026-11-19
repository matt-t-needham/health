package computer.bix.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {
    private val now = 1_795_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test fun neverAttemptedIsOverdue() = assertTrue(Scheduler.isOverdue(null, now))

    @Test fun justUnderThresholdIsNotOverdue() =
        assertFalse(Scheduler.isOverdue(now - Scheduler.OVERDUE_MS + 1, now))

    @Test fun atThresholdIsOverdue() = assertTrue(Scheduler.isOverdue(now - Scheduler.OVERDUE_MS, now))

    @Test fun fortyTwoHourGapIsOverdue() = assertTrue(Scheduler.isOverdue(now - 42 * hour, now))

    @Test fun recentAttemptIsNotOverdue() = assertFalse(Scheduler.isOverdue(now - 2 * hour, now))

    /** A wall clock set backwards would otherwise suppress catch-ups until it caught up. */
    @Test fun attemptInTheFutureIsOverdue() = assertTrue(Scheduler.isOverdue(now + hour, now))
}
