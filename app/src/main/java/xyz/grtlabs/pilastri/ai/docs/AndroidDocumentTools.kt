package xyz.grtlabs.pilastri.ai.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android side of [DocumentPipeline]: OCR with ML Kit's BUNDLED Latin text recognizer (the model ships in the APK, so
 * recognition runs on the device with no download and no Play Services), photo preparation and PDF page rendering.
 */
class AndroidImageTools(private val context: Context) : ImageTools {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun ocr(image: ByteArray): String {
        val bitmap = decode(image, OCR_SIDE) ?: throw DocumentReadException("Immagine non leggibile")
        try {
            return recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        } catch (e: DocumentReadException) {
            throw e
        } catch (e: Exception) {
            throw DocumentReadException("Lettura del testo non riuscita")
        } finally {
            bitmap.recycle()
        }
    }

    override fun downscale(image: ByteArray, maxSide: Int): ByteArray {
        val bitmap = decode(image, maxSide) ?: throw DocumentReadException("Immagine non leggibile")
        try {
            // Re-encoding drops every EXIF tag (location, device, time).
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Upright bitmap, at most [maxSide] on the long edge, decoded with a power-of-two sample first to keep memory small. */
    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val matrix = Matrix()
        try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
        } catch (_: Exception) { }
        val long = maxOf(raw.width, raw.height)
        if (long > maxSide) matrix.postScale(maxSide.toFloat() / long, maxSide.toFloat() / long)
        if (matrix.isIdentity) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (out !== raw) raw.recycle()
        return out
    }

    private companion object { const val OCR_SIDE = 2400 }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

/** Renders pages with the framework's [PdfRenderer] through a short-lived file in the app's cache (deleted before returning). */
class AndroidPdfPages(private val context: Context) : PdfPageRenderer {
    override fun render(pdf: ByteArray, maxPages: Int, maxSide: Int): List<ByteArray> {
        val dir = File(context.cacheDir, "attach").apply { mkdirs() }
        val file = File.createTempFile("render-", ".pdf", dir)
        try {
            file.writeBytes(pdf)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val renderer = try { PdfRenderer(fd) } catch (e: SecurityException) { throw DocumentReadException("PDF protetto da password") }
                try {
                    return (0 until minOf(renderer.pageCount, maxPages)).map { index ->
                        renderer.openPage(index).use { page ->
                            val scale = maxSide.toFloat() / maxOf(page.width, page.height)
                            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            try {
                                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                } finally {
                    renderer.close()
                }
            }
        } finally {
            file.delete()
        }
    }
}
