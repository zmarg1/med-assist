package com.example.medassist

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // IMPORTANT: Replace this with your actual backend's base URL when it's ready.
    // For local development with Android Emulator connecting to a server on your host machine:
    // - Use "http://10.0.2.2:PORT/" where PORT is the port your Python server runs on (e.g., 5000).
    // For now, we'll use a placeholder.
    private const val BASE_URL = "http://10.0.2.2:5000/api/v1/" // Example placeholder

    // Create a logging interceptor
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        // For development, Level.BODY shows all request and response data.
        // For production, you'd likely use Level.NONE or Level.BASIC.
        level = HttpLoggingInterceptor.Level.BODY
    }

    // Create an OkHttpClient and add the logging interceptor
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(60, TimeUnit.SECONDS)    // Increased connect timeout
        .readTimeout(180, TimeUnit.SECONDS)     // SIGNIFICANTLY INCREASED read timeout (3 minutes)
        .writeTimeout(60, TimeUnit.SECONDS)   // Increased write timeout
        .build()

    // Create the Retrofit instance (lazily to initialize only when first accessed)
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient) // Use our custom OkHttpClient
            .addConverterFactory(GsonConverterFactory.create()) // For JSON parsing
            .build()
    }

    // Publicly accessible instance of your ApiService
    // This uses Retrofit's create() method to generate an implementation of the ApiService interface.
    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }
}