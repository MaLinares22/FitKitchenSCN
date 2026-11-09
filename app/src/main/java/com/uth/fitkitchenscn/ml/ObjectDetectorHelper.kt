package com.uth.fitkitchenscn.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import com.uth.fitkitchenscn.model.DetectedBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ObjectDetectorHelper(
    context: Context,
    private val streamMode: Boolean
) {
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(
                if (streamMode) ObjectDetectorOptions.STREAM_MODE
                else ObjectDetectorOptions.SINGLE_IMAGE_MODE
            )
            .enableMultipleObjects()
            .build()
    )

    private val labeler: ImageLabeler? = createLabeler(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun createLabeler(context: Context): ImageLabeler? {
        if (context.assets.list("")?.contains(MODEL_FILE) != true) return null

        val localModel = LocalModel.Builder()
            .setAssetFilePath(MODEL_FILE)
            .build()
        val options = CustomImageLabelerOptions.Builder(localModel)
            .setMaxResultCount(1)
            .build()
        return ImageLabeling.getClient(options)
    }

    suspend fun process(bitmap: Bitmap): List<DetectedBox> {
        val objects = await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        return classifyObjects(bitmap, objects)
    }

    fun process(imageProxy: ImageProxy): Task<List<DetectedBox>> {
        val result = TaskCompletionSource<List<DetectedBox>>()
        val sourceBitmap = runCatching { imageProxyToBitmap(imageProxy) }.getOrNull()

        if (sourceBitmap == null) {
            result.setResult(emptyList())
            return result.task
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val bitmap = rotate(sourceBitmap, rotation)
        if (bitmap !== sourceBitmap) sourceBitmap.recycle()

        scope.launch {
            try {
                result.setResult(process(bitmap))
            } catch (error: Exception) {
                result.setException(error)
            } finally {
                bitmap.recycle()
            }
        }
        return result.task
    }

    fun close() {
        scope.coroutineContext.cancel()
        detector.close()
        labeler?.close()
    }

    private suspend fun classifyObjects(
        bitmap: Bitmap,
        objects: List<DetectedObject>
    ): List<DetectedBox> {
        val activeLabeler = labeler ?: return emptyList()

        return objects.mapNotNull { detectedObject ->
            val crop = createCrop(bitmap, detectedObject)
            if (crop == null) return@mapNotNull null

            try {
                val bestLabel = await(
                    activeLabeler.process(InputImage.fromBitmap(crop, 0))
                ).maxByOrNull(ImageLabel::getConfidence)

                if (bestLabel == null || bestLabel.confidence < MIN_LABEL_CONFIDENCE) {
                    return@mapNotNull null
                }

                val labelName = LABEL_CLASSES.getOrElse(bestLabel.index) { "desconocido" }

                if (labelName == "desconocido") {
                    null
                } else {
                    DetectedBox(
                        rect = detectedObject.boundingBox,
                        label = labelName,
                        confidence = bestLabel.confidence,
                        trackingId = detectedObject.trackingId
                    )
                }
            } finally {
                crop.recycle()
            }
        }
    }

    private fun createCrop(bitmap: Bitmap, detectedObject: DetectedObject): Bitmap? {
        val bounds = detectedObject.boundingBox
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yuvBytes = yuv420ToNv21(imageProxy)
        val yuvImage = YuvImage(
            yuvBytes,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        val output = java.io.ByteArrayOutputStream()
        check(yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 95, output)) {
            "No se pudo convertir el frame de cámara"
        }
        return android.graphics.BitmapFactory.decodeByteArray(
            output.toByteArray(), 0, output.size()
        ) ?: error("No se pudo decodificar el frame de cámara")
    }

    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val planes = imageProxy.planes
        val width = imageProxy.width
        val height = imageProxy.height
        val output = ByteArray(width * height * 3 / 2)

        copyPlane(planes[0], width, height, output, 0, 1)

        val chromaOffset = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val uPlane = planes[1]
        val vPlane = planes[2]
        repeat(chromaHeight) { row ->
            repeat(chromaWidth) { column ->
                val uIndex = row * uPlane.rowStride + column * uPlane.pixelStride
                val vIndex = row * vPlane.rowStride + column * vPlane.pixelStride
                val destination = chromaOffset + row * width + column * 2
                output[destination] = vPlane.buffer.get(vIndex)
                output[destination + 1] = uPlane.buffer.get(uIndex)
            }
        }
        return output
    }

    private fun copyPlane(plane: ImageProxy.PlaneProxy, width: Int, height: Int, output: ByteArray, outputOffset: Int, outputStride: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val row = ByteArray(rowStride)
        var destination = outputOffset

        repeat(height) { rowIndex ->
            val rowLength = if (pixelStride == 1) width else (width - 1) * pixelStride + 1
            buffer.position(rowIndex * rowStride)
            buffer.get(row, 0, minOf(rowLength, buffer.remaining()))
            var source = 0
            repeat(width) {
                output[destination] = row[source]
                destination += outputStride
                source += pixelStride
            }
        }
    }

    private suspend fun <T> await(task: Task<T>): T =
        suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener {
                if (continuation.isActive) continuation.resume(it)
            }.addOnFailureListener {
                if (continuation.isActive) continuation.resumeWithException(it)
            }
        }

    companion object {
        private const val MODEL_FILE = "fitkichen_model.tflite"
        private const val MIN_LABEL_CONFIDENCE = 0.55f
        private val LABEL_CLASSES = listOf(
            "limon",
            "mayonesa",
            "huevo",
            "atun",
            "granola",
            "leche"
        )
    }
}