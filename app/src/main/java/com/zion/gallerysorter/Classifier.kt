package com.zion.gallerysorter

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable

data class Classification(
    val category: Category,
    val confidence: Double,
    val labels: List<Pair<String, Float>> = emptyList(),
    val faces: Int = 0,
    val textLines: Int = 0,
)

/**
 * Phase 1 sorting: fixed rules first (folder, file name), then on-device ML Kit.
 * Everything runs on the phone; nothing is sent anywhere.
 *
 * Note: ML Kit's on-device text reader does not read Hebrew. We only use how MUCH
 * text it finds (digits and Latin letters on receipts and documents), not what it says.
 */
class Classifier : Closeable {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build()
    )
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )
    private val textReader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Must be called from a background thread (blocks on ML Kit tasks). */
    fun classify(item: MediaItem, bitmap: Bitmap?): Classification {
        val path = item.path.lowercase()
        val name = item.name.lowercase()

        // Rule 1: Samsung saves screenshots in DCIM/Screenshots or Pictures/Screenshots.
        if (path.contains("screenshot") || name.startsWith("screenshot")) {
            return Classification(Category.SCREENSHOT, 1.0)
        }
        if (bitmap == null) return Classification(Category.OTHER, 0.0)

        val image = InputImage.fromBitmap(bitmap, 0)
        val area = bitmap.width.toFloat() * bitmap.height.toFloat()

        val labels = Tasks.await(labeler.process(image))
            .map { it.text to it.confidence }
            .sortedByDescending { it.second }
        val faces = Tasks.await(faceDetector.process(image))
        // A face that is at least 2% of the frame: someone the photo is about, not a passer-by.
        val bigFaces = faces.count { f ->
            val b = f.boundingBox
            b.width().toFloat() * b.height().toFloat() >= 0.02f * area
        }
        val textLines = if (bigFaces == 0) {
            Tasks.await(textReader.process(image)).textBlocks.sumOf { it.lines.size }
        } else 0

        val byName = labels.associate { it.first.lowercase() to it.second }
        fun best(vararg names: String): Float = names.maxOf { byName[it] ?: 0f }

        val fromWhatsApp = path.contains("whatsapp")
        val food = best(
            "food", "fast food", "cuisine", "dish", "cake", "bread", "pizza", "dessert", "fruit",
            "vegetable", "sushi", "pasta", "salad", "cookie", "meal", "breakfast", "lunch", "dinner",
        )
        val greeting = best(
            "flower", "petal", "rose", "bouquet", "sunset", "sky", "heart", "candle", "butterfly",
            "star", "plant",
        )
        val work = best(
            "computer", "monitor", "screen", "keyboard", "laptop", "electronic device", "cable",
            "machine", "server", "desk", "office",
        )
        val top = labels.firstOrNull()?.second ?: 0f

        val (category, confidence) = when {
            textLines >= 12 -> Category.DOCUMENT to 0.85
            textLines >= 6 && !fromWhatsApp -> Category.DOCUMENT to 0.7
            bigFaces >= 1 -> Category.PEOPLE to 0.8
            faces.isNotEmpty() -> Category.PEOPLE to 0.55
            food >= 0.7f -> Category.FOOD to food.toDouble()
            fromWhatsApp && greeting >= 0.7f -> Category.GREETING to 0.7
            fromWhatsApp && (greeting >= 0.5f || textLines in 1..11) -> Category.GREETING to 0.55
            work >= 0.7f -> Category.WORK to 0.6
            top >= 0.8f -> Category.OTHER to 0.7
            else -> Category.OTHER to 0.5
        }
        return Classification(category, confidence, labels.take(8), faces.size, textLines)
    }

    override fun close() {
        labeler.close()
        faceDetector.close()
        textReader.close()
    }
}
