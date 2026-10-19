package me.proton.sync.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.sync.ProtonSyncApp
import me.proton.sync.api.AuthInterceptor
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.TokenRefreshAuthenticator
import me.proton.sync.SyncConstants
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenRefreshAuthenticator: TokenRefreshAuthenticator
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(tokenRefreshAuthenticator)
            // OkHttp's default Dispatcher.maxRequestsPerHost is 5, which would cap the calendar
            // full-fetch's concurrency regardless of its semaphore. Raise it to the full-fetch permit
            // count so the CalendarSyncAdapter semaphore stays the real gate. Other paths self-limit
            // with their own (lower) semaphores, so their behavior is unchanged.
            .dispatcher(Dispatcher().apply {
                maxRequestsPerHost = SyncConstants.CALENDAR_FULLFETCH_GET_PERMITS
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(ProtonSyncApp.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideProtonApiClient(retrofit: Retrofit): ProtonApiClient {
        return retrofit.create(ProtonApiClient::class.java)
    }
}
