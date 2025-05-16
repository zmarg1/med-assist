package com.example.medassist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build // Required for Build.VERSION.SDK_INT
import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MedAssistTheme {
                TranscriptionScreen()
            }
        }
    }
}

@Composable
fun TranscriptionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current // Get the current context
    var transcriptionText by remember { mutableStateOf("Your transcription will appear here...") }

    // Launcher for RECORD_AUDIO permission
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
            if (isGranted) {
                // Permission Granted: TODO: Implement audio recording logic
                Toast.makeText(context, "Record Audio permission granted", Toast.LENGTH_SHORT).show()
                // For now, let's update the text to show permission was granted
                transcriptionText = "Record Audio permission granted. Ready to record!"
            } else {
                // Permission Denied
                Toast.makeText(context, "Record Audio permission denied", Toast.LENGTH_SHORT).show()
                transcriptionText = "Record Audio permission denied. Cannot record."
            }
        }
    )

    // Launcher for storage permission (READ_MEDIA_AUDIO or READ_EXTERNAL_STORAGE)
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
                        // Check if permission is already granted
                        when (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        )) {
                            PackageManager.PERMISSION_GRANTED -> {
                                // Permission is already granted: TODO: Implement audio recording logic
                                Toast.makeText(context, "Record Audio permission already granted", Toast.LENGTH_SHORT).show()
                                transcriptionText = "Record Audio permission already granted. Ready to record!"
                            }
                            else -> {
                                // Permission is not granted, launch the permission request
                                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text("Record Audio")
                }
                Button(
                    onClick = {
                        // Determine which storage permission to request based on Android version
                        val storagePermission =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU is API 33
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
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TranscriptionScreenPreview() {
    MedAssistTheme {
        TranscriptionScreen()
    }
}