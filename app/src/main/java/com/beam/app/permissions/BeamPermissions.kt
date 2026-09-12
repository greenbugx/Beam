package com.beam.app.permissions

import android.Manifest
import android.os.Build

object BeamPermissions {
    private val location =
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

    // Runtime Bluetooth permissions only exist from Android 12. On Android
    // 8-11 the BLUETOOTH/BLUETOOTH_ADMIN permissions are install-time only.
    private val bluetooth: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            emptyArray()
        }

    private val nearbyWifi: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            emptyArray()
        }

    fun required(): Array<String> = location + bluetooth + nearbyWifi

    /**
     * Returns only the permissions that [check] reports as not granted.
     * Location is skipped entirely when either fine or coarse is already
     * held, so users who granted approximate location aren't re-prompted.
     */
    fun missing(check: (String) -> Boolean): List<String> {
        val toRequest = mutableListOf<String>()

        val hasLocation =
            check(Manifest.permission.ACCESS_FINE_LOCATION) ||
                check(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (!hasLocation) {
            toRequest += location
        }

        toRequest += bluetooth.filter { !check(it) }
        toRequest += nearbyWifi.filter { !check(it) }

        return toRequest
    }

    fun isSatisfied(grants: Map<String, Boolean>): Boolean {
        val hasLocation =
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (!hasLocation) return false

        return (bluetooth + nearbyWifi).all {
            grants[it] == true
        }
    }
}
