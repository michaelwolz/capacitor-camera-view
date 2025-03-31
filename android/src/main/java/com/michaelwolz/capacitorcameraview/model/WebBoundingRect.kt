package com.michaelwolz.capacitorcameraview.model

/** Normalized rectangle for barcode bounds. */
data class WebBoundingRect(
    /** Top left x coordinate of the rectangle. */
    val x: Float,

    /** Top left y coordinate of the rectangle. */
    val y: Float,

    /** Width of the rectangle. */
    val width: Float,

    /** Height of the rectangle. */
    val height: Float
)