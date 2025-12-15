package io.rebble.libpebblecommon.services

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.OverlayType
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian

private val logger = Logger.withTag("HealthPayloadParser")

/**
 * Parses step/movement data from the watch's health payload
 */
internal fun parseStepsData(payload: ByteArray, itemSize: UShort): List<HealthDataEntity> {
    if (payload.isEmpty() || itemSize.toInt() == 0) {
        logger.w { "Cannot parse steps data: empty payload or zero item size" }
        return emptyList()
    }

    val buffer = DataBuffer(payload.toUByteArray())
    val records = mutableListOf<HealthDataEntity>()

    if (payload.size % itemSize.toInt() != 0) {
        logger.w {
            "Steps payload size (${payload.size}) is not a multiple of item size ($itemSize); parsing what we can"
        }
    }

    val packetCount = payload.size / itemSize.toInt()
    logger.d { "Parsing $packetCount steps packets" }

    for (i in 0 until packetCount) {
        val itemStart = buffer.readPosition
        buffer.setEndian(Endian.Little)

        val version = buffer.getUShort()
        val timestamp = buffer.getUInt()
        buffer.getByte() // unused
        buffer.getUByte() // recordLength
        val recordNum = buffer.getUByte()

        if (!SUPPORTED_STEP_VERSIONS.contains(version)) {
            logger.w {
                "Unsupported health steps record version=$version, skipping remaining payload"
            }
            return records
        }

        var currentTimestamp = timestamp

        repeat(recordNum.toInt()) {
            val steps = buffer.getUByte().toInt()
            val orientation = buffer.getUByte().toInt()
            val intensity = buffer.getUShort().toInt()
            val lightIntensity = buffer.getUByte().toInt()

            val flags = if (version >= VERSION_FW_3_10_AND_BELOW) {
                buffer.getUByte().toInt()
            } else {
                0
            }

            var restingGramCalories = 0
            var activeGramCalories = 0
            var distanceCm = 0
            var heartRate = 0
            var heartRateWeight = 0
            var heartRateZone = 0

            if (version >= VERSION_FW_3_11) {
                restingGramCalories = buffer.getUShort().toInt()
                activeGramCalories = buffer.getUShort().toInt()
                distanceCm = buffer.getUShort().toInt()
            }

            if (version >= VERSION_FW_4_0) {
                heartRate = buffer.getUByte().toInt()
            }

            if (version >= VERSION_FW_4_1) {
                heartRateWeight = buffer.getUShort().toInt()
            }

            if (version >= VERSION_FW_4_3) {
                heartRateZone = buffer.getUByte().toInt()
            }

            records.add(
                HealthDataEntity(
                    timestamp = currentTimestamp.toLong(),
                    steps = steps,
                    orientation = orientation,
                    intensity = intensity,
                    lightIntensity = lightIntensity,
                    activeMinutes = if ((flags and 2) > 0) 1 else 0,
                    restingGramCalories = restingGramCalories,
                    activeGramCalories = activeGramCalories,
                    distanceCm = distanceCm,
                    heartRate = heartRate,
                    heartRateZone = heartRateZone,
                    heartRateWeight = heartRateWeight
                )
            )

            currentTimestamp += 60u
        }

        val consumed = buffer.readPosition - itemStart
        val expected = itemSize.toInt()
        if (consumed < expected) {
            buffer.getBytes(expected - consumed)
        } else if (consumed > expected) {
            logger.w { "Health steps item over-read: consumed=$consumed, expected=$expected" }
        }
    }

    return records
}

/**
 * Parses overlay data (sleep, activities) from the watch's health payload
 */
internal fun parseOverlayData(payload: ByteArray, itemSize: UShort): List<OverlayDataEntity> {
    if (payload.isEmpty() || itemSize.toInt() == 0) {
        logger.w { "Cannot parse overlay data: empty payload or zero item size" }
        return emptyList()
    }

    val buffer = DataBuffer(payload.toUByteArray())
    buffer.setEndian(Endian.Little)
    val records = mutableListOf<OverlayDataEntity>()

    if (payload.size % itemSize.toInt() != 0) {
        logger.w {
            "Overlay payload size (${payload.size}) is not a multiple of item size ($itemSize); parsing what we can"
        }
    }

    val packetCount = payload.size / itemSize.toInt()
    logger.d { "Parsing $packetCount overlay packets" }

    for (i in 0 until packetCount) {
        val itemStart = buffer.readPosition

        val version = buffer.getUShort()
        buffer.getUShort() // unused
        val rawType = buffer.getUShort().toInt()
        val type = OverlayType.fromValue(rawType)

        if (type == null) {
            val remaining = itemSize.toInt() - 6 // already consumed 6 bytes
            if (remaining > 0) buffer.getBytes(remaining)
            logger.w { "Unknown overlay type: $rawType, skipping packet $i" }
            continue
        }

        val offsetUTC = buffer.getUInt()
        val startTime = buffer.getUInt()
        val duration = buffer.getUInt()

        var steps = 0
        var restingKiloCalories = 0
        var activeKiloCalories = 0
        var distanceCm = 0

        if (version < 3.toUShort() || (type != OverlayType.Walk && type != OverlayType.Run)) {
            if (version == 3.toUShort()) {
                // Firmware 3.x includes calorie/distance data even for non-walk/run types
                buffer.getBytes(8)
            }
        } else {
            steps = buffer.getUShort().toInt()
            restingKiloCalories = buffer.getUShort().toInt()
            activeKiloCalories = buffer.getUShort().toInt()
            distanceCm = buffer.getUShort().toInt()
        }

        // Reject incoming sleep data from the watch; we treat the phone DB as the source of truth
        if (type.isSleep()) {
            logger.d { "Dropping incoming sleep overlay (start=$startTime, duration=$duration, type=$type)" }
        } else {
            records.add(
                OverlayDataEntity(
                    startTime = startTime.toLong(),
                    duration = duration.toLong(),
                    type = type.value,
                    steps = steps,
                    restingKiloCalories = restingKiloCalories,
                    activeKiloCalories = activeKiloCalories,
                    distanceCm = distanceCm,
                    offsetUTC = offsetUTC.toInt()
                )
            )
        }

        val consumed = buffer.readPosition - itemStart
        val expected = itemSize.toInt()
        if (consumed < expected) {
            buffer.getBytes(expected - consumed)
        } else if (consumed > expected) {
            logger.w { "Health overlay item over-read: consumed=$consumed, expected=$expected" }
        }
    }

    return records
}

/**
 * Summarizes step data payload for logging
 */
internal fun summarizeStepsPayload(itemSize: Int, payload: ByteArray): String? {
    if (payload.isEmpty() || itemSize <= 0) return null
    val buffer = DataBuffer(payload.toUByteArray())
    var totalSteps = 0
    val heartRateSamples = mutableListOf<Int>()
    var records = 0
    var firstTimestamp: UInt? = null
    var lastTimestamp: UInt? = null

    while (buffer.readPosition < payload.size) {
        val itemStart = buffer.readPosition
        val version = buffer.getUShort()
        val timestamp = buffer.getUInt()
        buffer.getByte() // unused
        buffer.getUByte() // recordLength
        val recordNum = buffer.getUByte()
        var currentTimestamp = timestamp
        records++

        repeat(recordNum.toInt()) {
            totalSteps += buffer.getUByte().toInt()
            buffer.getUByte() // orientation
            buffer.getUShort() // intensity
            buffer.getUByte() // light

            if (version >= VERSION_FW_3_10_AND_BELOW) {
                buffer.getUByte() // flags
            }

            if (version >= VERSION_FW_3_11) {
                buffer.getUShort() // resting calories
                buffer.getUShort() // active calories
                buffer.getUShort() // distance
            }

            var heartRate = 0
            if (version >= VERSION_FW_4_0) {
                heartRate = buffer.getUByte().toInt()
            }

            if (version >= VERSION_FW_4_1) {
                buffer.getUShort() // heartRateWeight
            }

            if (version >= VERSION_FW_4_3) {
                buffer.getUByte() // heartRateZone
            }

            if (heartRate > 0) heartRateSamples.add(heartRate)
            if (firstTimestamp == null) firstTimestamp = currentTimestamp
            lastTimestamp = currentTimestamp
            currentTimestamp += 60u
        }

        val consumed = buffer.readPosition - itemStart
        if (consumed < itemSize) {
            buffer.getBytes(itemSize - consumed)
        } else if (consumed > itemSize) {
            break
        }
    }

    val hrSummary = if (heartRateSamples.isNotEmpty()) {
        val min = heartRateSamples.minOrNull()
        val max = heartRateSamples.maxOrNull()
        val avg = heartRateSamples.average().toInt()
        "hr[min=$min,max=$max,avg=$avg]"
    } else {
        "no HR samples"
    }

    val range = if (firstTimestamp != null && lastTimestamp != null) {
        "range=${firstTimestamp}-${lastTimestamp}"
    } else {
        "range=unknown"
    }

    return "records=$records, steps=$totalSteps, $hrSummary, $range"
}

/**
 * Summarizes overlay data payload for logging
 */
internal fun summarizeOverlayPayload(itemSize: Int, payload: ByteArray): String? {
    if (payload.isEmpty() || itemSize <= 0) return null
    val buffer = DataBuffer(payload.toUByteArray()).apply { setEndian(Endian.Little) }
    var sleepMinutes = 0
    var overlays = 0
    var firstStart: UInt? = null
    var lastStart: UInt? = null

    while (buffer.readPosition < payload.size) {
        val itemStart = buffer.readPosition
        val version = buffer.getUShort()
        buffer.getUShort() // unused
        val rawType = buffer.getUShort().toInt()
        val type = OverlayType.fromValue(rawType)
        buffer.getUInt() // offsetUTC
        val startTime = buffer.getUInt()
        val duration = buffer.getUInt()

        if (version >= 3.toUShort() && (type == OverlayType.Walk || type == OverlayType.Run)) {
            buffer.getUShort() // steps
            buffer.getUShort() // restingKiloCalories
            buffer.getUShort() // activeKiloCalories
            buffer.getUShort() // distanceCm
        } else if (version == 3.toUShort()) {
            buffer.getBytes(8) // calories/distance even for non-walk/run in v3
        }

        overlays++
        if (type != null && type.isSleep()) {
            sleepMinutes += (duration / 60u).toInt()
        }
        if (firstStart == null) firstStart = startTime
        lastStart = startTime

        val consumed = buffer.readPosition - itemStart
        if (consumed < itemSize) {
            buffer.getBytes(itemSize - consumed)
        } else if (consumed > itemSize) {
            break
        }
    }

    return "overlays=$overlays, sleepMinutes=$sleepMinutes, range=${firstStart}-${lastStart}"
}

/**
 * Summarizes heart rate data payload for logging
 */
internal fun summarizeHeartRatePayload(itemSize: Int, payload: ByteArray): String? {
    if (payload.isEmpty() || itemSize <= 0) return null
    val buffer = DataBuffer(payload.toUByteArray()).apply { setEndian(Endian.Little) }
    val hrValues = mutableListOf<Int>()
    while (buffer.readPosition < payload.size) {
        val remaining = payload.size - buffer.readPosition
        if (remaining <= 0) break
        hrValues.add(buffer.getUByte().toInt())
        if (itemSize > 1 && remaining >= itemSize) {
            buffer.getBytes(itemSize - 1)
        } else if (itemSize > 1) {
            buffer.getBytes(remaining - 1)
        }
    }
    if (hrValues.isEmpty()) return null
    val min = hrValues.minOrNull()
    val max = hrValues.maxOrNull()
    val avg = hrValues.average().toInt()
    return "hr[min=$min,max=$max,avg=$avg,samples=${hrValues.size}]"
}

private fun OverlayType.isSleep(): Boolean =
    this == OverlayType.Sleep || this == OverlayType.DeepSleep || this == OverlayType.Nap || this == OverlayType.DeepNap

private val VERSION_FW_3_10_AND_BELOW: UShort = 5u
private val VERSION_FW_3_11: UShort = 6u
private val VERSION_FW_4_0: UShort = 7u
private val VERSION_FW_4_1: UShort = 8u
private val VERSION_FW_4_3: UShort = 13u
private val SUPPORTED_STEP_VERSIONS = setOf(
    VERSION_FW_3_10_AND_BELOW,
    VERSION_FW_3_11,
    VERSION_FW_4_0,
    VERSION_FW_4_1,
    VERSION_FW_4_3
)
