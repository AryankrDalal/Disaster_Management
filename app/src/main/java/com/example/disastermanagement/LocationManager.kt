package com.example.disastermanagement

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

data class GpsLocation(
    val latitude: Float,
    val longitude: Float
)

class LocationManager(
    private val context: Context
) {

    private val fusedLocationClient:
            FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(
            context
        )

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(
        onLocationReceived: (GpsLocation) -> Unit,
        onError: (String) -> Unit
    ) {

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            onError("Location permission not granted")
            return
        }

        fusedLocationClient
            .getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            )
            .addOnSuccessListener { location: Location? ->

                if (location != null) {

                    val gpsLocation =
                        GpsLocation(
                            latitude =
                                location.latitude.toFloat(),
                            longitude =
                                location.longitude.toFloat()
                        )

                    onLocationReceived(
                        gpsLocation
                    )

                } else {

                    // Try the last known location
                    getLastKnownLocation(
                        onLocationReceived,
                        onError
                    )
                }
            }
            .addOnFailureListener {

                onError(
                    "Unable to get GPS location: " +
                            it.message
                )
            }
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(
        onLocationReceived:
            (GpsLocation) -> Unit,
        onError:
            (String) -> Unit
    ) {

        fusedLocationClient
            .lastLocation
            .addOnSuccessListener { location ->

                if (location != null) {

                    onLocationReceived(
                        GpsLocation(
                            latitude =
                                location.latitude.toFloat(),
                            longitude =
                                location.longitude.toFloat()
                        )
                    )

                } else {

                    onError(
                        "GPS location unavailable. " +
                                "Make sure Location is ON."
                    )
                }
            }
            .addOnFailureListener {

                onError(
                    "Unable to get last location"
                )
            }
    }
}