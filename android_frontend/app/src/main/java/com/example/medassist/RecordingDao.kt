package com.example.medassist

import androidx.room.*
import kotlinx.coroutines.flow.Flow // For observing changes from the database

@Dao
interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: RecordingItem): Long

    @Update
    suspend fun updateRecording(recording: RecordingItem)

    @Query("SELECT * FROM recordings_table ORDER BY recordingDate DESC")
    fun getAllRecordings(): Flow<List<RecordingItem>>

    @Query("SELECT * FROM recordings_table WHERE id = :id")
    fun getRecordingById(id: Int): Flow<RecordingItem?>

    // --- ADD THIS FUNCTION IF IT'S MISSING ---
    @Query("SELECT * FROM recordings_table WHERE filePath = :filePath LIMIT 1")
    suspend fun getRecordingByFilePath(filePath: String): RecordingItem?
    // --- END OF FUNCTION TO ADD ---

    @Query("UPDATE recordings_table SET transcript = :transcriptText, transcriptionStatus = :status WHERE id = :id")
    suspend fun updateTranscriptAndStatusById(id: Int, transcriptText: String?, status: String)

    @Query("UPDATE recordings_table SET transcriptionStatus = :status WHERE id = :id")
    suspend fun updateStatusById(id: Int, status: String)

    @Query("UPDATE recordings_table SET userGivenName = :newName WHERE id = :id")
    suspend fun updateRecordingName(id: Int, newName: String)

    @Query("DELETE FROM recordings_table WHERE id = :id")
    suspend fun deleteRecordingById(id: Int)
}