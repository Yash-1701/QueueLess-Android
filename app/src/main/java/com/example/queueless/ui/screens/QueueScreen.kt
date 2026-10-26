package com.example.queueless.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.queueless.ui.viewmodel.QueueViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale

// 1. Target Venue Coordinates (QueueLess Counter) & Max Proximity Radius
private const val VENUE_LATITUDE = 19.141806
private const val VENUE_LONGITUDE = 72.833361
private const val MAX_RADIUS_METERS = 500.0f

private fun Float.format(digits: Int): String = String.format(Locale.US, "%.${digits}f", this)

enum class LocationStatus {
    SEARCHING_GPS,
    INSIDE_GEOFENCE,
    OUTSIDE_GEOFENCE,
    UNAVAILABLE,
    PERMISSION_DENIED
}

@SuppressLint("MissingPermission")
@Composable
fun QueueScreen(
    viewModel: QueueViewModel,
    onTokenGenerated: () -> Unit
) {
    val context = LocalContext.current
    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    var phone by remember { mutableStateOf("") }
    var partySize by remember { mutableStateOf("2") }
    var isSubmitting by remember { mutableStateOf(false) }

    val restaurant by viewModel.restaurant.collectAsState()
    val activeTokenId by viewModel.activeTokenId.collectAsState()

    // Location Permission State
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Hardware GPS Telemetry State
    var locationStatus by remember { mutableStateOf(LocationStatus.SEARCHING_GPS) }
    var realGpsDistanceMeters by remember { mutableStateOf<Float?>(null) }
    var isAcquiringLocation by remember { mutableStateOf(false) }

    // Examiner Viva Demonstration Simulation State
    var demoSimulationMode by remember { mutableStateOf(false) }
    var simulatedDistanceMeters by remember { mutableStateOf(20500f) }

    // 2. 3-Tier Multi-Source Location Strategy
    val triggerHardwareLocationCheck: () -> Unit = {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        hasLocationPermission = hasFine || hasCoarse

        if (!hasLocationPermission) {
            locationStatus = LocationStatus.PERMISSION_DENIED
            isAcquiringLocation = false
        } else {
            isAcquiringLocation = true
            locationStatus = LocationStatus.SEARCHING_GPS

            var hasReceivedFix = false

            fun applyLocationFix(loc: Location) {
                val results = FloatArray(1)
                Location.distanceBetween(
                    loc.latitude,
                    loc.longitude,
                    VENUE_LATITUDE,
                    VENUE_LONGITUDE,
                    results
                )
                val distanceInMeters = results[0]
                realGpsDistanceMeters = distanceInMeters
                hasReceivedFix = true
                isAcquiringLocation = false
                locationStatus = if (distanceInMeters <= MAX_RADIUS_METERS) {
                    LocationStatus.INSIDE_GEOFENCE
                } else {
                    LocationStatus.OUTSIDE_GEOFENCE
                }
            }

            // Tier 1: Immediately inspect lastLocation from Fused Provider & Native LocationManager
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null && !hasReceivedFix) {
                    applyLocationFix(lastLoc)
                }
            }

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val networkLast = try {
                locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: SecurityException) {
                null
            }
            val gpsLast = try {
                locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (e: SecurityException) {
                null
            }

            val bestLast = when {
                networkLast != null && gpsLast != null -> if (networkLast.time > gpsLast.time) networkLast else gpsLast
                networkLast != null -> networkLast
                gpsLast != null -> gpsLast
                else -> null
            }

            if (bestLast != null && !hasReceivedFix) {
                applyLocationFix(bestLast)
            }

            // Tier 2: Simultaneously query PRIORITY_BALANCED_POWER_ACCURACY (Wi-Fi + Cell towers indoor fix)
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                null
            ).addOnSuccessListener { balancedLoc ->
                if (balancedLoc != null) {
                    applyLocationFix(balancedLoc)
                } else if (!hasReceivedFix) {
                    // Fallback to high accuracy if balanced returned null
                    fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        null
                    ).addOnSuccessListener { highLoc ->
                        if (highLoc != null) {
                            applyLocationFix(highLoc)
                        } else if (!hasReceivedFix) {
                            // Tier 3: Both returned null (indoor without lock or GPS disabled)
                            isAcquiringLocation = false
                            locationStatus = LocationStatus.UNAVAILABLE
                        }
                    }.addOnFailureListener {
                        if (!hasReceivedFix) {
                            isAcquiringLocation = false
                            locationStatus = LocationStatus.UNAVAILABLE
                        }
                    }
                }
            }.addOnFailureListener {
                if (!hasReceivedFix) {
                    // Try High Accuracy before concluding unavailable
                    fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        null
                    ).addOnSuccessListener { highLoc ->
                        if (highLoc != null) {
                            applyLocationFix(highLoc)
                        } else if (!hasReceivedFix) {
                            isAcquiringLocation = false
                            locationStatus = LocationStatus.UNAVAILABLE
                        }
                    }.addOnFailureListener {
                        if (!hasReceivedFix) {
                            isAcquiringLocation = false
                            locationStatus = LocationStatus.UNAVAILABLE
                        }
                    }
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted
        if (granted) {
            triggerHardwareLocationCheck()
        } else {
            locationStatus = LocationStatus.PERMISSION_DENIED
            isAcquiringLocation = false
        }
    }

    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        hasLocationPermission = hasFine || hasCoarse

        if (hasLocationPermission) {
            triggerHardwareLocationCheck()
        } else {
            locationStatus = LocationStatus.PERMISSION_DENIED
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(activeTokenId) {
        if (activeTokenId != null) {
            isSubmitting = false
            onTokenGenerated()
        }
    }

    // Effective Distance Calculation
    val currentDistanceMeters: Float? = if (demoSimulationMode) {
        simulatedDistanceMeters
    } else {
        realGpsDistanceMeters
    }

    // 2. Exact Same Distance & Geofence Enforcement
    val isWithinRadius: Boolean = if (demoSimulationMode) {
        simulatedDistanceMeters <= MAX_RADIUS_METERS
    } else {
        locationStatus == LocationStatus.INSIDE_GEOFENCE
    }

    val isQueuePaused = restaurant?.isQueuePaused == true
    val isFormEnabled = isWithinRadius && !isQueuePaused && !isSubmitting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // App / Venue Brand Header
        Icon(
            imageVector = Icons.Default.ConfirmationNumber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "QueueLess",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Next-Gen Concurrency & EMA Queue System",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Venue Overview Card for rest_sample_01
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Store,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = restaurant?.name ?: "QueueLess Express Counter",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Text(
                        text = "ID: ${viewModel.venueId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                restaurant?.let { rest ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Now Serving: #${rest.currentServingToken}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Pace: ${String.format(Locale.US, "%.1f", rest.currentEmaWaitTime)}m/token",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Banner 1: Pause Queue Notification Banner
        if (isQueuePaused) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "Queue Paused",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "The vendor has temporarily paused token issuance.",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Location & Geofence Status Banners
        if (!isQueuePaused) {
            if (!demoSimulationMode && !hasLocationPermission) {
                // Permission Denied Banner
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Location Permission Required",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "Please allow location access to verify restaurant proximity (<500m).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        TextButton(
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        ) {
                            Text("Grant", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (!demoSimulationMode && isAcquiringLocation && realGpsDistanceMeters == null) {
                // Searching GPS Banner
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Acquiring Fast Indoor Location...",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Connecting with Wi-Fi & cell networks for an instant fix.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (!demoSimulationMode && locationStatus == LocationStatus.UNAVAILABLE && realGpsDistanceMeters == null) {
                // Tier 3: Clean Fallback State with Retry GPS button
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOff,
                            contentDescription = "Location Unavailable",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Location Unavailable",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Check that device location is enabled, or use the Examiner Demo Controls below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = triggerHardwareLocationCheck,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry GPS", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            // Geofence Warning Card (Amber/Red) when Outside Radius (> 500m)
            if (!isWithinRadius && currentDistanceMeters != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚠️ Outside Venue Radius: You are ${(currentDistanceMeters / 1000f).format(1)} km away. Tokens can only be issued within 500m of the venue.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        if (!demoSimulationMode) {
                            IconButton(onClick = triggerHardwareLocationCheck) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Success Card (Green) when Within Radius (<= 500m)
            if (isWithinRadius && currentDistanceMeters != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF81C784))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Verified",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "✓ Location Verified: You are ${currentDistanceMeters.toInt()}m from the venue.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFF1B5E20),
                            modifier = Modifier.weight(1f)
                        )
                        if (!demoSimulationMode) {
                            IconButton(
                                onClick = triggerHardwareLocationCheck,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Customer Input Form
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Join Queue",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Atomic remote allocation ensures collision-free token issuance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Phone Number field: strictly enabled = true only when isWithinRadius
                OutlinedTextField(
                    value = phone,
                    onValueChange = { input ->
                        phone = input.filter { it.isDigit() }.take(10)
                    },
                    label = { Text("Customer Mobile Number") },
                    placeholder = { Text("10-digit mobile number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = isFormEnabled,
                    isError = isFormEnabled && phone.isNotEmpty() && phone.length < 10,
                    supportingText = {
                        when {
                            isQueuePaused -> {
                                Text(
                                    "Locked: Queue is paused by the vendor",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            !isWithinRadius -> {
                                Text(
                                    "Locked: Must be within 500m to enter details",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            phone.isEmpty() -> {
                                Text("Enter 10-digit mobile number")
                            }
                            phone.length < 10 -> {
                                Text(
                                    "Enter exactly 10 digits (${phone.length}/10)",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            else -> {
                                Text(
                                    "Phone number verified (10/10)",
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Party Size counter / field: strictly enabled = false when outside radius
                OutlinedTextField(
                    value = partySize,
                    onValueChange = { partySize = it },
                    label = { Text("Party Size (Guests)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = isFormEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // "Get Queue Token" button: enabled = (isWithinRadius && phone.length == 10)
                Button(
                    onClick = {
                        val size = partySize.toIntOrNull() ?: 1
                        if (phone.length == 10 && !isQueuePaused && isWithinRadius && !isSubmitting) {
                            isSubmitting = true
                            viewModel.requestToken(
                                customerPhone = phone.trim(),
                                partySize = size
                            )
                        }
                    },
                    enabled = isWithinRadius && phone.length == 10 && !isQueuePaused && !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        val buttonText = when {
                            isQueuePaused -> "Queue Paused"
                            !isWithinRadius -> "Outside Venue Radius (>500m)"
                            phone.length != 10 -> "Enter 10-digit Mobile"
                            else -> "Get Queue Token"
                        }
                        Text(
                            text = buttonText,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }

        // 3. Embedded Examiner Viva Simulation Panel
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ),
            border = BorderStroke(
                width = 1.5.dp,
                color = if (demoSimulationMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Card Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Examiner Viva Demo Controls",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (demoSimulationMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = if (demoSimulationMode) "SIMULATION ACTIVE" else "HARDWARE GPS",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (demoSimulationMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Telemetry Display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Effective Distance",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val distDisplay = when {
                            currentDistanceMeters != null -> {
                                if (currentDistanceMeters >= 1000f) {
                                    "${(currentDistanceMeters / 1000f).format(1)} km (${currentDistanceMeters.toInt()}m)"
                                } else {
                                    "${currentDistanceMeters.toInt()} m"
                                }
                            }
                            locationStatus == LocationStatus.SEARCHING_GPS -> "Acquiring..."
                            locationStatus == LocationStatus.UNAVAILABLE -> "Unavailable"
                            else -> "No Permission"
                        }
                        Text(
                            text = distDisplay,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                            color = if (isWithinRadius) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isWithinRadius) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                        border = BorderStroke(
                            1.dp,
                            if (isWithinRadius) Color(0xFF81C784) else Color(0xFFE57373)
                        )
                    ) {
                        Text(
                            text = if (isWithinRadius) "INSIDE RADIUS (<=500m)" else "OUTSIDE RADIUS (>500m)",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isWithinRadius) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                        )
                    }
                }

                Text(
                    text = "Venue: ($VENUE_LATITUDE, $VENUE_LONGITUDE) • Max Proximity: ${MAX_RADIUS_METERS.toInt()}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

                // Button 1: "Simulate Far Away (>20 km)"
                Button(
                    onClick = {
                        demoSimulationMode = true
                        simulatedDistanceMeters = 20500f
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.DirectionsRun, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Simulate Far Away (>20 km)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Button 2: "Simulate Inside Venue (42 m)"
                Button(
                    onClick = {
                        demoSimulationMode = true
                        simulatedDistanceMeters = 42f
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Simulate Inside Venue (42 m)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Button 3: "Use Hardware GPS"
                OutlinedButton(
                    onClick = {
                        demoSimulationMode = false
                        if (!hasLocationPermission) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        } else {
                            triggerHardwareLocationCheck()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (!demoSimulationMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (!demoSimulationMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                ) {
                    if (isAcquiringLocation && !demoSimulationMode) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Acquiring Live Location...",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    } else {
                        Icon(imageVector = Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (!demoSimulationMode && hasLocationPermission) "✓ Using Live Hardware GPS (Tap to Refresh)" else "Use Hardware GPS",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}