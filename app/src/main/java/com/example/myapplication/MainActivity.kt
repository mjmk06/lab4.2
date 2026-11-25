@file:Suppress("DEPRECATION")

package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // Map & Location fields
    private lateinit var mapView: MapView
    private var maplibreMap: MapLibreMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var carMarker: Marker? = null
    private lateinit var tvStatusCard: TextView
    private var lastUserLocation: Location? = null

    // Constants for GeoJsonSource & Layer
    companion object {
        private const val CAR_SOURCE_ID = "car-source"
        private const val CAR_LAYER_ID = "car-layer"
        private const val CAR_ICON_ID = "car-icon"
        // Default Location: Cebu City, Philippines
        private val CEBU_LOCATION = LatLng(10.3157, 123.8854)
    }

    // Construct MapTiler style URL using API Key from BuildConfig / local.properties
    private val mapTilerStyleUrl =
        "https://api.maptiler.com/maps/streets-v2/style.json?key=${BuildConfig.MAPTILER_API_KEY}"

    // Permission launcher using Activity Result API
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            enableLocationAndCenter()
        } else {
            // Check if permission was permanently denied
            if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                showSettingsDialog()
            } else {
                Toast.makeText(this, R.string.location_permission_rationale, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize MapLibre before inflating layout
        MapLibre.getInstance(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        tvStatusCard = findViewById(R.id.tv_status_card)

        // Apply Window Insets for status bar and bottom panel margin
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val statusParams = tvStatusCard.layoutParams as ViewGroup.MarginLayoutParams
            statusParams.topMargin = systemBars.top + 16
            tvStatusCard.layoutParams = statusParams

            val bottomPanel = findViewById<ViewGroup>(R.id.bottom_panel)
            bottomPanel.setPadding(
                bottomPanel.paddingLeft,
                bottomPanel.paddingTop,
                bottomPanel.paddingRight,
                systemBars.bottom + 16
            )
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Setup MapView
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        // Fallback listener if custom style URL fails to load
        mapView.addOnDidFailLoadingMapListener {
            val fallbackStyleUrl = "https://demotiles.maplibre.org/style.json"
            maplibreMap?.setStyle(fallbackStyleUrl) { style ->
                setupCarMarkerStyle(style)
                enableLocationComponent(style)
                val savedLocation = restoreSavedLocation()
                if (savedLocation != null) {
                    updateCarMarker(savedLocation)
                    maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(savedLocation, 16.0))
                } else {
                    maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(CEBU_LOCATION, 15.0))
                }
                checkLocationPermissionAndEnableMap()
            }
        }

        mapView.getMapAsync { map ->
            this.maplibreMap = map

            // Enable MapLibre gesture controls
            map.uiSettings.run {
                isScrollGesturesEnabled = true
                isZoomGesturesEnabled = true
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = true
                isDoubleTapGesturesEnabled = true
                isQuickZoomGesturesEnabled = true
            }

            // Load MapTiler Style URL
            map.setStyle(mapTilerStyleUrl) { style ->
                setupCarMarkerStyle(style)
                enableLocationComponent(style)

                // Restore saved location or default to Cebu, Philippines
                val savedLocation = restoreSavedLocation()
                if (savedLocation != null) {
                    updateCarMarker(savedLocation)
                    maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(savedLocation, 16.0))
                } else {
                    maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(CEBU_LOCATION, 15.0))
                }
                checkLocationPermissionAndEnableMap()
            }
        }

        // "I'm parked here" Button click handler
        val btnParkedHere = findViewById<MaterialButton>(R.id.btn_parked_here)
        btnParkedHere.setOnClickListener {
            onParkedHereClicked()
        }

        // Floating Action Buttons
        val fabMyLocation = findViewById<FloatingActionButton>(R.id.fab_my_location)
        fabMyLocation.setOnClickListener {
            if (!hasLocationPermission()) {
                checkLocationPermissionAndEnableMap()
                return@setOnClickListener
            }
            if (!isLocationServicesEnabled()) {
                Toast.makeText(this, R.string.location_services_off, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            fetchCurrentLocation { loc ->
                lastUserLocation = loc
                val userLatLng = LatLng(loc.latitude, loc.longitude)
                maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 17.0))
                updateStatusCard()
                Toast.makeText(this, "Centered on your location", Toast.LENGTH_SHORT).show()
            }
        }

        val fabCarLocation = findViewById<FloatingActionButton>(R.id.fab_car_location)
        fabCarLocation.setOnClickListener {
            val carLatLng = carMarker?.position ?: restoreSavedLocation()
            if (carLatLng != null) {
                maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(carLatLng, 17.0))
                carMarker?.let { marker -> maplibreMap?.selectMarker(marker) }
            } else {
                Toast.makeText(this, getString(R.string.no_car_saved), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Set up MapLibre GeoJsonSource & SymbolLayer for Car Marker
    private fun setupCarMarkerStyle(style: Style) {
        val carBitmap = getBitmapFromVectorDrawable(R.drawable.ic_car_pin_marker)
        if (carBitmap != null) {
            style.addImage(CAR_ICON_ID, carBitmap)
        }

        val savedLocation = restoreSavedLocation()
        val initialFeatureCollection = if (savedLocation != null) {
            val point = Point.fromLngLat(savedLocation.longitude, savedLocation.latitude)
            FeatureCollection.fromFeature(Feature.fromGeometry(point))
        } else {
            FeatureCollection.fromFeatures(arrayOf())
        }

        val geoJsonSource = GeoJsonSource(CAR_SOURCE_ID, initialFeatureCollection)
        style.addSource(geoJsonSource)

        val symbolLayer = SymbolLayer(CAR_LAYER_ID, CAR_SOURCE_ID).apply {
            setProperties(
                PropertyFactory.iconImage(CAR_ICON_ID),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        }
        style.addLayer(symbolLayer)
    }

    // Convert vector drawable to Bitmap for MapLibre style image
    private fun getBitmapFromVectorDrawable(drawableId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(this, drawableId) ?: return null
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.draw(canvas)
        return bitmap
    }

    // Check if Location Services (GPS / Network) are enabled on device
    private fun isLocationServicesEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // Handle "I'm parked here" button click with full edge-case checks
    private fun onParkedHereClicked() {
        if (!hasLocationPermission()) {
            checkLocationPermissionAndEnableMap()
            return
        }

        if (!isLocationServicesEnabled()) {
            Toast.makeText(this, R.string.location_services_off, Toast.LENGTH_LONG).show()
            return
        }

        fetchCurrentLocation { location ->
            lastUserLocation = location
            val currentLatLng = LatLng(location.latitude, location.longitude)
            val now = System.currentTimeMillis()

            updateCarMarker(currentLatLng)
            saveLocation(currentLatLng, now)

            maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 17.0))
            updateStatusCard()
            Toast.makeText(this, getString(R.string.parked_location_saved), Toast.LENGTH_SHORT).show()
        }
    }

    // Check permission state
    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private fun checkLocationPermissionAndEnableMap() {
        if (hasLocationPermission()) {
            enableLocationAndCenter()
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.permission_required_title)
                    .setMessage(R.string.location_permission_rationale)
                    .setPositiveButton(R.string.ok) { _, _ -> launchPermissionRequest() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                launchPermissionRequest()
            }
        }
    }

    private fun launchPermissionRequest() {
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required_title)
            .setMessage(R.string.permission_settings_rationale)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Enable MapLibre LocationComponent
    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        if (hasLocationPermission()) {
            try {
                val locationComponent = maplibreMap?.locationComponent ?: return
                val options = LocationComponentActivationOptions
                    .builder(this, style)
                    .useDefaultLocationEngine(true)
                    .build()
                locationComponent.activateLocationComponent(options)
                locationComponent.isLocationComponentEnabled = true
                locationComponent.cameraMode = CameraMode.NONE
                locationComponent.renderMode = RenderMode.COMPASS
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun enableLocationAndCenter() {
        maplibreMap?.style?.let { enableLocationComponent(it) }
        fetchCurrentLocation { location ->
            lastUserLocation = location
            val currentLatLng = LatLng(location.latitude, location.longitude)
            val savedCarLoc = restoreSavedLocation()
            if (savedCarLoc == null) {
                maplibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 17.0))
            }
            updateStatusCard()
        }
    }

    private fun updateStatusCard() {
        val carLatLng = carMarker?.position ?: restoreSavedLocation()
        val savedTimestamp = getSavedTimestamp()

        if (carLatLng == null) {
            tvStatusCard.text = getString(R.string.no_car_saved)
            return
        }

        val timeStr = if (savedTimestamp > 0) {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            sdf.format(Date(savedTimestamp))
        } else ""

        val userLoc = lastUserLocation
        if (userLoc != null) {
            val carLoc = Location("").apply {
                latitude = carLatLng.latitude
                longitude = carLatLng.longitude
            }
            val distanceMeters = userLoc.distanceTo(carLoc)
            val distStr = if (distanceMeters < 1000) {
                "${distanceMeters.roundToInt()} m away"
            } else {
                String.format(Locale.getDefault(), "%.1f km away", distanceMeters / 1000.0)
            }

            tvStatusCard.text = if (timeStr.isNotEmpty()) {
                "Car parked $distStr · Saved $timeStr"
            } else {
                "Car parked $distStr"
            }
        } else {
            tvStatusCard.text = if (timeStr.isNotEmpty()) {
                "Car parked · Saved $timeStr"
            } else {
                "Car parked"
            }
        }
    }

    // Fetch user current location with fallback and edge-case handling
    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation(onLocationReceived: (Location) -> Unit) {
        if (!hasLocationPermission()) return

        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { freshLocation ->
                if (freshLocation != null && isValidLocation(freshLocation)) {
                    onLocationReceived(freshLocation)
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null && isValidLocation(lastLoc)) {
                            onLocationReceived(lastLoc)
                        } else {
                            requestFreshLocationUpdate(onLocationReceived)
                        }
                    }.addOnFailureListener {
                        requestFreshLocationUpdate(onLocationReceived)
                    }
                }
            }.addOnFailureListener {
                requestFreshLocationUpdate(onLocationReceived)
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun isValidLocation(location: Location): Boolean {
        return location.latitude != 0.0 || location.longitude != 0.0
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocationUpdate(onLocationReceived: (Location) -> Unit) {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000
        ).setMaxUpdates(1).build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val loc = locationResult.lastLocation
                if (loc != null) {
                    onLocationReceived(loc)
                }
                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    // Update GeoJsonSource and Annotation marker for car pin
    private fun updateCarMarker(latLng: LatLng) {
        // Update GeoJsonSource feature
        val style = maplibreMap?.style
        if (style != null && style.isFullyLoaded) {
            val point = Point.fromLngLat(latLng.longitude, latLng.latitude)
            val featureCollection = FeatureCollection.fromFeature(Feature.fromGeometry(point))
            val source = style.getSourceAs<GeoJsonSource>(CAR_SOURCE_ID)
            source?.setGeoJson(featureCollection)
        }

        // Update Annotation Marker for callout title
        val iconFactory = IconFactory.getInstance(this)
        val bitmap = getBitmapFromVectorDrawable(R.drawable.ic_car_pin_marker)
        val icon = bitmap?.let { iconFactory.fromBitmap(it) }

        if (carMarker == null) {
            val markerOptions = MarkerOptions()
                .position(latLng)
                .title(getString(R.string.car_parked_title))
            if (icon != null) {
                markerOptions.icon(icon)
            }
            carMarker = maplibreMap?.addMarker(markerOptions)
        } else {
            carMarker?.position = latLng
        }
        carMarker?.let { marker ->
            maplibreMap?.selectMarker(marker)
        }
    }

    // Requirement 8: Save parking location to SharedPreferences
    private fun saveLocation(latLng: LatLng, timestamp: Long = System.currentTimeMillis()) =
        getPreferences(MODE_PRIVATE)?.edit()?.apply {
            putString("latitude", latLng.latitude.toString())
            putString("longitude", latLng.longitude.toString())
            putLong("saved_timestamp", timestamp)
            apply()
        }

    // Requirement 9: Restore saved parking location from SharedPreferences
    private fun restoreSavedLocation(): LatLng? {
        val sharedPreferences = getPreferences(MODE_PRIVATE) ?: return null
        val latitude = sharedPreferences.getString("latitude", null)?.toDoubleOrNull() ?: return null
        val longitude = sharedPreferences.getString("longitude", null)?.toDoubleOrNull() ?: return null
        return LatLng(latitude, longitude)
    }

    private fun getSavedTimestamp(): Long {
        val sharedPreferences = getPreferences(MODE_PRIVATE) ?: return 0L
        return sharedPreferences.getLong("saved_timestamp", 0L)
    }

    // MapView Required Lifecycle Management
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
