package com.example.medassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    // State to hold the transcription text
    val transcriptionText by remember { mutableStateOf("Your transcription will appear here...") }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween // Pushes elements apart
        ) {
            // Title Text
            Text(
                text = "Medical Transcription App",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 32.dp) // Added more padding
            )

            // Container for Buttons
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp) // Space between buttons
            ) {
                Button(
                    onClick = { /* TODO: Implement record audio action */ },
                    modifier = Modifier.fillMaxWidth(0.8f) // Make buttons wider
                ) {
                    Text("Record Audio")
                }
                Button(
                    onClick = { /* TODO: Implement select audio file action */ },
                    modifier = Modifier.fillMaxWidth(0.8f) // Make buttons wider
                ) {
                    Text("Select Audio File")
                }
            }

            // Text area for transcription result
            Text(
                text = transcriptionText,
                modifier = Modifier
                    .weight(1f) // Allows this to take up remaining vertical space
                    .fillMaxWidth()
                    .padding(top = 32.dp, bottom = 16.dp), // Added more padding
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TranscriptionScreenPreview() {
    MedAssistTheme { // Or your actual theme name
        TranscriptionScreen()
    }
}