package dev.ander.alexet.data.videoeditor

import android.net.Uri
import dev.ander.alexet.data.GalleryMedia
import org.json.JSONArray
import org.json.JSONObject

/**
 * A video editor project as text, kept in the saved state: after the system ends the app in the
 * background, the editor comes back with the user's cuts, texts and stickers instead of the
 * original clips. Undo history is not kept.
 */
object VideoProjectJson {

    fun encode(project: VideoProjectState): String = JSONObject().apply {
        put("clips", JSONArray().apply { project.clips.forEach { put(clip(it)) } })
        put("audio", JSONArray().apply { project.audioTracks.forEach { put(audio(it)) } })
        put("texts", JSONArray().apply { project.textOverlays.forEach { put(text(it)) } })
        put("images", JSONArray().apply { project.imageOverlays.forEach { put(image(it)) } })
        put("selectedClip", project.selectedClipIndex)
        project.selectedTextId?.let { put("selectedText", it) }
        project.selectedImageId?.let { put("selectedImage", it) }
        project.selectedAudioId?.let { put("selectedAudio", it) }
        put("aspect", project.aspectRatio.name)
    }.toString()

    /** Null when the text is not a project (or from an incompatible version). */
    fun decode(text: String): VideoProjectState? = runCatching {
        val o = JSONObject(text)
        VideoProjectState(
            clips = o.getJSONArray("clips").objects().map(::clip),
            audioTracks = o.optJSONArray("audio").objects().map(::audio),
            textOverlays = o.optJSONArray("texts").objects().map(::text),
            imageOverlays = o.optJSONArray("images").objects().map(::image),
            selectedClipIndex = o.optInt("selectedClip", 0),
            selectedTextId = o.optString("selectedText").takeIf { it.isNotEmpty() },
            selectedImageId = o.optString("selectedImage").takeIf { it.isNotEmpty() },
            selectedAudioId = o.optString("selectedAudio").takeIf { it.isNotEmpty() },
            aspectRatio = runCatching { VideoProjectAspectRatio.valueOf(o.optString("aspect")) }
                .getOrDefault(VideoProjectAspectRatio.ORIGINAL),
        )
    }.getOrNull()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun clip(c: VideoClipItem) = JSONObject().apply {
        put("id", c.id)
        put("media", media(c.media))
        put("source", c.sourceDurationMs)
        put("start", c.timelineStartMs)
        put("trimStart", c.startTrimMs)
        put("trimEnd", c.endTrimMs)
        put("speed", c.speed.toDouble())
        put("volume", c.volume.toDouble())
        put("muted", c.isMuted)
        put("rotation", c.rotation)
        put("flipH", c.flipH)
        put("flipV", c.flipV)
        put("brightness", c.brightness.toDouble())
        put("contrast", c.contrast.toDouble())
        put("saturation", c.saturation.toDouble())
        put("gamma", c.gamma.toDouble())
        put("panX", c.panX.toDouble())
        put("panY", c.panY.toDouble())
        put("zoom", c.zoomScale.toDouble())
    }

    private fun clip(o: JSONObject) = VideoClipItem(
        id = o.getString("id"),
        media = media(o.getJSONObject("media")),
        sourceDurationMs = o.getLong("source"),
        timelineStartMs = o.getLong("start"),
        startTrimMs = o.getLong("trimStart"),
        endTrimMs = o.getLong("trimEnd"),
        speed = o.getDouble("speed").toFloat(),
        volume = o.getDouble("volume").toFloat(),
        isMuted = o.getBoolean("muted"),
        rotation = o.getInt("rotation"),
        flipH = o.getBoolean("flipH"),
        flipV = o.getBoolean("flipV"),
        brightness = o.getDouble("brightness").toFloat(),
        contrast = o.getDouble("contrast").toFloat(),
        saturation = o.getDouble("saturation").toFloat(),
        gamma = o.getDouble("gamma").toFloat(),
        panX = o.getDouble("panX").toFloat(),
        panY = o.getDouble("panY").toFloat(),
        zoomScale = o.getDouble("zoom").toFloat(),
    )

    private fun audio(a: AudioTrackItem) = JSONObject().apply {
        put("id", a.id)
        put("uri", a.uri.toString())
        put("name", a.displayName)
        put("source", a.sourceDurationMs)
        put("start", a.timelineStartMs)
        put("trimStart", a.startTrimMs)
        put("trimEnd", a.endTrimMs)
        put("volume", a.volume.toDouble())
        put("muted", a.isMuted)
        put("fromVideo", a.isVideoSource)
    }

    private fun audio(o: JSONObject) = AudioTrackItem(
        id = o.getString("id"),
        uri = Uri.parse(o.getString("uri")),
        displayName = o.optString("name"),
        sourceDurationMs = o.getLong("source"),
        timelineStartMs = o.getLong("start"),
        startTrimMs = o.getLong("trimStart"),
        endTrimMs = o.getLong("trimEnd"),
        volume = o.getDouble("volume").toFloat(),
        isMuted = o.getBoolean("muted"),
        isVideoSource = o.optBoolean("fromVideo"),
    )

    private fun text(t: TextOverlayItem) = JSONObject().apply {
        put("id", t.id)
        put("text", t.text)
        put("color", t.textColor)
        put("bg", t.bgColor)
        put("size", t.fontSize.toDouble())
        put("start", t.startTimelineMs)
        put("end", t.endTimelineMs)
        put("x", t.normalizedX.toDouble())
        put("y", t.normalizedY.toDouble())
        put("scale", t.scale.toDouble())
        put("rotation", t.rotationDeg.toDouble())
        put("align", t.alignment)
        put("bold", t.isBold)
        put("italic", t.isItalic)
        put("underline", t.isUnderline)
        put("strike", t.isStrikethrough)
    }

    // Style keys are read with defaults: projects saved before them open as they looked.
    private fun text(o: JSONObject) = TextOverlayItem(
        id = o.getString("id"),
        text = o.getString("text"),
        textColor = o.getLong("color"),
        bgColor = o.getLong("bg"),
        fontSize = o.getDouble("size").toFloat(),
        startTimelineMs = o.getLong("start"),
        endTimelineMs = o.getLong("end"),
        normalizedX = o.getDouble("x").toFloat(),
        normalizedY = o.getDouble("y").toFloat(),
        scale = o.getDouble("scale").toFloat(),
        rotationDeg = o.getDouble("rotation").toFloat(),
        alignment = o.optInt("align", 1),
        isBold = o.optBoolean("bold", true),
        isItalic = o.optBoolean("italic", false),
        isUnderline = o.optBoolean("underline", false),
        isStrikethrough = o.optBoolean("strike", false),
    )

    private fun image(i: ImageOverlayItem) = JSONObject().apply {
        put("id", i.id)
        put("uri", i.uri.toString())
        put("start", i.startTimelineMs)
        put("end", i.endTimelineMs)
        put("x", i.normalizedX.toDouble())
        put("y", i.normalizedY.toDouble())
        put("scale", i.scale.toDouble())
        put("rotation", i.rotationDeg.toDouble())
    }

    private fun image(o: JSONObject) = ImageOverlayItem(
        id = o.getString("id"),
        uri = Uri.parse(o.getString("uri")),
        startTimelineMs = o.getLong("start"),
        endTimelineMs = o.getLong("end"),
        normalizedX = o.getDouble("x").toFloat(),
        normalizedY = o.getDouble("y").toFloat(),
        scale = o.getDouble("scale").toFloat(),
        rotationDeg = o.getDouble("rotation").toFloat(),
    )

    private fun media(m: GalleryMedia) = JSONObject().apply {
        put("id", m.id)
        put("uri", m.uri.toString())
        put("name", m.displayName)
        put("mime", m.mimeType)
        put("taken", m.dateTakenMs)
        put("modified", m.dateModifiedMs)
        put("added", m.dateAddedMs)
        put("size", m.sizeBytes)
        put("w", m.width)
        put("h", m.height)
        put("duration", m.durationMs)
        put("bucket", m.bucketId)
        put("bucketName", m.bucketName)
        put("rel", m.relativePath)
        put("hidden", m.isHidden)
        m.absolutePath?.let { put("path", it) }
        put("rawBucket", m.rawBucketId)
    }

    private fun media(o: JSONObject) = GalleryMedia(
        id = o.getLong("id"),
        uri = Uri.parse(o.getString("uri")),
        displayName = o.getString("name"),
        mimeType = o.optString("mime"),
        dateTakenMs = o.optLong("taken"),
        dateModifiedMs = o.optLong("modified"),
        dateAddedMs = o.optLong("added"),
        sizeBytes = o.optLong("size"),
        width = o.optInt("w"),
        height = o.optInt("h"),
        durationMs = o.optLong("duration"),
        bucketId = o.optLong("bucket"),
        bucketName = o.optString("bucketName"),
        relativePath = o.optString("rel"),
        isHidden = o.optBoolean("hidden"),
        absolutePath = if (o.has("path")) o.getString("path") else null,
        rawBucketId = o.optLong("rawBucket"),
    )
}
