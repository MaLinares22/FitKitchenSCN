package com.uth.fitkitchenscn

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.uth.fitkitchenscn.data.FirebaseRepository
import com.uth.fitkitchenscn.databinding.ActivityMainBinding
import com.uth.fitkitchenscn.ml.ObjectDetectorHelper
import com.uth.fitkitchenscn.ui.RecetaAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraProvider: ProcessCameraProvider
    private var imageCapture: ImageCapture? = null
    private var mode = Mode.LIVE
    @Volatile
    private var isShowingStaticImage = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analyzerBusy = AtomicBoolean(false)
    private val repository = FirebaseRepository()
    private val adapter = RecetaAdapter()
    private val detectedIngredients = linkedSetOf<String>()
    private var detector: ObjectDetectorHelper? = null

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else toast("Se necesita permiso de cámara para el modo en vivo")
    }

    private val galleryPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleGalleryUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.recipesRecycler.layoutManager = LinearLayoutManager(this)
        binding.recipesRecycler.adapter = adapter
        setupUi()
        lifecycleScope.launch {
            runCatching { repository.cargarDatos() }
                .onFailure { toast("No se pudieron cargar las recetas: ${it.message}") }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun setupUi() {
        binding.modeTabs.addTab(binding.modeTabs.newTab().setText("Modo En Vivo"))
        binding.modeTabs.addTab(binding.modeTabs.newTab().setText("Tomar Foto"))
        binding.modeTabs.addTab(binding.modeTabs.newTab().setText("Galería"))
        binding.modeTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                mode = Mode.entries[tab.position]
                binding.graphicOverlay.clear()
                if (mode == Mode.LIVE) {
                    isShowingStaticImage = false
                }
                binding.actionButton.setImageResource(
                    if (mode == Mode.GALLERY) android.R.drawable.ic_menu_gallery
                    else android.R.drawable.ic_menu_camera
                )
                binding.previewView.visibility = if (mode == Mode.LIVE) View.VISIBLE else View.GONE
                binding.frozenImage.visibility = if (mode == Mode.LIVE) View.GONE else View.VISIBLE
                if (mode == Mode.LIVE) {
                    binding.frozenImage.visibility = View.GONE
                    detector?.close()
                    detector = ObjectDetectorHelper(this@MainActivity, true)
                    startCamera()
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
        binding.actionButton.setOnClickListener {
            if (mode == Mode.GALLERY) {
                galleryPicker.launch(arrayOf("image/*", "video/*"))
            } else {
                if (mode == Mode.LIVE) {
                    binding.modeTabs.getTabAt(Mode.PHOTO.ordinal)?.select()
                } else {
                    isShowingStaticImage = false
                    binding.frozenImage.visibility = View.GONE
                    binding.graphicOverlay.clear()
                }
                capturePhoto()
            }
        }
        binding.clearButton.setOnClickListener {
            detectedIngredients.clear()
            refreshBasket()
        }
        binding.demoButton.setOnClickListener {
            val next = FirebaseRepository.INGREDIENTES_VALIDOS
                .firstOrNull { it !in detectedIngredients }
                ?: FirebaseRepository.INGREDIENTES_VALIDOS.first()
            detectedIngredients.add(next)
            refreshBasket()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            detector?.close()
            detector = ObjectDetectorHelper(this, true)
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                if (isShowingStaticImage || mode != Mode.LIVE ||
                    !analyzerBusy.compareAndSet(false, true)
                ) {
                    proxy.close()
                    return@setAnalyzer
                }
                val mediaImage = proxy.image
                if (mediaImage == null) {
                    proxy.close()
                    analyzerBusy.set(false)
                    return@setAnalyzer
                }
                val rotation = proxy.imageInfo.rotationDegrees
                val imageWidth = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
                val imageHeight = if (rotation == 90 || rotation == 270) proxy.width else proxy.height
                val detectionTask = detector?.process(proxy)
                if (detectionTask == null) {
                    proxy.close()
                    analyzerBusy.set(false)
                    return@setAnalyzer
                }
                detectionTask.addOnCompleteListener { task ->
                    try {
                        if (task.isSuccessful) {
                            val boxes = task.result.orEmpty()
                            runOnUiThread {
                                binding.graphicOverlay.updateImageInfo(imageWidth, imageHeight, false)
                                binding.graphicOverlay.setDetections(boxes)
                                addRecognized(boxes.map { it.label })
                            }
                        } else {
                            runOnUiThread {
                                toast("Error analizando cámara: ${task.exception?.message}")
                                binding.graphicOverlay.setDetections(emptyList())
                            }
                        }
                    } finally {
                        proxy.close()
                        analyzerBusy.set(false)
                    }
                }
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val file = File.createTempFile("fitkitchen_capture_", ".jpg", cacheDir)
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    isShowingStaticImage = true
                    binding.graphicOverlay.clear()
                    BitmapFactory.decodeFile(file.absolutePath)?.let(::analyzeBitmap)
                }
                override fun onError(exception: ImageCaptureException) {
                    toast("No se pudo capturar la foto: ${exception.message}")
                }
            }
        )
    }

    private fun handleGalleryUri(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                if (contentResolver.getType(uri)?.startsWith("video") == true) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this@MainActivity, uri)
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        retriever.release()
                    }
                } else {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            }
            if (bitmap == null) toast("No se pudo leer el archivo seleccionado") else analyzeBitmap(bitmap)
        }
    }

    private fun analyzeBitmap(bitmap: Bitmap) {
        isShowingStaticImage = true
        binding.frozenImage.setImageBitmap(bitmap)
        binding.frozenImage.visibility = View.VISIBLE
        binding.previewView.visibility = View.GONE
        lifecycleScope.launch {
            val imageDetector = ObjectDetectorHelper(this@MainActivity, false)
            try {
                val boxes = imageDetector.process(bitmap)
                binding.graphicOverlay.updateImageInfo(bitmap.width, bitmap.height, false)
                binding.graphicOverlay.setDetections(boxes)
                addRecognized(boxes.map { it.label })
            } catch (error: Exception) {
                toast("No se pudo analizar la imagen: ${error.message}")
            } finally {
                imageDetector.close()
            }
        }
    }

    private fun addRecognized(labels: List<String>) {
        labels.map { it.lowercase() }
            .filter { it in FirebaseRepository.INGREDIENTES_VALIDOS }
            .forEach { detectedIngredients.add(it) }
        refreshBasket()
    }

    private fun refreshBasket() {
        binding.ingredientChips.removeAllViews()
        detectedIngredients.forEach { ingredient ->
            val chip = Chip(this).apply {
                text = ingredient.replaceFirstChar { it.uppercase() }
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    detectedIngredients.remove(ingredient)
                    refreshBasket()
                }
                setOnClickListener { refreshRecipes() }
            }
            binding.ingredientChips.addView(chip)
        }
        refreshRecipes()
    }

    private fun refreshRecipes() {
        adapter.submitList(repository.filtrarRecetas(detectedIngredients))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        detector?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private enum class Mode { LIVE, PHOTO, GALLERY }
}
