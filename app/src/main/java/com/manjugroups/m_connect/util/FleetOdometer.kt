package com.manjugroups.m_connect.util

const val MAX_FLEET_TRIP_DISTANCE_KM = 5_000.0

fun fleetTripDistanceKm(startKm: Double, endKm: Double): Double? {
    if (!startKm.isFinite() || !endKm.isFinite() || startKm < 0 || endKm < startKm) {
        return null
    }
    return (endKm - startKm).takeIf { it <= MAX_FLEET_TRIP_DISTANCE_KM }
}
