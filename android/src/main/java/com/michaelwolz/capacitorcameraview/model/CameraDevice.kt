package com.michaelwolz.capacitorcameraview.model

/**
 * Represents a camera device available on the Android device.
 *
 * @property id Unique identifier for the camera
 * @property name Human-readable name of the camera
 * @property position Position of the camera ("front" or "back")
 */
data class CameraDevice(
    val id: String,
    val name: String,
    val position: String
)
