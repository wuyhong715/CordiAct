package com.cordiact.core.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageNormalizationPolicyTest {
    @Test
    fun smallUprightJpegNeedsNoTransform() {
        val plan = ImageNormalizationPolicy.plan("image/jpeg", 800, 600, 1)
        assertFalse(plan.requiresTransform)
        assertEquals(800, plan.outputWidth)
        assertEquals(600, plan.outputHeight)
        assertEquals(1, plan.sampleSize)
        assertEquals(0, plan.rotationDegrees)
        assertEquals("image/jpeg", plan.outputMimeType)
    }

    @Test
    fun pngKeepsPngSoTransparencySurvives() {
        assertEquals("image/png", ImageNormalizationPolicy.plan("image/png", 100, 100, 1).outputMimeType)
        assertEquals("image/jpeg", ImageNormalizationPolicy.plan("image/webp", 100, 100, 1).outputMimeType)
    }

    @Test
    fun rotatedExifSwapsAxesAndRotates() {
        val plan = ImageNormalizationPolicy.plan("image/jpeg", 4000, 3000, exifOrientation = 6)
        assertEquals(90, plan.rotationDegrees)
        assertTrue(plan.requiresTransform)
        // Orientation 6 means the stored pixels are sideways: the visible image is 3000x4000.
        assertEquals(1536, plan.outputWidth)
        assertEquals(2048, plan.outputHeight)
    }

    @Test
    fun mirroredOrientationsSetTheFlip() {
        val transpose = ImageNormalizationPolicy.plan("image/jpeg", 100, 200, exifOrientation = 5)
        assertEquals(90, transpose.rotationDegrees)
        assertTrue(transpose.flipHorizontal)

        val verticalFlip = ImageNormalizationPolicy.plan("image/jpeg", 100, 200, exifOrientation = 4)
        assertEquals(0, verticalFlip.rotationDegrees)
        assertTrue(verticalFlip.flipVertical)
    }

    @Test
    fun largeImageIsSampledAndScaled() {
        val plan = ImageNormalizationPolicy.plan("image/jpeg", 8000, 4000, 1)
        assertTrue(plan.requiresTransform)
        assertEquals(2, plan.sampleSize)
        assertEquals(2048, plan.outputWidth)
        assertEquals(1024, plan.outputHeight)
    }

    @Test
    fun unknownOrientationIsTreatedAsUpright() {
        val plan = ImageNormalizationPolicy.plan("image/jpeg", 100, 100, exifOrientation = 0)
        assertEquals(0, plan.rotationDegrees)
        assertFalse(plan.requiresTransform)
    }

    @Test
    fun sampleSizeNeverUndershootsTheTargetEdge() {
        val plan = ImageNormalizationPolicy.plan("image/jpeg", 12000, 100, 1, maxEdge = 2048)
        assertTrue(plan.sampleSize * plan.outputWidth <= 12000 * 2)
        assertEquals(2048, plan.outputWidth)
    }
}
