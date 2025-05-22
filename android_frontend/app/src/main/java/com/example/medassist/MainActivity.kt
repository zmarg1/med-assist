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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.medassist.ui.theme.MedAssistTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn // Ensure these are here
import androidx.compose.foundation.lazy.items     // Ensure these are here
import androidx.lifecycle.lifecycleScope

enum class AppScreen {
    MAIN_TRANSCRIPTION,
    RECORDINGS_LIST,
    RECORDING_DETAIL
}

// Helper function to format timestamp (can be placed at file level or in a utils file)
fun formatMillisToDateTime(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(millis))
}

class MainActivity : ComponentActivity() {
    // --- Member variable for MediaRecorder ---
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    // --- Database related (We will integrate Room fully in the next major step) ---
    // For now, getRecordedAudioFiles still returns List<File>
    // Later, this will interact with RecordingDao
    internal fun getRecordedAudioFiles(context: android.content.Context): List<File> {
        val storageDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        if (storageDir != null && storageDir.exists() && storageDir.isDirectory) {
            return storageDir.listFiles { file ->
                file.isFile && file.name.endsWith(".mp4", ignoreCase = true)
            }?.sortedDescending() ?: emptyList()
        }
        return emptyList()
    }
    // --- End Database placeholder ---

    /**
     * Copies the content of a given Uri to a temporary file in the app's cache directory.
     * This is useful when you get a content Uri from a file picker or share intent
     * and need a direct File object to work with (e.g., for uploading).
     */
    internal fun copyUriToCache(context: android.content.Context, uri: Uri, fileNamePrefix: String = "selected_audio_"): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("FileUtil", "Failed to open input stream for URI: $uri")
                Toast.makeText(context, "Failed to open selected file.", Toast.LENGTH_LONG).show()
                return null
            }

            // Try to get a file extension
            val extension = getFileExtensionFromUri(context, uri) ?: "tmp" // Default to .tmp if no extension found

            // Create a temporary file in the cache directory
            // Using a timestamp to help ensure uniqueness if multiple files are processed quickly,
            // though createTempFile usually handles uniqueness well with its random component.
            val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val tempFile = File.createTempFile("${fileNamePrefix}${timeStamp}_", ".$extension", context.cacheDir)

            val outputStream = FileOutputStream(tempFile)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("FileUtil", "File copied to cache: ${tempFile.absolutePath}")
            tempFile
        } catch (e: IOException) {
            Log.e("FileUtil", "Error copying URI to cache: ${e.message}", e)
            Toast.makeText(context, "Error processing selected file.", Toast.LENGTH_LONG).show()
            null
        } catch (e: SecurityException) {
            Log.e("FileUtil", "Security error copying URI to cache (permission issue?): ${e.message}", e)
            Toast.makeText(context, "Permission denied for selected file.", Toast.LENGTH_LONG).show()
            null
        } catch (e: Exception) { // Catch any other unexpected errors
            Log.e("FileUtil", "Unexpected error copying URI to cache: ${e.message}", e)
            Toast.makeText(context, "An unexpected error occurred while processing the file.", Toast.LENGTH_LONG).show()
            null
        }
    }

    /**
     * Helper function to try and get a file extension from a content URI.
     * Relies on the ContentResolver being able to determine the MIME type.
     */
    private fun getFileExtensionFromUri(context: android.content.Context, uri: Uri): String? {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType != null) {
                android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            } else {
                // Fallback if MIME type is null: try to get from path (less reliable for content URIs)
                uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w("FileUtil", "Could not determine file extension from URI: $uri", e)
            null
        }
    }

    // Modified uploadAudioFileToServer to update MainActivity's shared state
    fun uploadAudioFileToServer(
        filePath: String,
        userGivenName: String, // Added to associate with the active recording
        updateActiveTranscriptionState: (newState: String) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) { // Perform network and file ops on IO thread
            // Initial status update already done by caller before navigating
            // updateActiveTranscriptionState("Transcribing: $userGivenName...")

            try {
                val fileToUpload = File(filePath)
                if (!fileToUpload.exists()) {
                    withContext(Dispatchers.Main) { updateActiveTranscriptionState("Error: File not found - ${fileToUpload.name}") }
                    return@launch
                }

                val requestFile = fileToUpload.asRequestBody("audio/mp4".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("audioFile", fileToUpload.name, requestFile)
                val description = "Audio for '$userGivenName'".toRequestBody("text/plain".toMediaTypeOrNull())

                Log.d("FileUpload", "Attempting AssemblyAI upload for: ${fileToUpload.name} (User: $userGivenName)")
                val response = RetrofitClient.apiService.uploadAudioFile(body, description)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        response.body()?.let {
                            Log.d("FileUpload", "AssemblyAI successful for $userGivenName: ${it.transcript?.take(50)}...")
                            updateActiveTranscriptionState(it.transcript ?: "No transcript content received.")
                            // TODO: Save transcript to DB, associated with userGivenName/filePath
                        } ?: run {
                            Log.e("FileUpload", "AssemblyAI successful for $userGivenName but response body is null")
                            updateActiveTranscriptionState("Error: Empty response from server. (Retry?)")
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Unknown server error"
                        Log.e("FileUpload", "AssemblyAI failed for $userGivenName: ${response.code()} - $errorBody")
                        updateActiveTranscriptionState("Error: ${response.code()} - Server processing failed. (Retry?)")
                    }
                }
            } catch (e: Exception) {
                Log.e("FileUpload", "Upload/AssemblyAI exception for $userGivenName ($filePath): ${e.message}", e)
                withContext(Dispatchers.Main) {
                    updateActiveTranscriptionState("Exception: ${e.localizedMessage ?: "Unknown error"} (Retry?)")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MedAssistTheme {
                var currentScreen by remember { mutableStateOf(AppScreen.MAIN_TRANSCRIPTION) }

                var activeRecordingFilePath by remember { mutableStateOf<String?>(null) }
                var activeUserGivenName by remember { mutableStateOf<String?>(null) }
                var activeTranscriptionStateDisplay by remember { mutableStateOf<String?>("N/A") }

                var recordedFilesListState by remember { mutableStateOf(emptyList<File>()) }

                when (currentScreen) {
                    AppScreen.MAIN_TRANSCRIPTION -> {
                        TranscriptionScreen(
                            startRecording = ::startRecording, // Pass MainActivity's method
                            stopRecording = ::stopRecording,   // Pass MainActivity's method
                            onRecordingNamed = { filePath, userGivenName ->
                                // This is called after user names the recording
                                activeRecordingFilePath = filePath
                                activeUserGivenName = userGivenName
                                activeTranscriptionStateDisplay = "Transcription in progress for '$userGivenName'..."
                                currentScreen = AppScreen.RECORDING_DETAIL // Navigate to detail screen

                                // Trigger the transcription
                                uploadAudioFileToServer(
                                    filePath = filePath,
                                    userGivenName = userGivenName,
                                    updateActiveTranscriptionState = { newState ->
                                        // Ensure we only update if this is still the active file
                                        if (filePath == activeRecordingFilePath) {
                                            activeTranscriptionStateDisplay = newState
                                        }
                                    }
                                )
                            },
                            onViewRecordings = {
                                recordedFilesListState = getRecordedAudioFiles(applicationContext) // Refresh list using the correct variable name
                                currentScreen = AppScreen.RECORDINGS_LIST
                            }
                        )
                    }
                    AppScreen.RECORDINGS_LIST -> {
                        // recordedFilesListState = getRecordedAudioFiles(applicationContext) // You might want to refresh this here too
                        RecordingsListScreen(
                            recordings = recordedFilesListState,
                            onRecordingSelected = { selectedFile -> // <<<< CHANGED to onRecordingSelected
                                activeRecordingFilePath = selectedFile.absolutePath
                                activeUserGivenName = selectedFile.name
                                activeTranscriptionStateDisplay = "Transcript for ${selectedFile.name} (TBD from storage)"
                                currentScreen = AppScreen.RECORDING_DETAIL
                            },
                            onPlayRecording = { selectedFile ->
                                Toast.makeText(this, "Play ${selectedFile.name} TBD", Toast.LENGTH_SHORT).show()
                            },
                            onDismiss = { currentScreen = AppScreen.MAIN_TRANSCRIPTION }
                        )
                    }
                    AppScreen.RECORDING_DETAIL -> {
                        val currentFileToDetail = activeRecordingFilePath
                        val currentNameToDetail = activeUserGivenName
                        if (currentFileToDetail != null && currentNameToDetail != null) {
                            RecordingDetailScreen(
                                userGivenName = currentNameToDetail,
                                filePath = currentFileToDetail,
                                currentTranscriptionResult = activeTranscriptionStateDisplay,
                                onPlayRecording = {
                                    Toast.makeText(this, "Play ${currentNameToDetail} TBD", Toast.LENGTH_SHORT).show()
                                },
                                onRetryTranscription = {
                                    activeTranscriptionStateDisplay = "Retrying transcription for '$currentNameToDetail'..."
                                    uploadAudioFileToServer(
                                        currentFileToDetail,
                                        currentNameToDetail,
                                        updateActiveTranscriptionState = { newState ->
                                            if (currentFileToDetail == activeRecordingFilePath) {
                                                activeTranscriptionStateDisplay = newState
                                            }
                                        }
                                    )
                                },
                                onBack = { currentScreen = AppScreen.RECORDINGS_LIST }
                            )
                        } else { // Fallback if somehow navigated here without active file
                            Text("Error: No recording selected for details. Navigating back.")
                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(2000) // Brief pause
                                currentScreen = AppScreen.RECORDINGS_LIST
                            }
                        }
                    }
                }
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


// Ensure these imports are present at the top of your MainActivity.kt
// import androidx.compose.material3.AlertDialog // Already there if RecordingNameDialog is in this file
// import androidx.compose.material3.TextField // Already there if RecordingNameDialog is in this file

@Composable
fun TranscriptionScreen(
    modifier: Modifier = Modifier,
    startRecording: (context: android.content.Context, updateUiOnStart: (filePath: String) -> Unit) -> Unit,
    stopRecording: (context: android.content.Context, updateUiOnStop: (filePath: String?) -> Unit) -> Unit,
    onViewRecordings: () -> Unit,
    onRecordingNamed: (filePath: String, userGivenName: String) -> Unit // NEW callback
) {
    val context = LocalContext.current
    var uiMessageText by remember { mutableStateOf("Tap 'Record Audio' to start.") }
    var isRecording by remember { mutableStateOf(false) }
    // currentAudioFilePath is now less relevant on this screen directly for transcription
    // tempFilePathForNaming will hold the path of the just-recorded file.

    var showNameDialog by remember { mutableStateOf(false) }
    var tempFilePathForNaming by remember { mutableStateOf<String?>(null) }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = true
            uiMessageText = "Starting recorder..."
            startRecording(context) { filePath ->
                // This filePath is the actual path of the file being recorded
                tempFilePathForNaming = filePath // Store it for when recording stops
                uiMessageText = "Recording: ${File(filePath).name}"
            }
        } else { uiMessageText = "Microphone permission denied." }
    }

    if (showNameDialog && tempFilePathForNaming != null) {
        RecordingNameDialog(
            onDismissRequest = {
                showNameDialog = false
                uiMessageText = "Naming cancelled for ${File(tempFilePathForNaming!!).name}. File saved."
                tempFilePathForNaming = null
            },
            onConfirm = { userGivenName ->
                showNameDialog = false
                val originalPath = tempFilePathForNaming
                if (originalPath != null) {
                    // No longer directly calls onUploadFile here.
                    // Calls back to MainActivity to handle navigation and transcription triggering.
                    onRecordingNamed(originalPath, userGivenName)
                    // uiMessageText can be updated by MainActivity or DetailScreen later
                }
                tempFilePathForNaming = null
            }
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Health Buddy", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 32.dp))

        Text(uiMessageText, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                if (isRecording) {
                    // When "Stop Recording" is clicked, tempFilePathForNaming should already hold the path
                    stopRecording(context) { stoppedFilePath -> // stoppedFilePath is the confirmed saved path
                        isRecording = false
                        if (stoppedFilePath != null) {
                            // tempFilePathForNaming should be the same as stoppedFilePath if startRecording set it.
                            // If startRecording's callback for path wasn't used to set tempFilePathForNaming,
                            // then use stoppedFilePath here. For safety, ensure it's from stopRecording.
                            tempFilePathForNaming = stoppedFilePath
                            showNameDialog = true
                            uiMessageText = "Stopped: ${File(stoppedFilePath).name}. Please name it."
                        } else { uiMessageText = "Recording failed or was cancelled." }
                    }
                } else { // Start Recording
                    uiMessageText = "Checking permission..."
                    when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
                        PackageManager.PERMISSION_GRANTED -> {
                            isRecording = true
                            uiMessageText = "Starting recorder..."
                            startRecording(context) { filePath -> // filePath from startRecording
                                tempFilePathForNaming = filePath // Store it for when stopping
                                uiMessageText = "Recording: ${File(filePath).name}"
                            }
                        }
                        else -> recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            enabled = !showNameDialog,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) { Text(if (isRecording) "Stop Recording" else "Record Audio") }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onViewRecordings,
            enabled = !isRecording && !showNameDialog,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) { Text("My Recordings") }
    }
}

// Update Preview for TranscriptionScreen (remove initialFileToShowDetails, onClearFileDetails)
@Preview(showBackground = true)
@Composable
fun TranscriptionScreenPreview() {
    MedAssistTheme {
        TranscriptionScreen(
            startRecording = { _, _ -> },
            stopRecording = { _, _ -> },
            onViewRecordings = { },
            // recordingDao = dummyDao, // This will be an issue for preview without a real/mock DAO
            onRecordingNamed = { _, _ -> } // Adjusted for onRecordingNamedAndSaved if we change it.
            // The current user code has onRecordingNamedAndSaved in MainActivity, not passed to TranscriptionScreen.
            // My new TranscriptionScreen takes onRecordingNamed.
        )
    }
}

// --- RecordingsListScreen (displays List<File> for now) ---
// Needs to be updated to take List<RecordingItem> once DB is integrated for user-given names
@Composable
fun RecordingsListScreen(
    recordings: List<File>, // Will change to List<RecordingItem> later
    onRecordingSelected: (File) -> Unit, // Will change to (RecordingItem) -> Unit
    onPlayRecording: (File) -> Unit,     // Will change to (RecordingItem) -> Unit
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("My Recordings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
            if (recordings.isEmpty()) { Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("No recordings found.") } }
            else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(recordings) { file -> // Iterates over File objects for now
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) // Shows actual filename
                                    Text("Recorded: ${formatMillisToDateTime(file.lastModified())}", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { onPlayRecording(file) }) { Icon(Icons.Filled.PlayArrow, "Play") }
                                IconButton(onClick = { onRecordingSelected(file) }) { Icon(Icons.Filled.Article, "View Details") }
                            }
                        }
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back to Main Screen") }
        }
    }
}


@Composable
fun RecordingNameDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (name: String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Name Your Recording") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Recording Name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onConfirm(text)
                    } else {
                        Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun RecordingDetailScreen(
    userGivenName: String,
    filePath: String, // To identify this recording
    currentTranscriptionResult: String?, // The transcript or status message
    onPlayRecording: () -> Unit,
    onRetryTranscription: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recordingDate = remember(filePath) { // Calculate date once based on filePath if it's a File object
        try {
            formatMillisToDateTime(File(filePath).lastModified())
        } catch (e: Exception) { "Unknown date" }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Recording Details", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
            Text("Name: $userGivenName", style = MaterialTheme.typography.titleLarge)
            Text("Recorded: $recordingDate", style = MaterialTheme.typography.bodyMedium)
            // Text("File: ${File(filePath).name}", style = MaterialTheme.typography.caption) // Optional: for debug
            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onPlayRecording, modifier = Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Play Audio (TBD)")
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text("Status & Transcript:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

            val transcriptOrStatus = currentTranscriptionResult ?: "Loading status..."

            // Check if the result indicates an error to show retry button
            val showRetryButton = transcriptOrStatus.startsWith("Error:") || transcriptOrStatus.startsWith("Exception:")

            if (showRetryButton) {
                Text(transcriptOrStatus, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                Button(onClick = onRetryTranscription) {
                    Text("Retry Transcription")
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = if(showRetryButton) 8.dp else 0.dp) ) {
                Text(transcriptOrStatus, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Back to Recordings List")
            }
        }
    }
}

