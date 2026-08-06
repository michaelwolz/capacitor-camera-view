package com.michaelwolz.capacitorcameraview.model

/** Rectangle for barcode bounds in display/CSS pixels within the webview coordinate space. */
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