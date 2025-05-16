package com.example.medassist

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder // Added
import android.os.Build
import android.os.Bundle
import android.os.Environment // Added
import android.util.Log // For logging
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
import java.io.File // Added
import java.io.IOException // Added
import java.text.SimpleDateFormat // Added
import java.util.Date // Added
import java.util.Locale // Added

class MainActivity : ComponentActivity() {
    // --- Member variable for MediaRecorder ---
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MedAssistTheme {
                TranscriptionScreen(
                    startRecording = ::startRecording,
                    stopRecording = ::stopRecording
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
    stopRecording: (context: android.content.Context, updateUiOnStop: (filePath: String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var transcriptionText by remember { mutableStateOf("Your transcription will appear here...") }
    var isRecording by remember { mutableStateOf(false) } // --- NEW: State for recording status ---
    var currentAudioFilePath by remember { mutableStateOf<String?>(null) } // To store current file path

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

    // Launcher for storage permission (remains the same)
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(context, "Storage permission granted", Toast.LENGTH_SHORT).show()
                transcriptionText = "Storage permission granted. Ready to select file!"
                // TODO: Implement audio file selection logic
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
                            // --- Stop recording ---
                            stopRecording(context) { filePath ->
                                transcriptionText = if (filePath != null) {
                                    "Recording stopped. File saved: ${File(filePath).name}"
                                } else {
                                    "Recording failed or was stopped with an issue."
                                }
                                isRecording = false
                            }
                        } else {
                            // --- Start recording ---
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
                    Text(if (isRecording) "Stop Recording" else "Record Audio") // --- NEW: Dynamic button text ---
                }
                Button(
                    onClick = { // Storage permission logic from previous step
                        val storagePermission =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Manifest.permission.READ_MEDIA_AUDIO
                            } else {
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            }
                        when (ContextCompat.checkSelfPermission(context, storagePermission)) {
                            PackageManager.PERMISSION_GRANTED -> {
                                Toast.makeText(context, "Storage permission already granted", Toast.LENGTH_SHORT).show()
                                transcriptionText = "Storage permission already granted. Ready to select file!"
                                // TODO: Implement audio file selection logic
                            }
                            else -> {
                                storagePermissionLauncher.launch(storagePermission)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text("Select Audio File")
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

@Preview(showBackground = true)
@Composable
fun TranscriptionScreenPreview() {
    MedAssistTheme {
        // Provide dummy functions for the preview
        TranscriptionScreen(startRecording = { _, _ -> }, stopRecording = { _, _ -> })
    }
}