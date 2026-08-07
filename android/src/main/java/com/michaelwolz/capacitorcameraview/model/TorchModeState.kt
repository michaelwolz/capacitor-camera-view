package com.michaelwolz.capacitorcameraview.model

/**
 * Represents the current torch (flashlight) mode and intensity.
 *
 * @property enabled Whether the torch is currently enabled.
 * @property level The current torch intensity level, normalized to 0.0-1.0. Only meaningful
 * as a continuous value on API 33+ devices with multi-level torch hardware - below API 33, or
 * on single-level hardware, the torch is binary and this is always 1.0 when enabled and 0.0
 * when off.
 */
data class TorchModeState(
    val enabled: Boolean,
    val level: Float
)
