package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coredevices.pebble.rememberLibPebble
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.health.HealthSettings

enum class UnitSystem {
    Metric,
    Imperial
}

@Composable
fun HealthSettingsScreen(
    navBarNav: NavBarNav,
    topBarParams: TopBarParams
) {
    val libPebble = rememberLibPebble()
    val healthSettings by libPebble.healthSettings.collectAsState(initial = HealthSettings())

    // Debug logging
    LaunchedEffect(healthSettings) {
        println("HealthSettingsScreen: healthSettings updated - age=${healthSettings.ageYears}, height=${healthSettings.heightMm}, weight=${healthSettings.weightDag}, gender=${healthSettings.gender}")
    }

    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(false)
        topBarParams.actions {}
        topBarParams.title("Health Settings")
        topBarParams.canGoBack(true)
        topBarParams.goBack.collect {
            navBarNav.goBack()
        }
    }

    val focusManager = LocalFocusManager.current

    // Local editable state
    var unitSystem by remember { mutableStateOf(UnitSystem.Metric) }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf(HealthGender.Female) }
    var displayHeight by remember { mutableStateOf("") }
    var displayWeight by remember { mutableStateOf("") }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var isInitialized by remember { mutableStateOf(false) }

    // Load values from healthSettings whenever they change (including on first load and after save)
    LaunchedEffect(healthSettings.heightMm, healthSettings.weightDag, healthSettings.ageYears, healthSettings.gender, healthSettings.imperialUnits) {
        println("HealthSettingsScreen: Loading from healthSettings - heightMm=${healthSettings.heightMm}, weightDag=${healthSettings.weightDag}, imperialUnits=${healthSettings.imperialUnits}")

        // Update unit system from saved settings
        val newUnitSystem = if (healthSettings.imperialUnits) UnitSystem.Imperial else UnitSystem.Metric
        unitSystem = newUnitSystem

        // Update age and gender
        age = healthSettings.ageYears.toString()
        gender = healthSettings.gender

        // Convert and display height/weight
        val heightCm = healthSettings.heightMm / 10
        val weightKg = healthSettings.weightDag / 100.0

        displayHeight = if (newUnitSystem == UnitSystem.Imperial) {
            val totalInches = heightCm / 2.54
            "${(totalInches / 12).toInt()}'${(totalInches % 12).toInt()}\""
        } else {
            "$heightCm"
        }

        displayWeight = if (newUnitSystem == UnitSystem.Imperial) {
            String.format("%.1f", weightKg * 2.20462)
        } else {
            String.format("%.1f", weightKg)
        }

        // Don't mark as unsaved when loading from database
        hasUnsavedChanges = false
        isInitialized = true
    }

    // Helper function to convert units when user manually changes unit system
    fun convertUnits(newUnitSystem: UnitSystem) {
        if (!isInitialized) return

        // Convert based on current saved values in healthSettings
        val heightCm = healthSettings.heightMm / 10
        val weightKg = healthSettings.weightDag / 100.0

        displayHeight = if (newUnitSystem == UnitSystem.Imperial) {
            val totalInches = heightCm / 2.54
            "${(totalInches / 12).toInt()}'${(totalInches % 12).toInt()}\""
        } else {
            "$heightCm"
        }

        displayWeight = if (newUnitSystem == UnitSystem.Imperial) {
            String.format("%.1f", weightKg * 2.20462)
        } else {
            String.format("%.1f", weightKg)
        }

        unitSystem = newUnitSystem
        hasUnsavedChanges = true
    }

    fun saveSettings(): Boolean {
        println("HealthSettingsScreen: saveSettings() called - age=$age, height=$displayHeight, weight=$displayWeight, gender=$gender, unitSystem=$unitSystem")

        // Validate age
        val ageInt = age.toIntOrNull()?.coerceIn(1, 150)
        if (ageInt == null) {
            println("HealthSettingsScreen: Invalid age")
            return false
        }

        val (heightMm, weightDag) = if (unitSystem == UnitSystem.Imperial) {
            // Parse feet and inches (e.g., "5'10")
            val heightParts = displayHeight.replace("\"", "").split("'")
            val feet = heightParts.getOrNull(0)?.toIntOrNull()
            val inches = heightParts.getOrNull(1)?.toIntOrNull() ?: 0

            if (feet == null || feet < 3 || feet > 8 || inches < 0 || inches > 11) {
                println("HealthSettingsScreen: Invalid height")
                return false
            }

            val totalInches = feet * 12 + inches
            val heightCm = (totalInches * 2.54).toInt()
            val heightMm = (heightCm * 10).toShort()

            // Convert pounds to decagrams
            val weightLbs = displayWeight.toDoubleOrNull()
            if (weightLbs == null || weightLbs < 50 || weightLbs > 500) {
                println("HealthSettingsScreen: Invalid weight")
                return false
            }

            val weightKg = weightLbs / 2.20462
            val weightDag = (weightKg * 100).toInt().toShort()

            Pair(heightMm, weightDag)
        } else {
            // Metric - centimeters and kilograms
            val heightCm = displayHeight.toIntOrNull()
            if (heightCm == null || heightCm < 100 || heightCm > 250) {
                println("HealthSettingsScreen: Invalid height")
                return false
            }

            val heightMm = (heightCm * 10).toShort()

            val weightKg = displayWeight.toDoubleOrNull()
            if (weightKg == null || weightKg < 20 || weightKg > 200) {
                println("HealthSettingsScreen: Invalid weight")
                return false
            }

            val weightDag = (weightKg * 100).toInt().toShort()

            Pair(heightMm, weightDag)
        }

        val newSettings = healthSettings.copy(
            heightMm = heightMm,
            weightDag = weightDag,
            ageYears = ageInt,
            gender = gender,
            imperialUnits = (unitSystem == UnitSystem.Imperial)
        )
        println("HealthSettingsScreen: Saving settings - heightMm=$heightMm, weightDag=$weightDag, ageYears=$ageInt, gender=$gender, imperialUnits=${unitSystem == UnitSystem.Imperial}")

        // Save to database (which syncs to watch)
        libPebble.updateHealthSettings(newSettings)

        // Send updated health averages to watch for calorie calculations
        libPebble.sendHealthAveragesToWatch()

        hasUnsavedChanges = false
        return true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Personal Information",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        // Unit System Toggle
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Unit System",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = unitSystem == UnitSystem.Metric,
                        onClick = {
                            if (unitSystem != UnitSystem.Metric) {
                                convertUnits(UnitSystem.Metric)
                            }
                        },
                        label = { Text("Metric (cm, kg)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = unitSystem == UnitSystem.Imperial,
                        onClick = {
                            if (unitSystem != UnitSystem.Imperial) {
                                convertUnits(UnitSystem.Imperial)
                            }
                        },
                        label = { Text("Imperial (ft, lbs)") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Age Input
        OutlinedTextField(
            value = age,
            onValueChange = { newValue ->
                // Only allow digits and limit to reasonable range
                if (newValue.isEmpty() || (newValue.toIntOrNull() != null && newValue.toIntOrNull()!! <= 150)) {
                    age = newValue
                    hasUnsavedChanges = true
                }
            },
            label = { Text("Age") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Filled.Cake, contentDescription = null) },
            suffix = { Text("years") }
        )

        // Height Input
        OutlinedTextField(
            value = displayHeight,
            onValueChange = {
                displayHeight = it
                hasUnsavedChanges = true
            },
            label = { Text("Height") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Filled.Straighten, contentDescription = null) },
            suffix = {
                Text(
                    if (unitSystem == UnitSystem.Imperial) "ft'in\"" else "cm"
                )
            },
            supportingText = {
                Text(
                    if (unitSystem == UnitSystem.Imperial) {
                        "Format: 5'10\""
                    } else {
                        "Example: 170"
                    }
                )
            }
        )

        // Weight Input
        OutlinedTextField(
            value = displayWeight,
            onValueChange = {
                displayWeight = it
                hasUnsavedChanges = true
            },
            label = { Text("Weight") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Filled.FitnessCenter, contentDescription = null) },
            suffix = {
                Text(
                    if (unitSystem == UnitSystem.Imperial) "lbs" else "kg"
                )
            }
        )

        // Gender Selection
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Gender",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = gender == HealthGender.Male,
                        onClick = {
                            gender = HealthGender.Male
                            hasUnsavedChanges = true
                        },
                        label = { Text("Male") },
                        leadingIcon = if (gender == HealthGender.Male) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = gender == HealthGender.Female,
                        onClick = {
                            gender = HealthGender.Female
                            hasUnsavedChanges = true
                        },
                        label = { Text("Female") },
                        leadingIcon = if (gender == HealthGender.Female) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Save Button
        Button(
            onClick = { saveSettings() },
            enabled = hasUnsavedChanges,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (hasUnsavedChanges) "Save Changes" else "No Changes")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This information helps calculate calories burned and other health metrics.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
