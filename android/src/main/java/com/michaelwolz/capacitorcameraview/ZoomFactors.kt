package com.michaelwolz.capacitorcameraview

/**
 * Represents supported zoom factors for the camera.
 *
 * @property min Minimum zoom level (typically 1.0)
 * @property max Maximum zoom level supported by the device
 * @property current Current zoom level in use
 */
data class ZoomFactors(
    val min: Float,
    val max: Float,
    val current: Float
)
