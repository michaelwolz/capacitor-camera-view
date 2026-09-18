package com.michaelwolz.capacitorcameraview.model

/**
 * Represents a camera device available on the Android device.
 *
 * @property id Unique identifier for the camera
 * @property name Human-readable name of the camera
 * @property position Position of the camera ("front" or "back")
 * @property deviceType Lens type ("wideAngle", "ultraWide", or "telephoto") derived from
 *                       CameraX's intrinsic zoom ratio where derivable, matching the
 *                       iOS-populated field of the same name. `null` for a physical
 *                       sub-camera of a logical multi-camera, whose lens type CameraX
 *                       can't resolve.
 */
data class CameraDevice(
    val id: String,
    val name: String,
    val position: String,
    val deviceType: String? = null
)
