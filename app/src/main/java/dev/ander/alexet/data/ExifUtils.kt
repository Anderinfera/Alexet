package dev.ander.alexet.data

import androidx.exifinterface.media.ExifInterface
import dev.ander.alexet.data.AppLog as Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ExifUtils {
    private const val TAG = "ExifUtils"

    private val TAGS_TO_REMOVE = listOf(
        // GPS
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        "GPSDestLatitude",
        "GPSDestLatitudeRef",
        "GPSDestLongitude",
        "GPSDestLongitudeRef",
        "GPSDestBearing",
        "GPSDestBearingRef",
        "GPSDestDistance",
        "GPSDestDistanceRef",
        "GPSSpeed",
        "GPSSpeedRef",
        "GPSTrack",
        "GPSTrackRef",
        "GPSImgDirection",
        "GPSImgDirectionRef",
        "GPSAreaInformation",
        "GPSDifferential",
        "GPSDOP",

        // Device & Author
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        "CameraOwnerName",
        "BodySerialNumber",
        "LensMake",
        "LensModel",
        "LensSerialNumber",
        "LensSpecification",
        "DeviceSettingDescription",

        // Capture settings & hardware
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        "ExposureProgram",
        "SpectralSensitivity",
        "PhotographicSensitivity",
        "OECF",
        "ShutterSpeedValue",
        "ApertureValue",
        "BrightnessValue",
        "ExposureBiasValue",
        "MaxApertureValue",
        "SubjectDistance",
        "MeteringMode",
        "LightSource",
        "Flash",
        ExifInterface.TAG_FOCAL_LENGTH,
        "SubjectArea",
        "MakerNote",
        "UserComment",
        "SubSecTime",
        "SubSecTimeOriginal",
        "SubSecTimeDigitized",
        "FlashpixVersion",
        "ColorSpace",
        "FlashEnergy",
        "SpatialFrequencyResponse",
        "FocalPlaneXResolution",
        "FocalPlaneYResolution",
        "FocalPlaneResolutionUnit",
        "SubjectLocation",
        "ExposureIndex",
        "SensingMethod",
        "FileSource",
        "SceneType",
        "CFAPattern",
        "CustomRendered",
        "ExposureMode",
        ExifInterface.TAG_WHITE_BALANCE,
        "DigitalZoomRatio",
        "FocalLengthIn35mmFilm",
        "SceneCaptureType",
        "GainControl",
        "Contrast",
        "Saturation",
        "Sharpness",
        "SubjectDistanceRange",
        "ImageUniqueID",
    )

    fun stripExifExceptDate(file: File): Boolean {
        if (!file.exists() || !file.canWrite()) return false
        val ext = file.extension.lowercase()
        if (ext !in listOf("jpg", "jpeg", "png", "heic", "heif", "webp", "dng", "cr2", "nef")) {
            return false
        }
        // Written into a copy next to the photo, which then takes its place in one rename: the
        // photo rewritten in place was left broken when the app was killed half-way. A copy left
        // by such a kill is removed at the next start (RenameJournal).
        val dir = file.absoluteFile.parentFile ?: return false
        val temp = File(dir, ".${file.nameWithoutExtension}.alexet-exif.${file.extension}")
        return try {
            file.copyTo(temp, overwrite = true)
            RenameJournal.addDiscard(temp)
            val exif = ExifInterface(temp.absolutePath)
            val dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))

            for (tag in TAGS_TO_REMOVE) {
                exif.setAttribute(tag, null)
            }

            // Retain/ensure valid date
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateTaken)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTaken)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTaken)

            exif.saveAttributes()
            // On the disk before it replaces the original.
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            if (!temp.renameTo(file)) throw java.io.IOException("Cannot replace ${file.name}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to strip EXIF for ${file.name}", e)
            false
        } finally {
            // Gone already after the rename; otherwise the original stays as it was.
            temp.delete()
            RenameJournal.remove(listOf(temp))
        }
    }

    fun stripExifExceptDate(context: android.content.Context, uri: android.net.Uri): Boolean {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return false
            pfd.use {
                val exif = ExifInterface(it.fileDescriptor)
                val dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    ?: SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())

                for (tag in TAGS_TO_REMOVE) {
                    exif.setAttribute(tag, null)
                }

                exif.setAttribute(ExifInterface.TAG_DATETIME, dateTaken)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTaken)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTaken)

                exif.saveAttributes()
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to strip EXIF for uri $uri", e)
            false
        }
    }

    /**
     * A copy of [source] with no metadata at all except the capture date: decoded (upright by its
     * orientation) and written as JPEG. For formats whose tags can't be rewritten in place
     * (HEIC, RAW). Null when the image can't be decoded.
     */
    fun reencodeWithoutMetadata(source: File, target: File): File? = try {
        val exif = runCatching { ExifInterface(source.absolutePath) }.getOrNull()
        val date = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        // Sampled down to at most ~24 MP: a full-size 50-200 MP photo runs out of memory.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(source.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > 24_000_000L) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeFile(
            source.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
        if (decoded == null) {
            null
        } else {
            val upright = ImageEditEngine.applyExifOrientation(decoded, orientation)
            try {
                val written = target.outputStream().use { out -> upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out) }
                if (!written) throw java.io.IOException("JPEG encode failed")
            } finally {
                if (upright !== decoded) upright.recycle()
                decoded.recycle()
            }
            if (date != null) {
                runCatching {
                    ExifInterface(target.absolutePath).apply {
                        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
                        setAttribute(ExifInterface.TAG_DATETIME, date)
                        saveAttributes()
                    }
                }
            }
            target
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Re-encoding ${source.name} without metadata failed", e)
        target.delete()
        null
    }

    fun generateAnonymousFileName(originalName: String): String {
        val dot = originalName.lastIndexOf('.')
        val ext = if (dot >= 0) originalName.substring(dot) else ""
        val randomHex = UUID.randomUUID().toString().replace("-", "").take(8)
        return "anon_$randomHex$ext"
    }
}
