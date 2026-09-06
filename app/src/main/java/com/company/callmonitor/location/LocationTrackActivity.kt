package com.company.callmonitor.location

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.company.callmonitor.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/**
 * Shows the driver's own recorded movement as a live route on a Google Map.
 * It plots ONLY the raw GPS points the driver actually passed through — it
 * never infers or snaps to a guessed road path, so the line is always the real
 * route, not a fabricated one. Dense sampling (see LocationTrackingService) keeps
 * the raw trace hugging the road closely.
 *
 * Reads straight from the local Room DB, so the trail is available without a
 * login and survives being offline; only the map tiles need the network.
 * Refreshes every few seconds so the trail grows as duty continues.
 */
class LocationTrackActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var tvStats: TextView

    /** Null until the Maps SDK finishes initialising. */
    private var googleMap: GoogleMap? = null

    private var polyline: Polyline? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var hasFramedOnce = false

    /** Latest render input, replayed once the map becomes ready. */
    private var pendingTrack: List<LatLng> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadTrack()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_track)

        tvStats = findViewById(R.id.tvTrackStats)
        mapView = findViewById(R.id.mapTrack)

        // MapView (rather than SupportMapFragment) so this plain AppCompatActivity
        // stays in control of the map lifecycle; every callback must be forwarded.
        mapView.onCreate(savedInstanceState?.getBundle(STATE_MAP_VIEW))
        mapView.getMapAsync { map ->
            googleMap = map
            map.mapType = GoogleMap.MAP_TYPE_NORMAL
            map.uiSettings.apply {
                isZoomControlsEnabled = true
                isCompassEnabled = true
                isMapToolbarEnabled = false
            }
            map.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.fromLatLngZoom(DEFAULT_CENTER, DEFAULT_ZOOM)
            ))
            render(pendingTrack, pendingTrack.size)
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        mapView.onPause()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val mapState = Bundle()
        mapView.onSaveInstanceState(mapState)
        outState.putBundle(STATE_MAP_VIEW, mapState)
    }

    private fun loadTrack() {
        Thread {
            val db = FleetLocationDatabase.getInstance(this)
            val dao = db.locationPointDao()

            // Prefer the active duty session; otherwise the most recent one.
            val sessionId = getSharedPreferences("location_prefs", Context.MODE_PRIVATE)
                .getString("activeSessionId", null)
                ?: dao.latestSessionId()

            // Raw recorded points only, in travel order — the actual path driven.
            val points = if (sessionId != null) dao.getPointsForSession(sessionId) else emptyList()
            val path = points.map { LatLng(it.latitude, it.longitude) }

            runOnUiThread { render(path, points.size) }
        }.start()
    }

    private fun render(path: List<LatLng>, count: Int) {
        pendingTrack = path

        val tracking = LocationTrackingService.isRunning
        tvStats.text = when {
            path.isEmpty() -> "No route recorded yet. Tracking starts automatically or from the website."
            else -> "${if (tracking) "● LIVE" else "Last route"} — $count points"
        }

        val map = googleMap ?: return

        // Clear previous overlays.
        polyline?.remove()
        startMarker?.remove()
        endMarker?.remove()
        polyline = null
        startMarker = null
        endMarker = null

        if (path.isEmpty()) return

        val color = if (tracking) TRACK_COLOR_LIVE else TRACK_COLOR_IDLE

        polyline = map.addPolyline(
            PolylineOptions()
                .addAll(path)
                .color(color)
                .width(12f)
                .jointType(JointType.ROUND)
                .geodesic(false)
        )

        startMarker = map.addMarker(
            MarkerOptions()
                .position(path.first())
                .title("Start")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
        endMarker = map.addMarker(
            MarkerOptions()
                .position(path.last())
                .title(if (tracking) "Current position" else "End")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )

        // Keep the newest position centred; frame the whole route on first load.
        if (!hasFramedOnce && path.size > 1) {
            val bounds = LatLngBounds.builder().apply { path.forEach { include(it) } }.build()
            // newLatLngBounds needs a laid-out map; fall back to a plain zoom if
            // this render lands before the view has been measured.
            try {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, BOUNDS_PADDING_PX))
            } catch (_: IllegalStateException) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(path.last(), DEFAULT_ZOOM))
            }
            hasFramedOnce = true
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLng(path.last()))
        }
    }

    companion object {
        private const val STATE_MAP_VIEW = "mapViewState"
        private const val REFRESH_MS = 5_000L
        private const val DEFAULT_ZOOM = 16f
        private const val BOUNDS_PADDING_PX = 96

        /** Geographic centre of India — the view before any point is loaded. */
        private val DEFAULT_CENTER = LatLng(20.5937, 78.9629)

        private val TRACK_COLOR_LIVE = Color.parseColor("#10b981")
        private val TRACK_COLOR_IDLE = Color.parseColor("#6366f1")
    }
}
