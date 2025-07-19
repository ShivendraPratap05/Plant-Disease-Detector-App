package com.example.plantdiseasetracker

import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.plantdiseasetracker.databinding.ActivityMainBinding
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var interpreter: Interpreter
    private val inputSize = 48
    private val modelFileName = "plant_model.tflite"
    private var photoUri: Uri? = null
    private lateinit var currentPhotoPath: String

    private val labels = arrayOf(
        "Pepper – Bacterial spot", "Pepper – Healthy",
        "Potato – Early blight", "Potato – Late blight", "Potato – Healthy",
        "Tomato – Bacterial spot", "Tomato – Early blight", "Tomato – Late blight",
        "Tomato – Leaf Mold", "Tomato – Septoria leaf spot", "Tomato – Spider mites",
        "Tomato – Target Spot", "Tomato – Yellow Leaf Curl Virus", "Tomato – Mosaic virus",
        "Tomato – Healthy"
    )

    private val pesticideMap = mapOf(
        "Pepper – Bacterial spot" to "Use Copper Oxychloride, 2g/L",
        "Potato – Early blight" to "Use Mancozeb, 2g/L",
        "Potato – Late blight" to "Use Metalaxyl, 1.5g/L",
        "Tomato – Early blight" to "Use Chlorothalonil, 2g/L",
        "Tomato – Late blight" to "Use Fosetyl-Al, 3g/L",
        "Tomato – Leaf Mold" to "Use Copper Hydroxide, 2.5g/L",
        "Tomato – Septoria leaf spot" to "Use Mancozeb, 2g/L",
        "Tomato – Yellow Leaf Curl Virus" to "Use Imidacloprid 17.8% SL, 1ml/L",
        "Tomato – Mosaic virus" to "Use systemic insecticide, avoid aphids",
        "Tomato – Spider mites" to "Use Abamectin, 1.5ml/L",
        "Tomato – Target Spot" to "Use Azoxystrobin, 1ml/L"
    )

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleImageSelection(uri)
            }
        }
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && photoUri != null) {
            val bitmap = handleCapturedImage(photoUri!!)
            bitmap?.let {
                binding.imageView.setImageBitmap(it)
                classifyImage(it)
            } ?: run {
                binding.resultText.text = "Failed to load captured image."
            }
        } else {
            binding.resultText.text = "Image capture cancelled or failed."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Request CAMERA permission at runtime
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 1)
        }

        try {
            interpreter = Interpreter(loadModelFile(modelFileName))
        } catch (e: IOException) {
            Log.e("MainActivity", "Model loading error", e)
            binding.resultText.text = "Failed to load model."
            return
        }

        binding.selectImageBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        }

        binding.captureImageBtn.setOnClickListener {
            dispatchTakePictureIntent()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            binding.resultText.text = "Camera permission is required to capture images."
        }
    }

    private fun handleImageSelection(uri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            val resized = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            binding.imageView.setImageBitmap(resized)
            classifyImage(resized)
        } catch (e: Exception) {
            Log.e("MainActivity", "Image loading failed", e)
            binding.resultText.text = "Failed to process image."
        }
    }

    private fun handleCapturedImage(uri: Uri): Bitmap? {
        val targetW = binding.imageView.width
        val targetH = binding.imageView.height
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(currentPhotoPath, this)
            inSampleSize = maxOf(1, minOf(outWidth / targetW, outHeight / targetH))
            inJustDecodeBounds = false
        }
        val bitmap = BitmapFactory.decodeFile(currentPhotoPath, options)
        return bitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun dispatchTakePictureIntent() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val photoFile: File? = try {
            createImageFile()
        } catch (ex: IOException) {
            Log.e("MainActivity", "File creation failed", ex)
            null
        }

        photoFile?.also {
            photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                it
            )
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            cameraCaptureLauncher.launch(takePictureIntent)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir).apply {
            currentPhotoPath = absolutePath
        }
    }

    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(fileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    private fun classifyImage(bitmap: Bitmap) {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in intValues) {
            byteBuffer.putFloat(((pixel shr 16 and 0xFF) / 255.0f))
            byteBuffer.putFloat(((pixel shr 8 and 0xFF) / 255.0f))
            byteBuffer.putFloat(((pixel and 0xFF) / 255.0f))
        }

        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(byteBuffer, output)

        val prediction = output[0]
        val maxIdx = prediction.indices.maxByOrNull { prediction[it] } ?: -1
        val confidence = prediction[maxIdx] * 100

        binding.resultText.text = "Prediction: ${labels[maxIdx]}\nConfidence: ${"%.2f".format(confidence)}%"
        val pesticide = pesticideMap[labels[maxIdx]] ?: "No pesticide suggestion available."
        binding.resultText.append("\n\nPesticide Suggestion:\n$pesticide")
    }

    override fun onDestroy() {
        if (::interpreter.isInitialized) interpreter.close()
        super.onDestroy()
    }
}
