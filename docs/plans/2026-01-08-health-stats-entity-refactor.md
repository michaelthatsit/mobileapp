# Health Stats Entity Refactor Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Refactor HealthStatsSync.kt to use @GenerateRoomEntity pattern, replacing manual BlobDB sends with automatic Room-based syncing.

**Architecture:** Create HealthStat entity with @GenerateRoomEntity annotation. Replace all sendHealthStat* functions with a single updateHealthStatsInDatabase() that computes and stores 16 health statistics. BlobDB infrastructure automatically syncs to watch.

**Tech Stack:** Kotlin, Room, @GenerateRoomEntity annotation processor, BlobDB syncing infrastructure

---

## Task 1: Create HealthStat Entity

**Files:**
- Create: `libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/database/entity/HealthStat.kt`

**Step 1: Create HealthStat entity file**

Create new file with complete implementation:

```kotlin
package io.rebble.libpebblecommon.database.entity

import coredev.BlobDatabase
import coredev.GenerateRoomEntity
import io.rebble.libpebblecommon.database.dao.BlobDbItem
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag

@GenerateRoomEntity(
    primaryKey = "key",
    databaseId = BlobDatabase.HealthStats,
    windowBeforeSecs = -1,
    windowAfterSecs = -1,
    onlyInsertAfter = false,
    sendDeletions = true,
)
data class HealthStat(
    val key: String,
    val payload: ByteArray,
    val lastUpdated: Long = System.currentTimeMillis(),
) : BlobDbItem {
    override fun key(): UByteArray =
        key.encodeToByteArray().toUByteArray()

    override fun value(platform: WatchType, capabilities: Set<ProtocolCapsFlag>): UByteArray =
        payload.toUByteArray()

    override fun recordHashCode(): Int =
        key.hashCode() + payload.contentHashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as HealthStat

        if (key != other.key) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
```

**Step 2: Commit entity creation**

```bash
git add libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/database/entity/HealthStat.kt
git commit -m "feat: add HealthStat entity with @GenerateRoomEntity annotation"
```

---

## Task 2: Add HealthStat DAO to Database

**Files:**
- Modify: `libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/database/Database.kt`

**Step 1: Find the healthDao() function**

Look for the pattern of dao getter functions in Database.kt (around line 330 based on similar DAOs).

**Step 2: Add healthStatDao() getter**

Add after `healthDao()` getter:

```kotlin
@Database.RealDatabaseComponent
fun healthStatDao(): HealthStatDao
```

**Step 3: Commit Database.kt change**

```bash
git add libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/database/Database.kt
git commit -m "feat: add healthStatDao getter to Database"
```

---

## Task 3: Add HealthStat DAO to BlobDbDaos

**Files:**
- Modify: `libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/connection/endpointmanager/blobdb/BlobDB.kt`

**Step 1: Add healthStatDao parameter to BlobDbDaos constructor**

Find the BlobDbDaos class (around line 40) and add parameter:

```kotlin
data class BlobDbDaos(
    private val lockerEntryDao: LockerEntryRealDao,
    private val notificationsDao: TimelineNotificationRealDao,
    private val timelinePinDao: TimelinePinRealDao,
    private val timelineReminderDao: TimelineReminderRealDao,
    private val notificationAppRealDao: NotificationAppRealDao,
    private val watchSettingsDao: WatchSettingsDao,
    private val healthStatDao: HealthStatDao,  // Add this
    private val platformConfig: PlatformConfig,
) {
```

**Step 2: Add healthStatDao to the set in get() method**

Find the get() method and add to buildSet:

```kotlin
fun get(): Set<BlobDbDao<BlobDbRecord>> = buildSet {
    add(lockerEntryDao)
    add(notificationsDao)
    add(timelinePinDao)
    add(timelineReminderDao)
    add(watchSettingsDao)
    add(healthStatDao)  // Add this
    if (platformConfig.syncNotificationApps) {
        add(notificationAppRealDao)
    }
    // because typing
} as Set<BlobDbDao<BlobDbRecord>>
```

**Step 3: Commit BlobDbDaos change**

```bash
git add libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/connection/endpointmanager/blobdb/BlobDB.kt
git commit -m "feat: add HealthStatDao to BlobDbDaos for automatic syncing"
```

---

## Task 4: Update LibPebbleModule DI Configuration

**Files:**
- Modify: `libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/di/LibPebbleModule.kt`

**Step 1: Add healthStatDao() singleton**

Find the database DAO declarations (around line 330) and add:

```kotlin
single { get<Database>().healthStatDao() }
```

**Step 2: Commit DI configuration change**

```bash
git add libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/di/LibPebbleModule.kt
git commit -m "feat: register healthStatDao in dependency injection"
```

---

## Task 5: Add Helper Extension for DailyMovementAggregate

**Files:**
- Modify: `libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/services/HealthStatsSync.kt`

**Step 1: Add toHealthAggregates() extension function**

Add this extension function at the bottom of the file (before constants):

```kotlin
private fun DailyMovementAggregate.toHealthAggregates(): HealthAggregates =
    HealthAggregates(
        steps = this.steps,
        activeGramCalories = this.activeGramCalories,
        restingGramCalories = this.restingGramCalories,
        activeMinutes = this.activeMinutes,
        distanceCm = this.distanceCm
    )
```

**Step 2: Commit extension function**

```bash
git add libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/services/HealthStatsSync.kt
git commit -m "feat: add DailyMovementAggregate.toHealthAggregates() extension"
```

---

## Task 6: Create updateHealthStatsInDatabase Function

**Files:**
- Modify: `libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/services/HealthStatsSync.kt`

**Step 1: Add import for HealthStat**

At top of file, add import:

```kotlin
import io.rebble.libpebblecommon.database.entity.HealthStat
import io.rebble.libpebblecommon.database.entity.HealthStatDao
```

**Step 2: Create updateHealthStatsInDatabase function**

Replace the sendHealthStatsToWatch function (lines 25-65) with:

```kotlin
/** Updates health stats in database for automatic syncing to watch */
internal suspend fun updateHealthStatsInDatabase(
    healthDao: HealthDao,
    healthStatDao: HealthStatDao,
    today: LocalDate,
    startDate: LocalDate,
    timeZone: TimeZone
): Boolean {
    val averages = calculateHealthAverages(healthDao, startDate, today, timeZone)
    if (averages.rangeDays <= 0) {
        logger.w { "HEALTH_STATS: Invalid date range (start=$startDate end=$today)" }
        return false
    }

    val averageSleepHours = averages.averageSleepSecondsPerDay / 3600.0

    logger.i {
        "HEALTH_STATS: 30-day averages window $startDate to $today (range=${averages.rangeDays} days, step days=${averages.stepDaysWithData}, sleep days=${averages.sleepDaysWithData})"
    }
    logger.i {
        "HEALTH_STATS: Average daily steps = ${averages.averageStepsPerDay} (total: ${averages.totalSteps} steps)"
    }
    logger.i {
        val sleepHrs = (averageSleepHours * 10).toInt() / 10.0
        "HEALTH_STATS: Average sleep = ${sleepHrs} hours (${averages.averageSleepSecondsPerDay} seconds, total: ${averages.totalSleepSeconds} seconds)"
    }

    val stats = mutableListOf<HealthStat>()

    // Add average stats
    stats.add(HealthStat(
        key = KEY_AVERAGE_DAILY_STEPS,
        payload = encodeUInt(averages.averageStepsPerDay.coerceAtLeast(0).toUInt()).toByteArray()
    ))
    stats.add(HealthStat(
        key = KEY_AVERAGE_SLEEP_DURATION,
        payload = encodeUInt(averages.averageSleepSecondsPerDay.coerceAtLeast(0).toUInt()).toByteArray()
    ))

    // Compute weekly movement and sleep data
    val oldestDate = today.minus(DatePeriod(days = MOVEMENT_HISTORY_DAYS - 1))
    val rangeStart = oldestDate.startOfDayEpochSeconds(timeZone)
    val rangeEnd = today.plus(DatePeriod(days = 1)).startOfDayEpochSeconds(timeZone)
    val allAggregates = healthDao.getDailyMovementAggregates(rangeStart, rangeEnd)
    val aggregatesByDayStart =
        allAggregates.associateBy {
            LocalDate.parse(it.day).atStartOfDayIn(timeZone).epochSeconds
        }

    repeat(MOVEMENT_HISTORY_DAYS) { offset ->
        val day = today.minus(DatePeriod(days = offset))
        val dayStart = day.startOfDayEpochSeconds(timeZone)
        val movementKey = MOVEMENT_KEYS[day.dayOfWeek] ?: return@repeat
        val sleepKey = SLEEP_KEYS[day.dayOfWeek] ?: return@repeat

        // Add movement stat
        val aggregate = aggregatesByDayStart[dayStart]
        val movementPayloadData = movementPayload(dayStart, aggregate?.toHealthAggregates())
        stats.add(HealthStat(
            key = movementKey,
            payload = movementPayloadData.toByteArray()
        ))

        // Add sleep stat
        val mainSleep = fetchAndGroupDailySleep(healthDao, dayStart, timeZone)
        val sleepPayloadData = sleepPayload(
            dayStart,
            mainSleep?.totalSleep?.toInt() ?: 0,
            mainSleep?.deepSleep?.toInt() ?: 0,
            mainSleep?.start?.toInt() ?: 0,
            mainSleep?.end?.toInt() ?: 0
        )
        stats.add(HealthStat(
            key = sleepKey,
            payload = sleepPayloadData.toByteArray()
        ))
    }

    // Batch insert all stats
    healthStatDao.insertOrReplace(stats)
    logger.i { "HEALTH_STATS: Updated ${stats.size} stats in database for automatic syncing" }

    return true
}
```

**Step 3: Commit new function**

```bash
git add libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/services/HealthStatsSync.kt
git commit -m "feat: add updateHealthStatsInDatabase to replace direct BlobDB sends"
```

---

## Task 7: Delete Old Send Functions from HealthStatsSync.kt

**Files:**
- Modify: `libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/services/HealthStatsSync.kt`

**Step 1: Delete sendWeeklyMovementData function**

Remove lines 68-152 (entire sendWeeklyMovementData function).

**Step 2: Delete sendTodayMovementData function**

Remove lines 154-188 (entire sendTodayMovementData function).

**Step 3: Delete sendWeeklySleepData function**

Remove lines 190-212 (entire sendWeeklySleepData function).

**Step 4: Delete sendRecentSleepData function**

Remove lines 214-226 (entire sendRecentSleepData function).

**Step 5: Delete sendSingleDaySleep function**

Remove lines 228-262 (entire sendSingleDaySleep function).

**Step 6: Delete sendAverageMonthlySteps function**

Remove lines 317-331 (entire sendAverageMonthlySteps function).

**Step 7: Delete sendAverageMonthlySleep function**

Remove lines 333-351 (entire sendAverageMonthlySleep function).

**Step 8: Delete sendHealthStat function**

Remove lines 353-376 (entire sendHealthStat function and its helper).

**Step 9: Remove BlobDBService import**

Remove this import from top of file:

```kotlin
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
```

**Step 10: Remove unused constants**

Remove these constants:

```kotlin
private const val HEALTH_STATS_BLOB_TIMEOUT_MS = 5_000L
```

**Step 11: Commit deletions**

```bash
git add libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/services/HealthStatsSync.kt
git commit -m "refactor: remove old send* functions, replaced by Room entity pattern"
```

---

## Task 8: Update HealthService to Use New Function

**Files:**
- Modify: `libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/services/HealthService.kt`

**Step 1: Add HealthStatDao parameter to HealthService constructor**

Find the HealthService class constructor (around line 66) and add:

```kotlin
class HealthService(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope,
    private val healthDao: HealthDao,
    private val appRunStateService: AppRunStateService,
    private val blobDBService: BlobDBService,
    private val healthStatDao: HealthStatDao,  // Add this
) : ProtocolService, io.rebble.libpebblecommon.connection.ConnectedPebble.Health {
```

**Step 2: Remove blobDBService parameter since it's no longer needed**

Actually, keep blobDBService for now since it might be used elsewhere in the file. We'll only change the call sites.

**Step 3: Find updateHealthStats function**

Locate the updateHealthStats() function (around line 576).

**Step 4: Replace sendHealthStatsToWatch call**

Change the function call from:

```kotlin
val sent = sendHealthStatsToWatch(healthDao, blobDBService, today, startDate, timeZone)
```

To:

```kotlin
val sent = updateHealthStatsInDatabase(healthDao, healthStatDao, today, startDate, timeZone)
```

**Step 5: Update sendTodayStatsToWatch function**

Find sendTodayStatsToWatch function and replace:

```kotlin
sendTodayMovementData(healthDao, blobDBService, today, timeZone)
```

With:

```kotlin
// Today's data is included in the weekly update, no separate call needed
updateHealthStatsInDatabase(healthDao, healthStatDao, today, today.minus(DatePeriod(days = 29)), timeZone)
```

**Step 6: Commit HealthService changes**

```bash
git add libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/services/HealthService.kt
git commit -m "refactor: use updateHealthStatsInDatabase instead of direct BlobDB sends"
```

---

## Task 9: Build and Test

**Step 1: Build the project**

Run the build to ensure annotation processor generates HealthStatDao:

```bash
./gradlew :libpebble3:build
```

Expected: BUILD SUCCESSFUL (may have warnings but no errors)

**Step 2: Verify HealthStatDao was generated**

Check that the generated DAO exists:

```bash
find . -name "HealthStatDao.kt" -o -name "HealthStatRealDao.kt"
```

Expected: Should find generated DAO file(s) in build directory

**Step 3: Run a smoke test**

If there are existing tests for health syncing, run them:

```bash
./gradlew :libpebble3:test --tests "*Health*"
```

Expected: Tests pass or at least compile (may need test updates)

**Step 4: Commit build verification**

```bash
git commit --allow-empty -m "build: verify HealthStat entity and DAO generation"
```

---

## Task 10: Final Cleanup and Documentation

**Step 1: Update HealthStatsSync.kt file comment**

Add a comment at the top of HealthStatsSync.kt explaining the new approach:

```kotlin
/**
 * Health statistics computation and database storage.
 *
 * Computes 16 health statistics (weekly movement/sleep + averages) and stores them
 * in the HealthStat Room entity. The @GenerateRoomEntity infrastructure automatically
 * syncs these stats to the watch via BlobDB.
 *
 * This replaces the old direct BlobDB sending approach with a declarative Room-based pattern.
 */
```

**Step 2: Add inline comment for payload functions**

Add comment above payload generation functions:

```kotlin
// Payload generation functions - construct binary data for BlobDB
// These are called during stat computation and results are stored in HealthStat entity
```

**Step 3: Commit documentation**

```bash
git add libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/services/HealthStatsSync.kt
git commit -m "docs: update HealthStatsSync.kt with new architecture comments"
```

---

## Verification Checklist

After implementation, verify:

- [ ] HealthStat entity exists with @GenerateRoomEntity annotation
- [ ] HealthStatDao is generated by annotation processor
- [ ] HealthStatDao is registered in Database.kt
- [ ] HealthStatDao is added to BlobDbDaos
- [ ] HealthStatDao is injected into HealthService
- [ ] updateHealthStatsInDatabase() computes and stores all 16 stats
- [ ] Old send* functions are deleted from HealthStatsSync.kt
- [ ] HealthService calls updateHealthStatsInDatabase() instead of old send functions
- [ ] Project builds successfully
- [ ] No BlobDBService parameter in HealthStatsSync.kt functions

## Testing Notes

Manual testing after implementation:
1. Connect a watch and sync health data
2. Check Room database to verify 16 HealthStat rows are created
3. Verify stats appear on watch
4. Test periodic 24-hour updates
5. Test manual sync via sendHealthAveragesToWatch()

The refactor is complete when health stats sync to the watch without any direct BlobDB.send() calls in HealthStatsSync.kt.
