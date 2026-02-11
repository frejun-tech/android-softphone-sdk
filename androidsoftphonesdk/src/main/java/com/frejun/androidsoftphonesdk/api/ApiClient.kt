package com.frejun.androidsoftphonesdk.api

import com.frejun.androidsoftphonesdk.storage.TokenStorage
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton factory for creating the Retrofit API service instance.
 *
 * It configures an OkHttpClient with:
 * 1.  An AuthInterceptor to add the "Authorization" header to requests.
 * 2.  A TokenAuthenticator to automatically handle 401 Unauthorized errors by refreshing the token.
 * 3.  A logging interceptor for debugging network traffic.
 */
internal object ApiClient {

    fun create(
        tokenStorage: TokenStorage,
        // Lambda function that performs the token refresh logic.
        // Returns `true` on success, `false` on failure.
        refreshTokenAction: suspend () -> Boolean
    ): FrejunApiService {

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY // Use .NONE in production
        }

        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val accessToken = tokenStorage.getTokens()?.accessToken

            if (accessToken == null || originalRequest.header("Authorization") != null) {
                // If no token or auth header is already present, proceed as is.
                return@Interceptor chain.proceed(originalRequest)
            }

            val newRequest = originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
            chain.proceed(newRequest)
        }

        val tokenAuthenticator = object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                // We need to synchronize to prevent multiple concurrent refresh calls
                synchronized(this) {
                    val currentToken = tokenStorage.getTokens()?.accessToken
                    // Check if the token was already refreshed by another thread while this one was waiting
                    if (currentToken != null && response.request.header("Authorization") == "Bearer $currentToken") {
                        // Token is the same as the one that failed, so we need to refresh.
                        val refreshed = kotlinx.coroutines.runBlocking {
                            refreshTokenAction()
                        }
                        if (!refreshed) {
                            return null // Refresh failed, give up.
                        }
                    }

                    // Retry the request with the new token
                    val newToken = tokenStorage.getTokens()?.accessToken ?: return null
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                }
            }
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .authenticator(tokenAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(FrejunApiService.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FrejunApiService::class.java)
    }
}