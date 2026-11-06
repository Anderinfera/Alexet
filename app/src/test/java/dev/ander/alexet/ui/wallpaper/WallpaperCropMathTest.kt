package dev.ander.alexet.ui.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WallpaperCropMathTest {

    @Test
    fun coverScaleCoversFrame() {
        // 2000x1000 image covering 1000x1000 frame => scale by height = 1
        assertEquals(1f, coverScale(2000f, 1000f, 1000f, 1000f), 0.0001f)
        // 1000x2000 covering 1000x1000 => scale by width = 1
        assertEquals(1f, coverScale(1000f, 2000f, 1000f, 1000f), 0.0001f)
        // 1000x1000 covering 2000x1000 => scale 2
        assertEquals(2f, coverScale(1000f, 1000f, 2000f, 1000f), 0.0001f)
    }

    @Test
    fun defaultCropIsCenteredCover() {
        // Square image, tall frame 9:19.5 — crop should be centered horizontally
        val frameW = 900f
        val frameH = 1950f
        val crop = cropRectInImagePixels(
            imageWidth = 2000,
            imageHeight = 2000,
            frameWidth = frameW,
            frameHeight = frameH,
            userScale = 1f,
            offsetX = 0f,
            offsetY = 0f,
        )
        assertTrue(crop.width > 0 && crop.height > 0)
        val cropAspect = crop.width.toFloat() / crop.height.toFloat()
        val frameAspect = frameW / frameH
        assertEquals(frameAspect, cropAspect, 0.02f)
        // Centered: left ≈ right margin
        val leftMargin = crop.left
        val rightMargin = 2000 - crop.right
        assertTrue(abs(leftMargin - rightMargin) <= 2)
    }

    @Test
    fun zoomInShrinksCropRegion() {
        val base = cropRectInImagePixels(3000, 2000, 900f, 1600f, userScale = 1f, offsetX = 0f, offsetY = 0f)
        val zoomed = cropRectInImagePixels(3000, 2000, 900f, 1600f, userScale = 2f, offsetX = 0f, offsetY = 0f)
        assertTrue(zoomed.width < base.width)
        assertTrue(zoomed.height < base.height)
    }

    @Test
    fun panShiftsCropHorizontally() {
        val center = cropRectInImagePixels(4000, 2000, 1000f, 1000f, 1f, 0f, 0f)
        val (maxX, _) = maxPanOffset(
            drawnW = 4000f * coverScale(4000f, 2000f, 1000f, 1000f),
            drawnH = 2000f * coverScale(4000f, 2000f, 1000f, 1000f),
            frameW = 1000f,
            frameH = 1000f,
        )
        val panned = cropRectInImagePixels(4000, 2000, 1000f, 1000f, 1f, maxX, 0f)
        // Positive offset moves image right => we see more of the left side of the image
        assertTrue(panned.left < center.left)
    }

    @Test
    fun outputSizePreservesAspectInsideDesired() {
        val out = outputSizeForAspect(frameAspect = 0.5f, desiredWidth = 2000, desiredHeight = 2000)
        assertEquals(0.5f, out.width.toFloat() / out.height.toFloat(), 0.02f)
        assertTrue(out.width <= 2000 && out.height <= 2000)
        assertTrue(out.width == 1000 || out.height == 2000)
    }

    @Test
    fun fitFrameSizeLetterboxesWiderContainer() {
        val (w, h) = fitFrameSize(2000f, 1000f, targetAspect = 0.5f)
        assertEquals(500f, w, 0.1f)
        assertEquals(1000f, h, 0.1f)
    }

    @Test
    fun pixel9ProFoldPresetIsNearSquareNotLandscape() {
        val preset = WALLPAPER_DEVICE_PRESETS.first { it.id == "pixel_9_pro_fold" }
        assertEquals(1080, preset.foldedWidth)
        assertEquals(2424, preset.foldedHeight)
        assertEquals(2076, preset.unfoldedWidth)
        assertEquals(2152, preset.unfoldedHeight)

        val unfoldedAspect = preset.unfoldedWidth.toFloat() / preset.unfoldedHeight.toFloat()
        // Must be near 1:1 (~0.9647), definitely not landscape (> 1.2) or stretched
        assertTrue("Aspect must be near 1:1 square, was $unfoldedAspect", unfoldedAspect in 0.90f..1.05f)
    }

    @Test
    fun panDownShiftsCropToBottomOfImage() {
        // 2000x4000 image (tall) cropped into 2000x2000 frame (square)
        val imageW = 2000
        val imageH = 4000
        val frameW = 1000f
        val frameH = 1000f
        val userScale = 1.2f

        val centerCrop = cropRectInImagePixels(imageW, imageH, frameW, frameH, userScale, 0f, 0f)
        val drawnH = imageH * coverScale(imageW.toFloat(), imageH.toFloat(), frameW, frameH) * userScale
        val (_, maxY) = maxPanOffset(frameW * userScale, drawnH, frameW, frameH)

        // Negative offsetY moves image UP relative to frame, meaning the frame shows the BOTTOM part of the image
        val bottomCrop = cropRectInImagePixels(imageW, imageH, frameW, frameH, userScale, 0f, -maxY)
        assertTrue("Bottom crop must have higher top than center crop", bottomCrop.top > centerCrop.top)
        assertTrue("Bottom crop bottom must be close to bottom of source image", bottomCrop.bottom >= imageH - 50)
    }
}
