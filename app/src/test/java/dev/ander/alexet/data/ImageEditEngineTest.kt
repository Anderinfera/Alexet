package dev.ander.alexet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditEngineTest {
    @Test
    fun encoderExtensionMapsUnsupportedToJpg() {
        assertEquals("png", ImageEditEngine.encoderExtensionFor("a.PNG"))
        assertEquals("webp", ImageEditEngine.encoderExtensionFor("a.webp"))
        assertEquals("jpg", ImageEditEngine.encoderExtensionFor("a.jpeg"))
        assertEquals("jpg", ImageEditEngine.encoderExtensionFor("shot.heic"))
        assertEquals("jpg", ImageEditEngine.encoderExtensionFor("anim.gif"))
        assertEquals("jpg", ImageEditEngine.encoderExtensionFor("x.bmp"))
    }

    @Test
    fun encoderMatchesOnlyNativeFormats() {
        assertTrue(ImageEditEngine.encoderMatchesOriginalExtension("a.jpg"))
        assertTrue(ImageEditEngine.encoderMatchesOriginalExtension("a.png"))
        assertTrue(ImageEditEngine.encoderMatchesOriginalExtension("a.webp"))
        assertFalse(ImageEditEngine.encoderMatchesOriginalExtension("a.heic"))
        assertFalse(ImageEditEngine.encoderMatchesOriginalExtension("a.gif"))
        assertFalse(ImageEditEngine.encoderMatchesOriginalExtension("a.bmp"))
    }

    @Test
    fun exifOrientationOpsMatchCoil() {
        assertTrue(ImageEditEngine.exifOrientationOps(1).isEmpty())
        assertTrue(ImageEditEngine.exifOrientationOps(0).isEmpty())

        val flipH = ImageEditEngine.exifOrientationOps(2)
        assertEquals(1, flipH.size)
        assertEquals(-1f, flipH[0].scaleX, 0.001f)
        assertEquals(1f, flipH[0].scaleY, 0.001f)
        assertEquals(0f, flipH[0].rotateDeg, 0.001f)

        val rot180 = ImageEditEngine.exifOrientationOps(3)
        assertEquals(180f, rot180.single().rotateDeg, 0.001f)

        val flipV = ImageEditEngine.exifOrientationOps(4)
        assertEquals(-1f, flipV.single().scaleY, 0.001f)

        val transpose = ImageEditEngine.exifOrientationOps(5)
        assertEquals(90f, transpose[0].rotateDeg, 0.001f)
        assertEquals(-1f, transpose[1].scaleX, 0.001f)

        val rot90 = ImageEditEngine.exifOrientationOps(6)
        assertEquals(90f, rot90.single().rotateDeg, 0.001f)

        val transverse = ImageEditEngine.exifOrientationOps(7)
        assertEquals(-90f, transverse[0].rotateDeg, 0.001f)
        assertEquals(-1f, transverse[1].scaleX, 0.001f)

        val rot270 = ImageEditEngine.exifOrientationOps(8)
        assertEquals(-90f, rot270.single().rotateDeg, 0.001f)
    }

    @Test
    fun userGeometryAppliesScaleBeforeRotation() {
        val (sx, sy, rot) = ImageEditEngine.userGeometryScaleThenRotate(90, flipH = true, flipV = false)
        assertEquals(-1f, sx, 0.001f)
        assertEquals(1f, sy, 0.001f)
        assertEquals(90f, rot, 0.001f)
        val none = ImageEditEngine.userGeometryScaleThenRotate(0, flipH = false, flipV = false)
        assertEquals(1f, none.first, 0.001f)
        assertEquals(1f, none.second, 0.001f)
        assertEquals(0f, none.third, 0.001f)
        val rot180 = ImageEditEngine.userGeometryScaleThenRotate(180, flipH = true, flipV = true)
        assertEquals(-1f, rot180.first, 0.001f)
        assertEquals(-1f, rot180.second, 0.001f)
        assertEquals(180f, rot180.third, 0.001f)
    }

    @Test
    fun gammaRgbScaleMatchesPreviewFactor() {
        assertEquals(1f, ImageEditEngine.gammaRgbScale(1f), 0.001f)
        assertEquals(0.5f, ImageEditEngine.gammaRgbScale(2f), 0.001f)
        assertEquals(2f, ImageEditEngine.gammaRgbScale(0.5f), 0.001f)
        assertEquals(1f, ImageEditEngine.gammaRgbScale(0.05f), 0.001f)
    }

    @Test
    fun editStateIsModifiedDetectsAllNewParameters() {
        val defaultState = ImageEditState()
        assertFalse(defaultState.isModified)

        assertTrue(defaultState.copy(shadows = 0.2f).isModified)
        assertTrue(defaultState.copy(highlights = -0.1f).isModified)
        assertTrue(defaultState.copy(exposure = 0.5f).isModified)
        assertTrue(defaultState.copy(gamma = -0.3f).isModified)
        assertTrue(defaultState.copy(temperature = 0.4f).isModified)
        assertTrue(defaultState.copy(sharpness = 0.6f).isModified)
        assertTrue(defaultState.copy(blur = BlurConfig(type = BlurType.RADIAL)).isModified)
        assertTrue(defaultState.copy(blur = BlurConfig(type = BlurType.MIRROR)).isModified)
        assertTrue(defaultState.copy(blur = BlurConfig(type = BlurType.LINEAR)).isModified)
        assertTrue(defaultState.copy(blur = BlurConfig(type = BlurType.GAUSSIAN)).isModified)
        assertTrue(defaultState.copy(texts = listOf(TextOverlay(text = "abc"))).isModified)
        assertTrue(defaultState.copy(brushStrokes = listOf(BrushStroke())).isModified)
    }

    @Test
    fun blurConfigDefaultsAreConsistent() {
        val config = BlurConfig()
        assertEquals(BlurType.NONE, config.type)
        assertEquals(25f, config.radius, 0.001f)
        assertEquals(0.5f, config.focusCenter.x, 0.001f)
        assertEquals(0.5f, config.focusCenter.y, 0.001f)
    }

    @Test
    fun overlaysTransformationsAreAccurate() {
        val sticker = StickerOverlay(uri = android.net.DummyUri(), normalizedX = 0.25f, normalizedY = 0.25f, scale = 1f, rotationDeg = 0f)
        val stroke = BrushStroke(points = listOf(NormPoint(0.25f, 0.25f), NormPoint(0.75f, 0.75f)), strokeWidthPx = 10f)
        val text = TextOverlay(text = "Hello", normalizedX = 0.5f, normalizedY = 0.5f, scale = 1f, rotationDeg = 0f)

        // Rotate 90
        val (rStickers, rStrokes, rTexts) = dev.ander.alexet.ui.editor.rotateOverlaysByDelta(
            listOf(sticker), listOf(stroke), listOf(text), 90,
        )
        assertEquals(0.75f, rStickers[0].normalizedX, 0.001f)
        assertEquals(0.25f, rStickers[0].normalizedY, 0.001f)
        assertEquals(90f, rStickers[0].rotationDeg, 0.001f)
        assertEquals(0.75f, rStrokes[0].points[0].x, 0.001f)
        assertEquals(0.25f, rStrokes[0].points[0].y, 0.001f)

        // Flip Horizontal
        val (fhStickers, fhStrokes, fhTexts) = dev.ander.alexet.ui.editor.flipOverlaysH(
            listOf(sticker), listOf(stroke), listOf(text),
        )
        assertEquals(0.75f, fhStickers[0].normalizedX, 0.001f)
        assertTrue(fhStickers[0].flipH)

        // Flip Vertical
        val (fvStickers, fvStrokes, fvTexts) = dev.ander.alexet.ui.editor.flipOverlaysV(
            listOf(sticker), listOf(stroke), listOf(text),
        )
        assertEquals(0.75f, fvStickers[0].normalizedY, 0.001f)
        assertTrue(fvStickers[0].flipV)

        // Remap to crop
        val crop = CropRect(left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f)
        val (cStickers, cStrokes, cTexts) = dev.ander.alexet.ui.editor.remapOverlaysToCrop(
            listOf(sticker), listOf(stroke), listOf(text), crop,
        )
        // (0.25 - 0.2) / 0.6 = 0.05 / 0.6 = 0.08333f
        assertEquals(0.0833f, cStickers[0].normalizedX, 0.001f)
        assertEquals(0.0833f, cStickers[0].normalizedY, 0.001f)
    }
    @Test
    fun exportKeepsFullResolutionWithinPixelBudget() {
        assertEquals(2208 to 1840, ImageEditEngine.exportTargetSize(2208, 1840))
        // 4100 px long side used to be halved by power-of-two sampling; 12.3 MP fits the budget.
        assertEquals(4100 to 3000, ImageEditEngine.exportTargetSize(4100, 3000))
        assertEquals(1, ImageEditEngine.exportSampleSize(4100, 3000, 4100, 3000))
    }

    @Test
    fun exportScalesLargeSourcesProportionallyToBudget() {
        val (w, h) = ImageEditEngine.exportTargetSize(2208, 9000)
        assertTrue(w.toLong() * h <= ImageEditEngine.EXPORT_MAX_PIXELS)
        assertTrue("long screenshot keeps most of its width, was 552 px", w >= 2000)
        assertEquals(2208f / 9000f, w.toFloat() / h, 0.001f)
        assertEquals(1, ImageEditEngine.exportSampleSize(2208, 9000, w, h))
    }

    @Test
    fun exportSampleCapsIntermediateDecodeForHugeSources() {
        val (w, h) = ImageEditEngine.exportTargetSize(9248, 6936)
        // Full 64 MP decode would exceed 2x budget; one halving (16 MP) stays close to target.
        assertEquals(2, ImageEditEngine.exportSampleSize(9248, 6936, w, h))
    }

    private fun webpHeader(chunk: String, flags: Int = 0): ByteArray {
        val bytes = ByteArray(32)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        chunk.toByteArray().copyInto(bytes, 12)
        bytes[20] = flags.toByte()
        return bytes
    }

    @Test
    fun webpKindReadsHeader() {
        assertEquals(ImageEditEngine.WebpKind.LOSSY, ImageEditEngine.webpKind(webpHeader("VP8 ")))
        assertEquals(ImageEditEngine.WebpKind.LOSSLESS, ImageEditEngine.webpKind(webpHeader("VP8L")))
        assertEquals(ImageEditEngine.WebpKind.ANIMATED, ImageEditEngine.webpKind(webpHeader("VP8X", flags = 0x02)))
        assertEquals(ImageEditEngine.WebpKind.LOSSY, ImageEditEngine.webpKind(webpHeader("VP8X", flags = 0x10)))
        assertEquals(ImageEditEngine.WebpKind.NOT_WEBP, ImageEditEngine.webpKind(ByteArray(32)))
        assertEquals(ImageEditEngine.WebpKind.NOT_WEBP, ImageEditEngine.webpKind(ByteArray(4)))
    }
    private val geometries = listOf(0, 90, 180, 270).flatMap { r ->
        listOf(false, true).flatMap { h -> listOf(false, true).map { v -> Triple(r, h, v) } }
    }

    @Test
    fun geometryPointMappingRoundTrips() {
        val p = NormPoint(0.2f, 0.7f)
        for ((r, h, v) in geometries) {
            val back = ImageEditEngine.pointDisplayToSource(ImageEditEngine.pointSourceToDisplay(p, r, h, v), r, h, v)
            assertEquals("r=$r h=$h v=$v", p.x, back.x, 0.0001f)
            assertEquals("r=$r h=$h v=$v", p.y, back.y, 0.0001f)
        }
    }

    @Test
    fun quarterTurnMatchesOverlayRotation() {
        // rotateOverlaysByDelta(90): (x, y) -> (1 - y, x)
        val d = ImageEditEngine.pointSourceToDisplay(NormPoint(0.2f, 0.7f), 90, flipH = false, flipV = false)
        assertEquals(0.3f, d.x, 0.0001f)
        assertEquals(0.2f, d.y, 0.0001f)
    }

    @Test
    fun cropOnRotatedDisplayMapsToSource() {
        // Rotated 90 deg clockwise, the displayed left edge comes from the source bottom edge,
        // so the displayed left third is the source bottom third.
        val src = ImageEditEngine.cropDisplayToSource(CropRect(0f, 0f, 1f / 3f, 1f), 90, flipH = false, flipV = false)
        assertEquals(0f, src.left, 0.0001f)
        assertEquals(2f / 3f, src.top, 0.0001f)
        assertEquals(1f, src.right, 0.0001f)
        assertEquals(1f, src.bottom, 0.0001f)
        // Left third after a horizontal flip is the right third of the source.
        val flipped = ImageEditEngine.cropDisplayToSource(CropRect(0f, 0f, 1f / 3f, 1f), 0, flipH = true, flipV = false)
        assertEquals(2f / 3f, flipped.left, 0.0001f)
        assertEquals(1f, flipped.right, 0.0001f)
    }

    @Test
    fun composeCropNestsRelativeRects() {
        val c = ImageEditEngine.composeCrop(CropRect(0.5f, 0f, 1f, 0.5f), CropRect(0f, 0.5f, 0.5f, 1f))
        assertEquals(CropRect(0.5f, 0.25f, 0.75f, 0.5f), c)
    }

    @Test
    fun blurFocusFollowsGeometryAndCrop() {
        val cfg = BlurConfig(type = BlurType.LINEAR, focusCenter = NormPoint(0.25f, 0.5f), focusAngle = 30f)
        for ((r, h, v) in geometries) {
            val back = ImageEditEngine.blurDisplayToSource(ImageEditEngine.blurSourceToDisplay(cfg, r, h, v), r, h, v)
            assertEquals(cfg.focusCenter.x, back.focusCenter.x, 0.0001f)
            assertEquals(cfg.focusAngle, back.focusAngle, 0.01f)
        }
        // Crop to the left half of a square image: centre x 0.25 -> 0.5, shorter side halves.
        val cropped = ImageEditEngine.remapBlurToCrop(cfg.copy(focusSize = 0.2f), CropRect(0f, 0f, 0.5f, 1f), 1f)
        assertEquals(0.5f, cropped.focusCenter.x, 0.0001f)
        assertEquals(0.4f, cropped.focusSize, 0.0001f)
    }

    @Test
    fun sizesAreRelativeToImage() {
        assertEquals(48f, ImageEditEngine.textFontPx(24f, 2160f), 0.001f)
        assertEquals(10f, ImageEditEngine.brushWidthPx(20f, 500f), 0.001f)
        assertEquals(ImageEditEngine.blurRadiusPx(25f, 4000, 3000), 2f * ImageEditEngine.blurRadiusPx(25f, 2000, 1500), 0.001f)
    }

    @Test
    fun blurTransitionFollowsFalloffAndSurvivesCrop() {
        assertEquals(0.01f, ImageEditEngine.blurTransitionFraction(0f), 0.0001f)
        assertEquals(0.60f, ImageEditEngine.blurTransitionFraction(1f), 0.0001f)
        val cfg = BlurConfig(type = BlurType.LINEAR, falloff = 0.25f)
        // Square image cropped to its left half: shorter side halves, transition keeps its pixel length.
        val cropped = ImageEditEngine.remapBlurToCrop(cfg, CropRect(0f, 0f, 0.5f, 1f), 1f)
        assertEquals(
            ImageEditEngine.blurTransitionFraction(cfg.falloff) * 1000f,
            ImageEditEngine.blurTransitionFraction(cropped.falloff) * 500f,
            0.5f,
        )
    }

    @Test
    fun linearBlurKeepsItsSideThroughFlips() {
        // Angle 0 blurs above the line. A horizontal mirror keeps "above"; a vertical one turns it below.
        val above = BlurConfig(type = BlurType.LINEAR, focusAngle = 0f)
        assertEquals(0f, ImageEditEngine.blurSourceToDisplay(above, 0, flipH = true, flipV = false).focusAngle, 0.001f)
        assertEquals(180f, ImageEditEngine.blurSourceToDisplay(above, 0, flipH = false, flipV = true).focusAngle, 0.001f)
        // Tilted 30 degrees: mirrored left-right it tilts the other way.
        val tilted = above.copy(focusAngle = 30f)
        assertEquals(330f, ImageEditEngine.blurSourceToDisplay(tilted, 0, flipH = true, flipV = false).focusAngle, 0.001f)
    }
}
