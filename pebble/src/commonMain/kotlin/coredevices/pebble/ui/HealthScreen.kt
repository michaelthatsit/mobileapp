package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.koalaplot.core.line.LinePlot2
import io.github.koalaplot.core.style.LineStyle
import io.github.koalaplot.core.util.ExperimentalKoalaPlotApi
import io.github.koalaplot.core.xygraph.DefaultPoint
import io.github.koalaplot.core.xygraph.XYGraph
import io.github.koalaplot.core.xygraph.rememberFloatLinearAxisModel
import io.rebble.libpebblecommon.database.dao.HealthDao
import io.rebble.libpebblecommon.health.OverlayType
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

enum class HealthTimeRange {
    Daily, Weekly, Monthly
}

@Composable
fun HealthScreen(
    navBarNav: NavBarNav,
    topBarParams: TopBarParams
) {
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(false)
        topBarParams.actions {}
        topBarParams.title("Health")
        topBarParams.canGoBack(false)
    }

    val healthDao: HealthDao = koinInject()
    var timeRange by remember { mutableStateOf(HealthTimeRange.Daily) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Time range selector
        TimeRangeSelector(
            selectedRange = timeRange,
            onRangeSelected = { timeRange = it }
        )

        // Steps chart
        HealthMetricCard(
            title = "Steps",
            icon = Icons.Filled.DirectionsRun,
            iconTint = MaterialTheme.colorScheme.primary
        ) {
            StepsChart(healthDao, timeRange)
        }

        // Heart rate chart (if data available)
        HealthMetricCard(
            title = "Heart Rate",
            icon = Icons.Filled.Favorite,
            iconTint = Color(0xFFE91E63)
        ) {
            HeartRateChart(healthDao, timeRange)
        }

        // Sleep chart
        HealthMetricCard(
            title = "Sleep",
            icon = Icons.Filled.Hotel,
            iconTint = Color(0xFF9C27B0)
        ) {
            SleepChart(healthDao, timeRange)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TimeRangeSelector(
    selectedRange: HealthTimeRange,
    onRangeSelected: (HealthTimeRange) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HealthTimeRange.entries.forEach { range ->
            FilterChip(
                selected = selectedRange == range,
                onClick = { onRangeSelected(range) },
                label = { Text(range.name) },
                modifier = Modifier.padding(horizontal = 4.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

@Composable
private fun HealthMetricCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            content()
        }
    }
}

@OptIn(ExperimentalKoalaPlotApi::class)
@Composable
private fun StepsChart(healthDao: HealthDao, timeRange: HealthTimeRange) {
    val scope = rememberCoroutineScope()
    var stepsData by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }
    var totalSteps by remember { mutableStateOf(0L) }

    LaunchedEffect(timeRange) {
        scope.launch {
            val (labels, values, total) = fetchStepsData(healthDao, timeRange)
            stepsData = labels.zip(values)
            totalSteps = total
        }
    }

    if (stepsData.isNotEmpty()) {
        Column {
            Text(
                text = "${totalSteps.toInt()} steps",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Box(modifier = Modifier.height(200.dp).fillMaxWidth().padding(12.dp)) {
                val points = stepsData.mapIndexed { index, (_, value) ->
                    DefaultPoint(index.toFloat(), value)
                }
                val maxY = stepsData.maxOfOrNull { it.second }?.let {
                    if (it > 0f) it * 1.1f else 10f
                } ?: 10f
                val maxX = (stepsData.size - 1).toFloat().coerceAtLeast(1f)

                XYGraph(
                    xAxisModel = rememberFloatLinearAxisModel(0f..maxX),
                    yAxisModel = rememberFloatLinearAxisModel(0f..maxY)
                ) {
                    LinePlot2(
                        data = points,
                        lineStyle = LineStyle(
                            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            strokeWidth = 2.dp
                        )
                    )
                }
            }
        }
    } else {
        Text(
            text = "No steps data available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 32.dp)
        )
    }
}

@OptIn(ExperimentalKoalaPlotApi::class)
@Composable
private fun HeartRateChart(healthDao: HealthDao, timeRange: HealthTimeRange) {
    val scope = rememberCoroutineScope()
    var hrData by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }
    var avgHR by remember { mutableStateOf(0) }

    LaunchedEffect(timeRange) {
        scope.launch {
            val (labels, values, avg) = fetchHeartRateData(healthDao, timeRange)
            hrData = labels.zip(values)
            avgHR = avg
        }
    }

    if (avgHR > 0) {
        Column {
            Text(
                text = "$avgHR bpm avg",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Box(modifier = Modifier.height(200.dp).fillMaxWidth().padding(12.dp)) {
                val points = hrData.mapIndexed { index, (_, value) ->
                    DefaultPoint(index.toFloat(), value)
                }
                val maxY = hrData.maxOfOrNull { it.second }?.let {
                    if (it > 0f) it * 1.1f else 100f
                } ?: 100f
                val maxX = (hrData.size - 1).toFloat().coerceAtLeast(1f)

                XYGraph(
                    xAxisModel = rememberFloatLinearAxisModel(0f..maxX),
                    yAxisModel = rememberFloatLinearAxisModel(0f..maxY)
                ) {
                    LinePlot2(
                        data = points,
                        lineStyle = LineStyle(
                            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFE91E63)),
                            strokeWidth = 2.dp
                        )
                    )
                }
            }
        }
    } else {
        Text(
            text = "No heart rate data available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 32.dp)
        )
    }
}

@OptIn(ExperimentalKoalaPlotApi::class)
@Composable
private fun SleepChart(healthDao: HealthDao, timeRange: HealthTimeRange) {
    val scope = rememberCoroutineScope()
    var sleepData by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }
    var avgSleepHours by remember { mutableStateOf(0f) }

    LaunchedEffect(timeRange) {
        scope.launch {
            val (labels, values, avg) = fetchSleepData(healthDao, timeRange)
            sleepData = labels.zip(values)
            avgSleepHours = avg
        }
    }

    if (avgSleepHours > 0) {
        Column {
            Text(
                text = "%.1f hours avg".format(avgSleepHours),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Box(modifier = Modifier.height(200.dp).fillMaxWidth().padding(12.dp)) {
                val points = sleepData.mapIndexed { index, (_, value) ->
                    DefaultPoint(index.toFloat(), value)
                }
                val maxY = sleepData.maxOfOrNull { it.second }?.let {
                    if (it > 0f) it * 1.1f else 10f
                } ?: 10f
                val maxX = (sleepData.size - 1).toFloat().coerceAtLeast(1f)

                XYGraph(
                    xAxisModel = rememberFloatLinearAxisModel(0f..maxX),
                    yAxisModel = rememberFloatLinearAxisModel(0f..maxY)
                ) {
                    LinePlot2(
                        data = points,
                        lineStyle = LineStyle(
                            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF9C27B0)),
                            strokeWidth = 2.dp
                        )
                    )
                }
            }
        }
    } else {
        Text(
            text = "No sleep data available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 32.dp)
        )
    }
}

// Data fetching functions
private suspend fun fetchStepsData(
    healthDao: HealthDao,
    timeRange: HealthTimeRange
): Triple<List<String>, List<Float>, Long> {
    val timeZone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(timeZone).date

    return when (timeRange) {
        HealthTimeRange.Daily -> {
            // Last 24 hours by hour (cumulative)
            val labels = (0..23).map { hour -> String.format("%02d:00", hour) }
            val values = mutableListOf<Float>()
            val todayStart = today.atStartOfDayIn(timeZone).epochSeconds

            // Fetch cumulative hourly data
            repeat(24) { hour ->
                val hourEnd = todayStart + ((hour + 1) * 3600)
                val steps = healthDao.getTotalStepsExclusiveEnd(todayStart, hourEnd) ?: 0L
                values.add(steps.toFloat())
            }

            val todayEnd = today.plus(DatePeriod(days = 1)).atStartOfDayIn(timeZone).epochSeconds
            val total = healthDao.getTotalStepsExclusiveEnd(todayStart, todayEnd) ?: 0L
            Triple(labels, values, total)
        }

        HealthTimeRange.Weekly -> {
            val labels = mutableListOf<String>()
            val values = mutableListOf<Float>()
            var total = 0L

            repeat(7) { offset ->
                val day = today.minus(DatePeriod(days = 6 - offset))
                labels.add(day.dayOfWeek.name.take(3))

                val start = day.atStartOfDayIn(timeZone).epochSeconds
                val end = day.plus(DatePeriod(days = 1)).atStartOfDayIn(timeZone).epochSeconds
                val steps = healthDao.getTotalStepsExclusiveEnd(start, end) ?: 0L
                values.add(steps.toFloat())
                total += steps
            }
            Triple(labels, values, total)
        }

        HealthTimeRange.Monthly -> {
            val labels = mutableListOf<String>()
            val values = mutableListOf<Float>()
            var total = 0L

            repeat(30) { offset ->
                val day = today.minus(DatePeriod(days = 29 - offset))
                if (offset % 5 == 0) {
                    labels.add("${day.month.name.take(3)} ${day.dayOfMonth}")
                } else {
                    labels.add("")
                }

                val start = day.atStartOfDayIn(timeZone).epochSeconds
                val end = day.plus(DatePeriod(days = 1)).atStartOfDayIn(timeZone).epochSeconds
                val steps = healthDao.getTotalStepsExclusiveEnd(start, end) ?: 0L
                values.add(steps.toFloat())
                total += steps
            }
            Triple(labels, values, total)
        }
    }
}

private suspend fun fetchHeartRateData(
    healthDao: HealthDao,
    timeRange: HealthTimeRange
): Triple<List<String>, List<Float>, Int> {
    val timeZone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(timeZone).date

    return when (timeRange) {
        HealthTimeRange.Daily -> {
            val labels = (0..23).map { hour -> String.format("%02d:00", hour) }
            val values = List(24) { 0f }
            Triple(labels, values, 0)
        }

        HealthTimeRange.Weekly -> {
            val labels = mutableListOf<String>()
            val values = mutableListOf<Float>()
            var count = 0
            var sum = 0

            repeat(7) { offset ->
                val day = today.minus(DatePeriod(days = 6 - offset))
                labels.add(day.dayOfWeek.name.take(3))

                val start = day.atStartOfDayIn(timeZone).epochSeconds
                val end = day.plus(DatePeriod(days = 1)).atStartOfDayIn(timeZone).epochSeconds

                // Get average HR for this day from health_data table
                val avgHR = healthDao.getAverageSteps(start, end)?.toInt() ?: 0
                values.add(avgHR.toFloat())
                if (avgHR > 0) {
                    sum += avgHR
                    count++
                }
            }
            val avg = if (count > 0) sum / count else 0
            Triple(labels, values, avg)
        }

        HealthTimeRange.Monthly -> {
            val labels = mutableListOf<String>()
            val values = mutableListOf<Float>()

            repeat(30) { offset ->
                val day = today.minus(DatePeriod(days = 29 - offset))
                if (offset % 5 == 0) {
                    labels.add("${day.month.name.take(3)} ${day.dayOfMonth}")
                } else {
                    labels.add("")
                }
                values.add(0f)
            }
            Triple(labels, values, 0)
        }
    }
}

private suspend fun fetchSleepData(
    healthDao: HealthDao,
    timeRange: HealthTimeRange
): Triple<List<String>, List<Float>, Float> {
    val timeZone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(timeZone).date

    return when (timeRange) {
        HealthTimeRange.Daily -> {
            // Today's sleep
            val searchStart = today.minus(DatePeriod(days = 1)).atStartOfDayIn(timeZone).epochSeconds + (18 * 3600)
            val searchEnd = today.atStartOfDayIn(timeZone).epochSeconds + (14 * 3600)

            val sleepTypes = listOf(OverlayType.Sleep.value, OverlayType.DeepSleep.value)
            val sleepEntries = healthDao.getOverlayEntries(searchStart, searchEnd, sleepTypes)

            val totalSleepSeconds = sleepEntries.filter { OverlayType.fromValue(it.type) == OverlayType.Sleep }
                .sumOf { it.duration }
            val sleepHours = totalSleepSeconds / 3600f

            Triple(listOf("Today"), listOf(sleepHours), sleepHours)
        }

        HealthTimeRange.Weekly -> {
            val labels = mutableListOf<String>()
            val values = mutableListOf<Float>()
            var totalHours = 0f

            repeat(7) { offset ->
                val day = today.minus(DatePeriod(days = 6 - offset))
                labels.add(day.dayOfWeek.name.take(3))

                val searchStart = day.minus(DatePeriod(days = 1)).atStartOfDayIn(timeZone).epochSeconds + (18 * 3600)
                val searchEnd = day.atStartOfDayIn(timeZone).epochSeconds + (14 * 3600)

                val sleepTypes = listOf(OverlayType.Sleep.value, OverlayType.DeepSleep.value)
                val sleepEntries = healthDao.getOverlayEntries(searchStart, searchEnd, sleepTypes)

                val daySleepSeconds = sleepEntries.filter { OverlayType.fromValue(it.type) == OverlayType.Sleep }
                    .sumOf { it.duration }
                val sleepHours = daySleepSeconds / 3600f
                values.add(sleepHours)
                totalHours += sleepHours
            }
            val avg = totalHours / 7f
            Triple(labels, values, avg)
        }

        HealthTimeRange.Monthly -> {
            val labels = mutableListOf<String>()
            val values = mutableListOf<Float>()
            var totalHours = 0f

            repeat(30) { offset ->
                val day = today.minus(DatePeriod(days = 29 - offset))
                if (offset % 5 == 0) {
                    labels.add("${day.month.name.take(3)} ${day.dayOfMonth}")
                } else {
                    labels.add("")
                }

                val searchStart = day.minus(DatePeriod(days = 1)).atStartOfDayIn(timeZone).epochSeconds + (18 * 3600)
                val searchEnd = day.atStartOfDayIn(timeZone).epochSeconds + (14 * 3600)

                val sleepTypes = listOf(OverlayType.Sleep.value, OverlayType.DeepSleep.value)
                val sleepEntries = healthDao.getOverlayEntries(searchStart, searchEnd, sleepTypes)

                val daySleepSeconds = sleepEntries.filter { OverlayType.fromValue(it.type) == OverlayType.Sleep }
                    .sumOf { it.duration }
                val sleepHours = daySleepSeconds / 3600f
                values.add(sleepHours)
                totalHours += sleepHours
            }
            val avg = totalHours / 30f
            Triple(labels, values, avg)
        }
    }
}
