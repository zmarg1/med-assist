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
import androidx.compose.material.icons.automirrored.filled.Article
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
import androidx.compose.material.icons.filled.Delete
import android.media.MediaPlayer
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.clickable
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.text.style.TextAlign

import android.content.Context
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import android.content.Intent
import android.content.ActivityNotFoundException



enum class AppScreen {
    MAIN_TRANSCRIPTION,
    RECORDINGS_LIST,
    RECORDING_DETAIL
}

fun createTranscriptPdf(context: Context, fileNamePrefix: String, transcriptText: String): File? {
    return try {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size in points
        val page = document.startPage(pageInfo)

        val canvas = page.canvas
        val paint = android.graphics.Paint().apply {
            textSize = 12f
        }

        val lines = transcriptText.chunked(90) // basic word wrap
        var y = 30f
        for (line in lines) {
            canvas.drawText(line, 30f, y, paint)
            y += 20f
        }

        document.finishPage(page)

        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val file = File(outputDir, "${fileNamePrefix}_transcript.pdf")
        FileOutputStream(file).use {
            document.writeTo(it)
        }
        document.close()
        file
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun sharePdf(context: Context, pdfFile: File) {
    try {
        val pdfUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            pdfFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, pdfUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share transcript PDF via"))
    } catch (e: Exception) {
        Toast.makeText(context, "Unable to share PDF: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        e.printStackTrace()
    }
}


fun viewPdf(context: Context, pdfFile: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        pdfFile
    )

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // Let user choose app — sometimes helps trigger Google Drive
    val chooser = Intent.createChooser(intent, "Open PDF with")

    // Grant URI permission to all apps that can handle this intent
    val resInfoList = context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
    for (resInfo in resInfoList) {
        val packageName = resInfo.activityInfo.packageName
        context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open PDFs. Try installing Adobe Reader or another PDF viewer.", Toast.LENGTH_LONG).show()
    }
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
    private val TAG = "MainActivity"

    // Initialize Room Database and DAO (lazily)
    private val database by lazy { AppDatabase.getDatabase(applicationContext) }
    private val recordingDao by lazy { database.recordingDao() }

    // --- Member variable for MediaPlayer ---
    private var MPlayer: MediaPlayer? = null

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
    private fun uploadAudioFileToServer( // Made it private as per previous warning
        filePathToUpload: String,
        userGivenNameToUpdate: String,
        recordingId: Int,
        idOfRecordingInDetailView: Int?,
        setActiveTranscriptionDisplay: (String) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            var resultStatusForUI: String
            var finalDbStatus: String

            try {
                recordingDao.updateStatusById(recordingId, "Transcribing...")
                withContext(Dispatchers.Main) {
                    if (recordingId == idOfRecordingInDetailView) {
                        setActiveTranscriptionDisplay("Transcribing: $userGivenNameToUpdate...")
                    }
                }

                val file = File(filePathToUpload)
                if (!file.exists()) {
                    finalDbStatus = "Failed: File Missing"
                    resultStatusForUI = "Error: File not found - ${file.name}"
                    recordingDao.updateStatusById(recordingId, finalDbStatus)
                } else {
                    val requestFile = file.asRequestBody("audio/mp4".toMediaTypeOrNull())
                    val body = MultipartBody.Part.createFormData("audioFile", file.name, requestFile)
                    val description = "Audio for '$userGivenNameToUpdate' (ID: $recordingId)".toRequestBody("text/plain".toMediaTypeOrNull())
                    Log.d(TAG, "Attempting AssemblyAI upload for ID $recordingId: ${file.name}")
                    val response = RetrofitClient.apiService.uploadAudioFile(body, description)

                    if (response.isSuccessful && response.body() != null) {
                        val receivedTranscript = response.body()!!.transcript // This is String?
                        resultStatusForUI = receivedTranscript ?: "No transcript content received."
                        finalDbStatus = "Completed"
                        Log.d(TAG, "AssemblyAI successful for ID $recordingId.")
                        // Pass receivedTranscript (which can be null) directly to DAO
                        recordingDao.updateTranscriptAndStatusById(recordingId, receivedTranscript, finalDbStatus)
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Unknown server error"
                        resultStatusForUI = "Error: ${response.code()} - Server processing failed. (Retry?)"
                        finalDbStatus = "Failed: Server Error ${response.code()}"
                        // Pass errorBody as the transcript to the DAO
                        recordingDao.updateTranscriptAndStatusById(recordingId, errorBody, finalDbStatus)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception for ID $recordingId ($filePathToUpload): ${e.message}", e)
                resultStatusForUI = "Exception: ${e.localizedMessage ?: "Unknown error"} (Retry?)"
                finalDbStatus = "Failed: Exception - ${e.javaClass.simpleName}"
                // Only update status, transcript in DB will remain as it was (likely null from insert)
                recordingDao.updateTranscriptAndStatusById(recordingId, resultStatusForUI, finalDbStatus)
            }

            withContext(Dispatchers.Main) {
                if (recordingId == idOfRecordingInDetailView) {
                    setActiveTranscriptionDisplay(resultStatusForUI)
                }
            }
        }
    }

    private suspend fun performDeleteRecording(recordingItem: RecordingItem): Boolean {
        var fileDeleted = false
        var dbRecordDeleted = false

        // 1. Delete the audio file from storage
        try {
            val file = File(recordingItem.filePath)
            if (file.exists()) {
                if (file.delete()) {
                    Log.d(TAG, "Audio file deleted successfully: ${recordingItem.filePath}")
                    fileDeleted = true
                } else {
                    Log.e(TAG, "Failed to delete audio file: ${recordingItem.filePath}")
                }
            } else {
                Log.w(TAG, "Audio file not found, considering file part of delete as done: ${recordingItem.filePath}")
                fileDeleted = true // File doesn't exist, so it's effectively "deleted"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting audio file ${recordingItem.filePath}: ${e.message}", e)
        }

        // 2. Delete the record from the database
        // We might want to delete the DB record even if file deletion failed,
        // or make it conditional. For now, let's try to delete it.
        try {
            recordingDao.deleteRecordingById(recordingItem.id)
            Log.d(TAG, "Recording deleted from database with ID: ${recordingItem.id}")
            dbRecordDeleted = true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting recording from database ID ${recordingItem.id}: ${e.message}", e)
        }

        // For simplicity, we'll say success if DB record is gone,
        // as the list is driven by DB. File deletion failure is logged.
        return fileDeleted && dbRecordDeleted
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // Enable edge-to-edge
        setContent {
            MedAssistTheme {
                var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
                var recordingToDelete by remember { mutableStateOf<RecordingItem?>(null) }
                var currentScreen by remember { mutableStateOf(AppScreen.MAIN_TRANSCRIPTION) }

                // States for the recording being actively detailed or just transcribed by MainActivity
                var activeRecordingFilePath by remember { mutableStateOf<String?>(null) }
                var activeUserGivenName by remember { mutableStateOf<String?>(null) }
                var activeTranscriptionStateDisplay by remember { mutableStateOf<String?>("N/A") } // Holds transcript or status
                var activeRecordingId by remember { mutableStateOf<Int?>(null) } // Holds DB ID of the active recording

                // --- Compose states for playback UI, managed by MainActivity ---
                var currentlyPlayingFilePath by remember { mutableStateOf<String?>(null) }
                var isAudioPlaying by remember { mutableStateOf(false) }

                // Lambdas to update the playback states from MainActivity methods
                val updateCurrentlyPlayingPathLambda = { path: String? -> currentlyPlayingFilePath = path }
                val updateIsAudioPlayingLambda = { playing: Boolean -> isAudioPlaying = playing }

                // Consolidated play/stop toggle function to pass down
                val togglePlayStopForFile = { filePathToToggle: String ->
                    if (isAudioPlaying && currentlyPlayingFilePath == filePathToToggle) {
                        stopAudioPlayback(updateCurrentlyPlayingPathLambda, updateIsAudioPlayingLambda)
                    } else {
                        playAudioFile(filePathToToggle, updateCurrentlyPlayingPathLambda, updateIsAudioPlayingLambda)
                    }
                }

                // This lambda handles new recordings after they are named
                val startAutoTranscriptionAndNavigate = { filePath: String, userGivenName: String ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val finalIdToProcess: Int // Declare as non-nullable Int

                        val existingRecording = recordingDao.getRecordingByFilePath(filePath)

                        if (existingRecording != null) {
                            Log.w(TAG, "Recording with filePath '$filePath' already exists with ID ${existingRecording.id}. Checking for updates.")
                            var needsDbUpdate = false
                            if (existingRecording.userGivenName != userGivenName) {
                                existingRecording.userGivenName = userGivenName
                                needsDbUpdate = true
                            }
                            if (existingRecording.transcriptionStatus != "Completed" &&
                                existingRecording.transcriptionStatus != "Transcribing..." &&
                                existingRecording.transcriptionStatus != "Pending Transcription") {
                                existingRecording.transcriptionStatus = "Pending Transcription"
                                existingRecording.transcript = null
                                needsDbUpdate = true
                            }
                            if (needsDbUpdate) {
                                recordingDao.updateRecording(existingRecording)
                                Log.i(TAG, "Updated existing recording ID ${existingRecording.id}.")
                            }
                            finalIdToProcess = existingRecording.id
                        } else {
                            // New recording
                            val newRecording = RecordingItem(
                                userGivenName = userGivenName,
                                filePath = filePath,
                                recordingDate = System.currentTimeMillis(),
                                transcript = null,
                                transcriptionStatus = "Pending Transcription"
                            )
                            val newRowId = recordingDao.insertRecording(newRecording)
                            if (newRowId == -1L) {
                                Log.e("DBInsert", "Failed to insert new recording for filePath: $filePath")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(applicationContext, "Error: Could not save recording metadata.", Toast.LENGTH_LONG).show()
                                }
                                return@launch // Exit this coroutine
                            }
                            finalIdToProcess = newRowId.toInt()
                            Log.d("DBInsert", "New recording saved to DB with ID: $finalIdToProcess, Name: $userGivenName")
                        }

                        // At this point, finalIdToProcess is guaranteed to be initialized
                        withContext(Dispatchers.Main) {
                            activeRecordingFilePath = filePath
                            activeUserGivenName = userGivenName
                            activeRecordingId = finalIdToProcess
                            activeTranscriptionStateDisplay = "Transcription in progress for '$userGivenName'..."
                            currentScreen = AppScreen.RECORDING_DETAIL

                            uploadAudioFileToServer(
                                filePathToUpload = filePath,
                                userGivenNameToUpdate = userGivenName,
                                recordingId = finalIdToProcess,
                                idOfRecordingInDetailView = finalIdToProcess,
                                setActiveTranscriptionDisplay = { newState ->
                                    if (filePath == activeRecordingFilePath && finalIdToProcess == activeRecordingId) {
                                        activeTranscriptionStateDisplay = newState
                                    }
                                }
                            )
                        }
                    }
                }

                if (showDeleteConfirmationDialog) { // No need to check recordingToDelete here, dialog won't show if it's null when set
                    val itemToActuallyDelete = recordingToDelete // Capture the value for use in lambdas
                    if (itemToActuallyDelete != null) { // Ensure it's not null before creating dialog
                        ConfirmDeleteDialog(
                            itemName = itemToActuallyDelete.userGivenName,
                            onConfirm = {
                                // Dialog is dismissed immediately by its own onDismiss after this onConfirm returns
                                // Or we can control dismissal from here
                                showDeleteConfirmationDialog = false // Dismiss dialog
                                val capturedItemForDelete = recordingToDelete // Re-capture for the coroutine
                                recordingToDelete = null // Clear the state immediately

                                if (capturedItemForDelete != null) {
                                    lifecycleScope.launch { // Use default dispatcher (Main for UI updates after)
                                        val success = withContext(Dispatchers.IO) { // Perform file & DB ops on IO
                                            performDeleteRecording(capturedItemForDelete)
                                        }
                                        // Back on Main thread for UI updates
                                        if (success) {
                                            Toast.makeText(applicationContext, "'${capturedItemForDelete.userGivenName}' deleted.", Toast.LENGTH_SHORT).show()
                                            // If we were on the detail screen for the item just deleted, navigate back
                                            if (currentScreen == AppScreen.RECORDING_DETAIL && activeRecordingId == capturedItemForDelete.id) {
                                                currentScreen = AppScreen.RECORDINGS_LIST
                                                activeRecordingId = null // Clear the now-deleted active ID
                                                activeUserGivenName = null
                                                activeRecordingFilePath = null
                                                activeTranscriptionStateDisplay = "N/A" // Reset display state
                                            }
                                            // The RecordingsListScreen will auto-update due to Flow from DB
                                        } else {
                                            Toast.makeText(applicationContext, "Failed to delete '${capturedItemForDelete.userGivenName}'. Check logs.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onDismiss = {
                                showDeleteConfirmationDialog = false
                                recordingToDelete = null
                            }
                        )
                    } else {
                        // This case should ideally not be reached if showDeleteConfirmationDialog is true
                        // but recordingToDelete became null. Resetting state is safe.
                        showDeleteConfirmationDialog = false
                        recordingToDelete = null
                    }
                }

                when (currentScreen) {
                    AppScreen.MAIN_TRANSCRIPTION -> {
                        // If audio was somehow playing and we got here, stop it.
                        // This might happen if user uses system back from Detail/List while playing.
                        LaunchedEffect(isAudioPlaying) { // Stop if navigating here and audio is playing
                            if (isAudioPlaying) {
                                stopAudioPlayback(updateCurrentlyPlayingPathLambda, updateIsAudioPlayingLambda)
                            }
                        }
                        TranscriptionScreen(
                            startRecording = ::startRecording,
                            stopRecording = ::stopRecording,
                            onViewRecordings = {
                                // No manual fetch needed here for the list,
                                // AppScreen.RECORDINGS_LIST case will collect the Flow.
                                currentScreen = AppScreen.RECORDINGS_LIST
                            },
                            onRecordingNamedAndReadyForTranscription = { filePath, userGivenName ->
                                startAutoTranscriptionAndNavigate(filePath, userGivenName)
                            })
                    }

                    AppScreen.RECORDINGS_LIST -> {
                        LaunchedEffect(isAudioPlaying) {
                            if (isAudioPlaying && activeRecordingId != null) { // Check if detail screen was active
                                // This logic is tricky: which file was playing?
                                // Better to handle stop in onBack of DetailScreen.
                            }
                        }
                        // Collect the Flow of recordings from the DAO as state
                        val recordingsListFromDb by recordingDao.getAllRecordings()
                            .collectAsState(initial = emptyList())

                        RecordingsListScreen(
                            recordings = recordingsListFromDb, // Pass List<RecordingItem>
                            onRecordingSelected = { recordingItem ->
                                activeRecordingId = recordingItem.id
                                // activeUserGivenName, activeRecordingFilePath, activeTranscriptionStateDisplay
                                // will be fetched by RecordingDetailScreen using the ID and DAO
                                currentScreen = AppScreen.RECORDING_DETAIL
                            },
                            onDeleteRecordingClicked = { itemToDelete -> // <<< HANDLE CALLBACK
                                recordingToDelete = itemToDelete
                                showDeleteConfirmationDialog = true
                            },
                            onDismiss = { // Navigating from List back to Main
                                if (isAudioPlaying) { // If anything was playing (though list doesn't play now)
                                    stopAudioPlayback(updateCurrentlyPlayingPathLambda, updateIsAudioPlayingLambda)
                                }
                                currentScreen = AppScreen.MAIN_TRANSCRIPTION
                            },
                            // Pass playback state down
                            currentlyPlayingFilePath = currentlyPlayingFilePath,
                            isAudioPlaying = isAudioPlaying
                        )
                    }

                    AppScreen.RECORDING_DETAIL -> {
                        activeRecordingId?.let { recId ->
                            RecordingDetailScreen(
                                recordingId = recId,
                                recordingDao = recordingDao,
                                onPlayRecordingRequest = { filePathFromDetail ->
                                    togglePlayStopForFile(filePathFromDetail)
                                },
                                onRetryTranscription = { recIdToRetry, filePathToRetry, userGivenNameToRetry -> // RecordingDetailScreen provides these
                                    // Set the shared state to show "Retrying..." immediately on the detail screen
                                    // if it's observing activeTranscriptionStateDisplay.
                                    // However, the better way is for DetailScreen to observe its own DB item's status.
                                    // For now, let's update the shared state for consistency with current DetailScreen structure.
                                    if (recIdToRetry == activeRecordingId) { // Check if it's still the active item
                                        activeTranscriptionStateDisplay =
                                            "Retrying transcription for '$userGivenNameToRetry'..."
                                    }
                                    // Update DB status to "Retrying"
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        recordingDao.updateStatusById(
                                            recIdToRetry,
                                            "Retrying Transcription..."
                                        )
                                    }
                                    // Call the upload function
                                    uploadAudioFileToServer(
                                        filePathToUpload = filePathToRetry,
                                        userGivenNameToUpdate = userGivenNameToRetry,
                                        recordingId = recIdToRetry,
                                        idOfRecordingInDetailView = activeRecordingId, // ID of item currently in detail view
                                        setActiveTranscriptionDisplay = { newState ->
                                            if (recIdToRetry == activeRecordingId) {
                                                activeTranscriptionStateDisplay = newState
                                            }
                                        }
                                    )
                                },
                                onDeleteThisRecording = { recordingItemToDelete -> // RecordingDetailScreen will provide the item
                                    recordingToDelete =
                                        recordingItemToDelete // Set state for confirmation dialog
                                    showDeleteConfirmationDialog = true
                                },
                                onBack = { // Navigating from Detail back to List
                                    if (isAudioPlaying) {
                                        stopAudioPlayback(updateCurrentlyPlayingPathLambda, updateIsAudioPlayingLambda)
                                    }
                                    currentScreen = AppScreen.RECORDINGS_LIST
                                    activeRecordingId = null // Clear active ID when going back
                                },
                                // Pass playback state down
                                currentlyPlayingFilePath = currentlyPlayingFilePath,
                                isAudioPlaying = isAudioPlaying
                            )
                        } ?: run { /* Error: No Recording ID. Navigating back. */
                            LaunchedEffect(Unit){currentScreen = AppScreen.RECORDINGS_LIST}
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

    // --- Playback Control Functions ---
    private fun playAudioFile(
        filePath: String,
        setCurrentlyPlayingPath: (String?) -> Unit, // Callback to update Compose state
        setIsPlaying: (Boolean) -> Unit          // Callback to update Compose state
    ) {
        // Stop any current playback before starting a new one
        // This ensures only one audio plays at a time and resources are released.
        releaseMediaPlayerInstance()
        setIsPlaying(false) // Update state to reflect that previous playback (if any) stopped
        setCurrentlyPlayingPath(null)

        Log.d(TAG, "Attempting to play: $filePath")
        MPlayer = MediaPlayer().apply {
            try {
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    Log.d(TAG, "MediaPlayer prepared for: $filePath. Starting playback.")
                    mp.start()
                    setIsPlaying(true)
                    setCurrentlyPlayingPath(filePath)
                }
                setOnCompletionListener {
                    Log.d(TAG, "MediaPlayer playback completed for: $filePath")
                    releaseMediaPlayerInstance() // Stop and release
                    setIsPlaying(false)
                    setCurrentlyPlayingPath(null)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer Error for $filePath: what $what, extra $extra")
                    releaseMediaPlayerInstance()
                    setIsPlaying(false)
                    setCurrentlyPlayingPath(null)
                    Toast.makeText(applicationContext, "Error playing audio.", Toast.LENGTH_SHORT).show()
                    true // Indicates error was handled
                }
                prepareAsync() // Prepare asynchronously
            } catch (e: Exception) { // Catch IOException, IllegalStateException, etc. broadly
                Log.e(TAG, "MediaPlayer setup failed for $filePath: ${e.message}")
                Toast.makeText(applicationContext, "Cannot play this audio file.", Toast.LENGTH_SHORT).show()
                releaseMediaPlayerInstance() // Clean up if setup failed
                setIsPlaying(false)
                setCurrentlyPlayingPath(null)
            }
        }
    }

    private fun stopAudioPlayback( // This function will be called by UI events or navigation
        setCurrentlyPlayingPath: (String?) -> Unit,
        setIsPlaying: (Boolean) -> Unit
    ) {
        releaseMediaPlayerInstance()
        setIsPlaying(false)
        setCurrentlyPlayingPath(null)
    }

    private fun releaseMediaPlayerInstance() { // Helper to centralize release logic
        MPlayer?.apply {
            try {
                if (isPlaying) {
                    stop()
                }
                reset()   // Reset to uninitialized state
                release() // Release system resources
                Log.d(TAG, "MediaPlayer released.")
            } catch (e: Exception) {
                Log.e(TAG, "Exception during MediaPlayer release: ${e.message}")
            }
        }
        MPlayer = null
    }
}


// Ensure these imports are present at the top of your MainActivity.kt
// import androidx.compose.material3.AlertDialog // Already there if RecordingNameDialog is in this file
// import androidx.compose.material3.TextField // Already there if RecordingNameDialog is in this file

@Composable
fun TranscriptionScreen(
    startRecording: (context: android.content.Context, updateUiOnStart: (filePath: String) -> Unit) -> Unit,
    stopRecording: (context: android.content.Context, updateUiOnStop: (filePath: String?) -> Unit) -> Unit,
    onViewRecordings: () -> Unit,
    onRecordingNamedAndReadyForTranscription: (filePath: String, userGivenName: String) -> Unit,
    modifier: Modifier = Modifier,
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
                    onRecordingNamedAndReadyForTranscription(originalPath, userGivenName)
                    // uiMessageText can be updated by MainActivity or DetailScreen later
                }
                tempFilePathForNaming = null
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            "DocBud",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Status message
        Text(
            text = uiMessageText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        // Buttons
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
                                uiMessageText =
                                    "Stopped: ${File(stoppedFilePath).name}. Please name it."
                            } else {
                                uiMessageText = "Recording failed or was cancelled."
                            }
                        }
                    } else { // Start Recording
                        uiMessageText = "Checking permission..."
                        when (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        )) {
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
            ) {
                Text(if (isRecording) "Stop Recording" else "Record Audio")
            }

            Spacer(modifier = Modifier.height(12.dp))


        Button(
                onClick = onViewRecordings,
                enabled = !isRecording && !showNameDialog,
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text("My Recordings")
            }
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
            onRecordingNamedAndReadyForTranscription = { _, _ -> }
        )
    }
}

@Composable
fun RecordingsListScreen(
    recordings: List<RecordingItem>,
    onRecordingSelected: (RecordingItem) -> Unit,
    //onPlayRecordingRequest: (RecordingItem) -> Unit,
    onDeleteRecordingClicked: (RecordingItem) -> Unit, // <<< NEW CALLBACK
    onDismiss: () -> Unit,
    // Receive playback state
    currentlyPlayingFilePath: String?,
    isAudioPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding() // For status bar
            .padding(horizontal = 16.dp) // Horizontal padding for the whole screen
    ) {
        Text(
            "My Recordings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp) // Padding for title
        )
            if (recordings.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(), // Takes up space
                    contentAlignment = Alignment.Center
                ) {   Text("No recordings found. Start by making a new recording!")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f), // Takes up available space
                    contentPadding = PaddingValues(bottom = 16.dp) // Add padding at the bottom of the list
                ) {
                    items(recordings) { recordingItem -> // Iterate over RecordingItem
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onRecordingSelected(recordingItem) },
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = recordingItem.userGivenName, // Display user-given name
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Recorded: ${formatMillisToDateTime(recordingItem.recordingDate)}", // Display formatted date from DB
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "Status: ${recordingItem.transcriptionStatus}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (recordingItem.transcriptionStatus.startsWith("Failed")) MaterialTheme.colorScheme.error else LocalContentColor.current
                                    )
                                }
                                IconButton(onClick = { onDeleteRecordingClicked(recordingItem) }) { // <<< NEW DELETE BUTTON
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete Recording", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        // Add space between list and button
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = onDismiss) {
                Text("Back to Main Screen")
            }
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
    recordingId: Int,
    recordingDao: RecordingDao,
    onPlayRecordingRequest: (filePath: String) -> Unit,
    onRetryTranscription: (recordingId: Int, filePath: String, userGivenName: String) -> Unit,
    onDeleteThisRecording: (RecordingItem) -> Unit,
    onBack: () -> Unit,
    currentlyPlayingFilePath: String?,
    isAudioPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val recordingItemState by recordingDao.getRecordingById(recordingId).collectAsState(initial = null)
    val item = recordingItemState

    LaunchedEffect(recordingId, item) {
        if (item == null) {
            kotlinx.coroutines.delay(3000)
            if (recordingItemState == null) {
                Log.w("RecordingDetailScreen", "Timeout: Recording ID $recordingId still not found. Navigating back.")
                onBack()
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (item == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text("Loading recording details...", modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("Recording Details", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
                Text("Name: ${item.userGivenName}", style = MaterialTheme.typography.titleLarge)
                Text("Recorded: ${formatMillisToDateTime(item.recordingDate)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { onPlayRecordingRequest(item.filePath) },
                    modifier = Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally)
                ) {
                    Icon(
                        imageVector = if (isAudioPlaying && currentlyPlayingFilePath == item.filePath) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (isAudioPlaying && currentlyPlayingFilePath == item.filePath) "Stop Audio" else "Play Audio"
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(if (isAudioPlaying && currentlyPlayingFilePath == item.filePath) "Stop Audio" else "Play Audio")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Status: ${item.transcriptionStatus}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

                val isError = item.transcriptionStatus.contains("Failed", true) ||
                        item.transcriptionStatus.contains("Error", true) ||
                        item.transcriptionStatus.contains("Exception", true)
                val isInProgress = item.transcriptionStatus.contains("in progress...", true) ||
                        item.transcriptionStatus.contains("Transcribing", true) ||
                        item.transcriptionStatus.contains("Retrying", true) ||
                        item.transcriptionStatus == "Pending Transcription" ||
                        item.transcriptionStatus == "Pending Naming"

                Log.d("UIState", "Transcript: ${item.transcript}, Status: ${item.transcriptionStatus}, isError=$isError, isInProgress=$isInProgress")

                if (isInProgress && !isError) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(item.transcriptionStatus, style = MaterialTheme.typography.bodyLarge)
                    }
                } else if (isError) {
                    val shortErrorMessage = item.transcriptionStatus
                        .replace("LLM_ERROR:", "")
                        .replace("ERROR:", "")
                        .replace("LLM_SKIPPED:", "")
                        .split("\n")
                        .firstOrNull()
                        ?.trim()
                        ?.take(200)
                        ?: "An error occurred during transcription."

                    Text(
                        text = shortErrorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            onRetryTranscription(item.id, item.filePath, item.userGivenName)
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                    ) {
                        Text("Retry Transcription")
                    }
                }

                Text("Transcript:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = if (isError || isInProgress) 8.dp else 0.dp, bottom = 8.dp))

                Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        text = if (!isError && !isInProgress) (item.transcript ?: "No transcript available.") else (if (isInProgress) "" else "Transcript will appear here upon successful retry."),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ✅ New Row with "View PDF" and "Share PDF"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = {
                        val pdf = createTranscriptPdf(context, item.userGivenName, item.transcript ?: "")
                        if (pdf != null) viewPdf(context, pdf)
                        else Toast.makeText(context, "Failed to open PDF.", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("View PDF")
                    }

                    Button(onClick = {
                        val pdf = createTranscriptPdf(context, item.userGivenName, item.transcript ?: "")
                        if (pdf != null) sharePdf(context, pdf)
                        else Toast.makeText(context, "Failed to share PDF.", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Share PDF")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { onDeleteThisRecording(item) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Delete This Recording")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { onBack() },
                    modifier = Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally)
                ) {
                    Text("Back to Recordings List")
                }
            }
        }
    }
}



@Composable
fun ConfirmDeleteDialog(
    itemName: String, // Name of the recording to show in the message
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Deletion") },
        text = { Text("Are you sure you want to delete the recording \"$itemName\"? This action cannot be undone.") },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss() // Close dialog after confirm
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

