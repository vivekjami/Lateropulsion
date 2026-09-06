package com.lateropulsion.engine.vision

import com.lateropulsion.engine.vision.CameraCapabilities.Companion.Dim
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreviewSizeTest {
    private val redmiLike = listOf(Dim(4000, 3000), Dim(2400, 1080), Dim(1920, 1080), Dim(1600, 720), Dim(1280, 720), Dim(1440, 1080), Dim(640, 480))

    @Test
    fun `REQ-VIS-017 the preview matches the landscape screen when the camera offers it, else the sharpest 16 to 9`() {
        // 20:9 screen and the camera has a 20:9 size at screen resolution → exactly that
        assertEquals(Dim(2400, 1080), CameraCapabilities.choosePreviewDim(redmiLike, 2400, 1080))
        // smaller 20:9 screen → still the smallest matching size at or above it
        assertEquals(Dim(1600, 720), CameraCapabilities.choosePreviewDim(redmiLike, 1600, 720))
        // no display-aspect size: full HD 16:9 before 720p
        val no20by9 = redmiLike.filter { it != Dim(2400, 1080) && it != Dim(1600, 720) }
        assertEquals(Dim(1920, 1080), CameraCapabilities.choosePreviewDim(no20by9, 2400, 1080))
        // old phone without full HD: 720p
        assertEquals(Dim(1280, 720), CameraCapabilities.choosePreviewDim(listOf(Dim(1280, 720), Dim(640, 480)), 2400, 1080))
        // nothing wide at all: the largest available
        assertEquals(Dim(1440, 1080), CameraCapabilities.choosePreviewDim(listOf(Dim(1440, 1080), Dim(640, 480)), 2400, 1080))
        assertEquals(null, CameraCapabilities.choosePreviewDim(emptyList(), 2400, 1080))
    }
}
