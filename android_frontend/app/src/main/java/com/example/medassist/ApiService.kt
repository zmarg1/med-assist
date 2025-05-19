package com.example.medassist

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response // For more detailed response handling
import retrofit2.http.*

interface ApiService {

    @Multipart // Indicates this request will send multipart data (common for file uploads)
    @POST("upload_audio") // The endpoint path on your server (e.g., your_base_url/upload_audio)
    suspend fun uploadAudioFile(
        @Part audioFile: MultipartBody.Part, // The actual file part
        @Part("description") description: RequestBody // An example of another data part you might send
    ): Response<TranscriptionResponse> // Using Response wrapper for more details; TranscriptionResponse is a placeholder
}

// Placeholder data class for the expected response.
// We'll define this more concretely when we know what your Python backend will return.
// For now, it could be as simple as:
data class TranscriptionResponse(
    val success: Boolean,
    val message: String?,
    val transcript: String?
    // Add other fields your backend might return
)