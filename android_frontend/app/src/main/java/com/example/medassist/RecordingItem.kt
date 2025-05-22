package com.example.medassist

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings_table")
data class RecordingItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    var userGivenName: String,
    val filePath: String,         // Actual path to the .mp4 audio file on device
    val recordingDate: Long,      // Timestamp (e.g., System.currentTimeMillis())
    var transcript: String? = null,
    var transcriptionStatus: String = "Pending Naming" // Initial status
    // Possible statuses: "Pending Naming", "Pending Transcription", "Transcribing", "Completed", "Failed"
)