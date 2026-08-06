package com.michaelwolz.capacitorcameraview.model

/**
 * Represents a camera device available on the Android device.
 *
 * @property id Unique identifier for the camera
 * @property name Human-readable name of the camera
 * @property position Position of the camera ("front" or "back")
 * @property deviceType Lens type ("wideAngle", "ultraWide", or "telephoto") derived from
 *                       camera characteristics where derivable, matching the
 *                       iOS-populated field of the same name. `null` when it can't be
 *                       determined (e.g. focal length/sensor size not reported).
 */
data class CameraDevice(
    val id: String,
    val name: String,
    val position: String,
    val deviceType: String? = null
)
