package com.michaelwolz.capacitorcameraview

import androidx.camera.core.AspectRatio
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [groupAspectRatio], the ratio every use case in the bound group is built for.
 */
class GroupAspectRatioTest {

    @Test
    fun `groupAspectRatio keeps an exact viewport match`() {
        assertEquals(AspectRatio.RATIO_4_3, groupAspectRatio(AspectRatio.RATIO_4_3, null))
        assertEquals(AspectRatio.RATIO_16_9, groupAspectRatio(AspectRatio.RATIO_16_9, "4:3"))
    }

    @Test
    fun `groupAspectRatio falls back to the capture ratio without an exact match`() {
        assertEquals(AspectRatio.RATIO_4_3, groupAspectRatio(AspectRatio.RATIO_DEFAULT, "4:3"))
        assertEquals(AspectRatio.RATIO_16_9, groupAspectRatio(AspectRatio.RATIO_DEFAULT, "16:9"))
    }

    @Test
    fun `groupAspectRatio falls back to the default capture ratio of 16 to 9`() {
        assertEquals(AspectRatio.RATIO_16_9, groupAspectRatio(AspectRatio.RATIO_DEFAULT, null))
    }

    @Test
    fun `groupAspectRatio never returns RATIO_DEFAULT`() {
        val resolved = listOf(null, "4:3", "16:9", "unsupported").map {
            groupAspectRatio(AspectRatio.RATIO_DEFAULT, it)
        }

        assert(resolved.none { it == AspectRatio.RATIO_DEFAULT })
    }
}
