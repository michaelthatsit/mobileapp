package io.rebble.libpebblecommon.database.dao

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity

private val logger = Logger.withTag("HealthDaoExt")

/**
 * Inserts health data with priority based on step count. If data already exists for a timestamp,
 * only replaces it if the new data has more steps.
 */
suspend fun HealthDao.insertHealthDataWithPriority(data: List<HealthDataEntity>) {
    var inserted = 0
    var skipped = 0
    var replaced = 0

    data.forEach { newData ->
        val existing = getDataAtTimestamp(newData.timestamp)
        if (existing == null) {
            insertHealthData(listOf(newData))
            inserted++
            logger.d {
                "Inserted new data at timestamp ${newData.timestamp}: ${newData.steps} steps"
            }
        } else if (newData.steps > existing.steps) {
            logger.i {
                "Replacing data at timestamp ${newData.timestamp}: ${existing.steps} steps -> ${newData.steps} steps (gained ${newData.steps - existing.steps} steps)"
            }
            insertHealthData(listOf(newData))
            replaced++
        } else if (newData.steps < existing.steps) {
            logger.d {
                "Skipping data at timestamp ${newData.timestamp}: existing ${existing.steps} steps > new ${newData.steps} steps"
            }
            skipped++
        } else {
            logger.d {
                "Skipping duplicate data at timestamp ${newData.timestamp}: both have ${newData.steps} steps"
            }
            skipped++
        }
    }

    val summary = buildString {
        append("Health data insert complete: ")
        if (inserted > 0) append("$inserted new, ")
        if (replaced > 0) append("$replaced replaced (higher steps), ")
        if (skipped > 0) append("$skipped skipped (lower/equal steps)")
    }
    logger.i { summary }
}

/**
 * Inserts overlay data (sleep, activities) while preventing duplicates.
 * An overlay entry is considered duplicate if it has the same startTime, duration, and type.
 */
suspend fun HealthDao.insertOverlayDataWithDeduplication(data: List<OverlayDataEntity>) {
    var inserted = 0
    var skipped = 0

    data.forEach { newEntry ->
        // Check if an entry with the same startTime, type, and duration already exists
        val existing = getOverlayEntries(
            newEntry.startTime,
            newEntry.startTime + 1, // Search for exact timestamp
            listOf(newEntry.type)
        ).firstOrNull {
            it.startTime == newEntry.startTime &&
            it.duration == newEntry.duration &&
            it.type == newEntry.type
        }

        if (existing == null) {
            insertOverlayData(listOf(newEntry))
            inserted++
            logger.d {
                "Inserted new overlay entry: type=${newEntry.type}, start=${newEntry.startTime}, duration=${newEntry.duration}s"
            }
        } else {
            logger.d {
                "Skipping duplicate overlay entry: type=${newEntry.type}, start=${newEntry.startTime}, duration=${newEntry.duration}s (already exists as ID=${existing.id})"
            }
            skipped++
        }
    }

    val summary = buildString {
        append("Overlay data insert complete: ")
        if (inserted > 0) append("$inserted new, ")
        if (skipped > 0) append("$skipped skipped (duplicates)")
    }
    logger.i { summary }
}

/**
 * Removes duplicate overlay entries from the database.
 * Keeps the first occurrence of each unique (startTime, type, duration) combination.
 */
suspend fun HealthDao.removeDuplicateOverlayEntries() {
    logger.i { "Starting duplicate overlay entry cleanup..." }

    val allEntries = getAllOverlayEntries()
    val seenEntries = mutableSetOf<Triple<Long, Int, Long>>() // (startTime, type, duration)
    val duplicateIds = mutableListOf<Long>()

    allEntries.forEach { entry ->
        val key = Triple(entry.startTime, entry.type, entry.duration)
        if (seenEntries.contains(key)) {
            // This is a duplicate
            duplicateIds.add(entry.id)
            logger.d {
                "Found duplicate: ID=${entry.id}, type=${entry.type}, start=${entry.startTime}, duration=${entry.duration}s"
            }
        } else {
            seenEntries.add(key)
        }
    }

    if (duplicateIds.isNotEmpty()) {
        deleteOverlayEntriesByIds(duplicateIds)
        logger.i { "CLEANUP: Removed ${duplicateIds.size} duplicate overlay entries from database" }
    } else {
        logger.i { "CLEANUP: No duplicate overlay entries found" }
    }
}
