package com.example.medassist

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
// Import your app's BuildConfig if you were to use that approach later
// import com.example.medassist.BuildConfig

object RetrofitClient {
    // --- START CONFIGURATION FOR BASE URL ---
    private const val USE_PHYSICAL_DEVICE_URL = true // <-- SET TO true FOR PHYSICAL DEVICE, false FOR EMULATOR

    private const val EMULATOR_URL = "http://10.0.2.2:5000/api/v1/"
    private const val PHYSICAL_DEVICE_LOCAL_URL = "http://192.168.86.85:5000/api/v1/" // <-- REPLACE WITH YOUR PC's WIFI IP
    // --- END CONFIGURATION ---

    // This 'val' will be initialized when the RetrofitClient object is first accessed.
    // Its value will be constant for the lifetime of the object.
    private val ACTUAL_BASE_URL: String = if (USE_PHYSICAL_DEVICE_URL) {
        PHYSICAL_DEVICE_LOCAL_URL
    } else {
        EMULATOR_URL
    }

// This is what Retrofit will use. It also cannot be 'const val' because it's initialized from ACTUAL_BASE_URL.
    // We can actually just use ACTUAL_BASE_URL directly in the Retrofit builder.
    // Let's simplify and use one variable that Retrofit Builder uses.

    // --- Final BASE_URL to be used by Retrofit ---
    private val BASE_URL: String = if (USE_PHYSICAL_DEVICE_URL) {
        PHYSICAL_DEVICE_LOCAL_URL
    } else {
        EMULATOR_URL
    }

    // Create a logging interceptor (as before)
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // Create an OkHttpClient (as before)
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(60, TimeUnit.SECONDS) // Keep generous timeouts for now
        .readTimeout(600, TimeUnit.SECONDS)   // Keep generous timeouts for now
        .writeTimeout(60, TimeUnit.SECONDS)  // Keep generous timeouts for now
        .build()

    // Create the Retrofit instance (lazily)
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL) // Use the regular 'val' BASE_URL
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    // Publicly accessible instance of your ApiService (as before)
    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }
}