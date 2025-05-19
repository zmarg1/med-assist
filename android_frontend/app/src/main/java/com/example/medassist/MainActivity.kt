package com.example.medassist

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.medassist.ui.theme.MedAssistTheme
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.FileOutputStream

import androidx.lifecycle.lifecycleScope // For launching coroutines from Activity
import kotlinx.coroutines.launch // For launching coroutines
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : ComponentActivity() {
    // --- Member variable for MediaRecorder ---
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    internal fun copyUriToCache(context: android.content.Context, uri: Uri, fileNamePrefix: String = "selected_audio_"): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("FileSelection", "Failed to open input stream for URI: $uri")
                return null
            }

            // Create a temporary file in the cache directory
            val extension = getFileExtensionFromUri(context, uri) ?: "tmp"
            val tempFile = File.createTempFile(fileNamePrefix, ".$extension", context.cacheDir)

            val outputStream = FileOutputStream(tempFile)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("FileSelection", "File copied to cache: ${tempFile.absolutePath}")
            tempFile
        } catch (e: IOException) {
            Log.e("FileSelection", "Error copying URI to cache: ${e.message}", e)
            Toast.makeText(context, "Error processing selected file.", Toast.LENGTH_LONG).show()
            null
        }
    }

    // Helper to try and get a file extension from a URI (optional, but good for naming)
    private fun getFileExtensionFromUri(context: android.content.Context, uri: Uri): String? {
        return try {
            // A more robust way might involve MediaStore if it's a media URI,
            // or MimeTypeMap if it's a file URI with a clear extension in its path.
            // For GetContent, the URI might not directly expose a simple file name.
            val mimeType = context.contentResolver.getType(uri)
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        } catch (e: Exception) {
            Log.w("FileSelection", "Could not determine file extension from URI: $uri", e)
            null
        }
    }

    fun uploadAudioFileToServer(
        filePath: String,
        onUploadStart: () -> Unit,
        onUploadSuccess: (response: TranscriptionResponse) -> Unit,
        onUploadFailure: (errorMessage: String) -> Unit
    ) {
        lifecycleScope.launch { // Launch a coroutine in the activity's lifecycle scope
            onUploadStart() // Notify UI that upload is starting

            try {
                val file = File(filePath)
                if (!file.exists()) {
                    onUploadFailure("File not found: $filePath")
                    return@launch
                }

                // Create RequestBody for the file
                // Adjust MIME type if your recorded files are different (e.g., "audio/3gp" for .3gp)
                val requestFile = file.asRequestBody("audio/mp4".toMediaTypeOrNull())

                // Create MultipartBody.Part for the file
                // The name "audioFile" MUST MATCH the @Part name in your ApiService interface
                val body = MultipartBody.Part.createFormData("audioFile", file.name, requestFile)

                // Create RequestBody for other data (e.g., description)
                // The name "description" MUST MATCH the @Part name in your ApiService interface
                val descriptionString = "This is a medical appointment audio."
                val description = descriptionString.toRequestBody("text/plain".toMediaTypeOrNull())

                // Make the API call
                Log.d("FileUpload", "Attempting to upload: ${file.name}")
                val response = RetrofitClient.apiService.uploadAudioFile(body, description)

                if (response.isSuccessful) {
                    response.body()?.let {
                        Log.d("FileUpload", "Upload successful: ${it.transcript}")
                        onUploadSuccess(it)
                    } ?: run {
                        Log.e("FileUpload", "Upload successful but response body is null")
                        onUploadFailure("Upload successful but response body is null")
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    Log.e("FileUpload", "Upload failed: ${response.code()} - $errorBody")
                    onUploadFailure("Upload failed: ${response.code()} - $errorBody")
                }

            } catch (e: Exception) {
                Log.e("FileUpload", "Upload exception: ${e.message}", e)
                onUploadFailure("Upload exception: ${e.localizedMessage ?: "Unknown network error"}")
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MedAssistTheme {
                TranscriptionScreen(
                    startRecording = ::startRecording,
                    stopRecording = ::stopRecording,
                    // --- PASS THE NEW LAMBDA ---
                    onUploadFile = { filePath, onStart, onSuccess, onFailure ->
                        uploadAudioFileToServer(filePath, onStart, onSuccess, onFailure)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release MediaRecorder if it's active
        mediaRecorder?.release()
        mediaRecorder = null
    }

    // --- Function to create a file for the recording ---
    private fun createAudioFile(context: android.content.Context): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) // App-specific external storage
        if (storageDir != null && !storageDir.exists()) {
            storageDir.mkdirs() // Create directory if it doesn't exist
        }
        return File.createTempFile(
            "AUDIO_${timeStamp}_", /* prefix */
            ".mp4", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save the file path for later use
            Log.d("AudioRecording", "File created at: $absolutePath")
        }
    }

    // --- Function to start recording ---
    private fun startRecording(context: android.content.Context, updateUiOnStart: (filePath: String) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                audioFile = createAudioFile(context)

                mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC) // AAC is widely compatible
                    setAudioEncodingBitRate(128000) // 128 kbps
                    setAudioSamplingRate(44100)     // 44.1 kHz
                    setOutputFile(audioFile?.absolutePath)
                    prepare()
                    start()
                    updateUiOnStart(audioFile?.absolutePath ?: "Unknown path")
                    Log.d("AudioRecording", "Recording started. File: ${audioFile?.absolutePath}")
                }
            } catch (e: IOException) {
                Log.e("AudioRecording", "prepare() failed: ${e.message}")
                Toast.makeText(context, "Recording failed to start: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                audioFile = null // Reset if creation or prepare failed
            } catch (e: IllegalStateException) {
                Log.e("AudioRecording", "start() failed or other state issue: ${e.message}")
                Toast.makeText(context, "Recording failed (state issue): ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                audioFile = null
            }
        } else {
            // This should ideally be handled by requesting permission before calling startRecording
            Toast.makeText(context, "Record Audio permission not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Function to stop recording ---
    private fun stopRecording(context: android.content.Context, updateUiOnStop: (filePath: String?) -> Unit) {
        mediaRecorder?.apply {
            try {
                stop()
                reset() // or release() - reset() allows re-using the object after re-configuring
                release() // Release resources
                Toast.makeText(context, "Recording stopped. File saved at: ${audioFile?.name}", Toast.LENGTH_LONG).show()
                Log.d("AudioRecording", "Recording stopped. File: ${audioFile?.absolutePath}")
                updateUiOnStop(audioFile?.absolutePath)
            } catch (e: IllegalStateException) {
                Log.e("AudioRecording", "stop() failed: ${e.message}")
                Toast.makeText(context, "Failed to stop recording properly.", Toast.LENGTH_SHORT).show()
                // Clean up the file if stop failed and it's unusable
                audioFile?.delete()
                updateUiOnStop(null) // Indicate failure
            }
        }
        mediaRecorder = null // Clear the instance
        // audioFile remains, as it's the successfully saved file (or null if error)
    }
}


@Composable
fun TranscriptionScreen(
    modifier: Modifier = Modifier,
    startRecording: (context: android.content.Context, updateUiOnStart: (filePath: String) -> Unit) -> Unit,
    stopRecording: (context: android.content.Context, updateUiOnStop: (filePath: String?) -> Unit) -> Unit,
    onUploadFile: (
        filePath: String,
        onStart: () -> Unit,
        onSuccess: (TranscriptionResponse) -> Unit,
        onFailure: (String) -> Unit
    ) -> Unit
    // --- Add a reference to the MainActivity function ---
    // (Note: This is a simplified way. For cleaner architecture, you might use a ViewModel
    // or pass lambdas that encapsulate MainActivity's methods. For now, this illustrates the call.)
    // However, since copyUriToCache is in MainActivity, we can't call it directly like this from a Composable
    // that doesn't have a MainActivity instance.
    // Let's adjust how we trigger this. We'll update transcriptionText and let MainActivity handle the copy
    // if we were to add more complex state later. For now, let's just show how to call it if MainActivity instance was available.
    // A better approach is to pass a lambda from MainActivity.
) {
    val activity = LocalContext.current as MainActivity // Get MainActivity instance (use with caution, see note)
    val context = LocalContext.current
    var transcriptionText by remember { mutableStateOf("Your transcription will appear here...") }
    var isRecording by remember { mutableStateOf(false) }
    var currentAudioFilePath by remember { mutableStateOf<String?>(null) } // This will now store the path to the copied cache file
    var isUploading by remember { mutableStateOf(false) }

    // Launcher for RECORD_AUDIO permission
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(context, "Record Audio permission granted", Toast.LENGTH_SHORT).show()
                // Now that permission is granted, try to start recording
                startRecording(context) { filePath ->
                    transcriptionText = "Recording started... saving to $filePath"
                    currentAudioFilePath = filePath
                    isRecording = true
                }
            } else {
                Toast.makeText(context, "Record Audio permission denied", Toast.LENGTH_SHORT).show()
                transcriptionText = "Record Audio permission denied. Cannot record."
            }
        }
    )

    // Launcher for selecting an audio file ---
    val selectAudioFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                Toast.makeText(context, "File selected: $uri. Processing...", Toast.LENGTH_SHORT).show()
                transcriptionText = "Processing selected file..."
                // --- MODIFIED: Call the copy function ---
                val copiedFile = activity.copyUriToCache(context, uri) // Calling MainActivity's method
                if (copiedFile != null) {
                    transcriptionText = "File ready: ${copiedFile.name}"
                    currentAudioFilePath = copiedFile.absolutePath // Store path of the copied file
                    // TODO: Now you have 'copiedFile' (a File object) ready for use (e.g., upload)
                    Log.d("FileSelection", "Processed file available at: ${copiedFile.absolutePath}")
                } else {
                    transcriptionText = "Failed to process selected file."
                    currentAudioFilePath = null
                }
            } else {
                Toast.makeText(context, "No file selected", Toast.LENGTH_SHORT).show()
                currentAudioFilePath = null
            }
        }
    )

    // Launcher for storage permission
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(context, "Storage permission granted. Opening file picker...", Toast.LENGTH_SHORT).show()
                transcriptionText = "Storage permission granted. Opening file picker..."
                selectAudioFileLauncher.launch("audio/*") // --- LAUNCH PICKER HERE AFTER GRANT ---
            } else {
                Toast.makeText(context, "Storage permission denied", Toast.LENGTH_SHORT).show()
                transcriptionText = "Storage permission denied. Cannot select file."
            }
        }
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Medical Transcription App",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 32.dp)
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (isRecording) {
                            stopRecording(context) { filePath ->
                                transcriptionText = if (filePath != null) {
                                    "Recording stopped. File saved: ${File(filePath).name}"
                                } else {
                                    "Recording failed or was stopped with an issue."
                                }
                                isRecording = false
                            }
                        } else {
                            when (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            )) {
                                PackageManager.PERMISSION_GRANTED -> {
                                    startRecording(context) { filePath ->
                                        transcriptionText = "Recording started... saving to ${File(filePath).name}"
                                        currentAudioFilePath = filePath
                                        isRecording = true
                                    }
                                }
                                else -> {
                                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(if (isRecording) "Stop Recording" else "Record Audio")
                }
                Button(
                    onClick = {
                        val storagePermission =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Manifest.permission.READ_MEDIA_AUDIO
                            } else {
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            }
                        when (ContextCompat.checkSelfPermission(context, storagePermission)) {
                            PackageManager.PERMISSION_GRANTED -> {
                                // Permission is already granted, launch the file picker
                                selectAudioFileLauncher.launch("audio/*") // --- MODIFIED: Launch file picker ---
                            }
                            else -> {
                                // Permission is not granted, request it.
                                // The onResult of storagePermissionLauncher will then launch the file picker if granted.
                                storagePermissionLauncher.launch(storagePermission)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text("Select Audio File")
                }
                // Transcribe Button
                Button(
                    onClick = {
                        currentAudioFilePath?.let { filePath ->
                            if (!isUploading) { // Prevent multiple clicks while uploading
                                onUploadFile(
                                    filePath,
                                    { // onStart
                                        isUploading = true
                                        transcriptionText = "Uploading and transcribing..."
                                    },
                                    { response -> // onSuccess
                                        isUploading = false
                                        transcriptionText = "Transcript: ${response.transcript ?: "No transcript received."}"
                                        // You might want to clear currentAudioFilePath or handle it differently after successful upload
                                    },
                                    { errorMessage -> // onFailure
                                        isUploading = false
                                        transcriptionText = "Error: $errorMessage"
                                    }
                                )
                            }
                        }
                    },
                    enabled = currentAudioFilePath != null && !isRecording && !isUploading, // Enable only if a file is ready and not recording/uploading
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(if (isUploading) "Transcribing..." else "Transcribe Current File")
                }
            }

            Text(
                text = transcriptionText,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 32.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            currentAudioFilePath?.let {
                Text("Last audio file: $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// Preview function needs to be adjusted if TranscriptionScreen parameters change,
// but since we are calling MainActivity methods via context cast, it might work as is for preview,
// though the copy function won't actually execute meaningfully in preview.
@Preview(showBackground = true)
@Composable
fun TranscriptionScreenPreview() {
    MedAssistTheme {
        TranscriptionScreen(
            startRecording = { _, _ -> },
            stopRecording = { _, _ -> },
            onUploadFile = { _, _, _, _ -> } // Provide dummy for preview
        )
    }
}