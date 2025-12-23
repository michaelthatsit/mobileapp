package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.database.dao.HealthDao
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.HealthConstants
import io.rebble.libpebblecommon.health.OverlayType
import io.rebble.libpebblecommon.health.calculateSleepSearchWindow

/**
 * Represents a grouped sleep session combining multiple sleep/deep sleep entries
 */
internal data class SleepSession(
    var start: Long,
    var end: Long,
    var totalSleep: Long = 0,
    var deepSleep: Long = 0
)

/**
 * Groups consecutive sleep entries into sessions.
 * Sleep and DeepSleep entries that are close together (within 1 hour) are part of the same session.
 * Only counts Sleep entries toward total sleep duration, DeepSleep only contributes to deep sleep counter.
 */
internal fun groupSleepSessions(sleepEntries: List<OverlayDataEntity>): List<SleepSession> {
    val sessions = mutableListOf<SleepSession>()

    sleepEntries.sortedBy { it.startTime }.forEach { entry ->
        val overlayType = OverlayType.fromValue(entry.type)
        val entryEnd = entry.startTime + entry.duration

        // Find if this entry belongs to an existing session (within 1 hour of last entry)
        val existingSession = sessions.lastOrNull()?.takeIf {
            entry.startTime <= it.end + SLEEP_SESSION_GAP_SECONDS
        }

        if (existingSession != null) {
            // Add to existing session
            existingSession.end = maxOf(existingSession.end, entryEnd)
            // Only count Sleep entries toward total sleep duration
            if (overlayType == OverlayType.Sleep) {
                existingSession.totalSleep += entry.duration
            }
            // DeepSleep entries only contribute to deep sleep counter
            if (overlayType == OverlayType.DeepSleep) {
                existingSession.deepSleep += entry.duration
            }
        } else {
            // Start new session
            sessions.add(
                SleepSession(
                    start = entry.startTime,
                    end = entryEnd,
                    totalSleep = if (overlayType == OverlayType.Sleep) entry.duration else 0,
                    deepSleep = if (overlayType == OverlayType.DeepSleep) entry.duration else 0
                )
            )
        }
    }

    return sessions
}

/**
 * Fetches sleep entries for a given day and groups them into sessions.
 * "Today's sleep" means you went to bed last night (6 PM yesterday) and woke up this morning/afternoon (2 PM today).
 *
 * @return The longest sleep session (main sleep, not naps) or null if no sleep data
 */
internal suspend fun fetchAndGroupDailySleep(
    healthDao: HealthDao,
    dayStartEpochSec: Long,
    timeZone: kotlinx.datetime.TimeZone
): SleepSession? {
    // Sleep for "today" means you went to bed last night (6 PM yesterday) and woke up this morning/afternoon (2 PM today)
    val (searchStart, searchEnd) = calculateSleepSearchWindow(dayStartEpochSec)

    val sleepEntries = healthDao.getOverlayEntries(searchStart, searchEnd, HealthConstants.SLEEP_TYPES)

    val sessions = groupSleepSessions(sleepEntries)

    // Find the longest sleep session (main sleep, not naps)
    return sessions.maxByOrNull { it.totalSleep }
}

private const val SLEEP_SESSION_GAP_SECONDS = HealthConstants.SLEEP_SESSION_GAP_HOURS * 3600L
