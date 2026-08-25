package mk.ry.redollars.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mk.ry.redollars.data.db.AppDatabase
import mk.ry.redollars.data.db.MessageDao
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** App-lifetime scope for work that must outlive any single ViewModel (WS, DB writes). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient {
        val pinner = CertificatePinner.Builder()
            .add("rd.ry.mk", "sha256/Nna/qm7tawbg1k2+WPynsaxzTVl+fpU2LLouasFdqP8=")
            .add("up.ry.mk", "sha256/Nna/qm7tawbg1k2+WPynsaxzTVl+fpU2LLouasFdqP8=")
            .add("bgm.tv", "sha256/eTHuRU78dJxZftsRBfCUU0cRPMW/iJKDCgMLoZkQerE=")
            .add("auth.ry.mk", "sha256/lOsdd9qcRNoS+JkYZYpo5QwEoW4smzwhvpAPYORVkDk=")
            .add("lain.bgm.tv", "sha256/eTHuRU78dJxZftsRBfCUU0cRPMW/iJKDCgMLoZkQerE=")
            .build()
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS) // the WS layer sends its own JSON heartbeat
            .certificatePinner(pinner)
            .build()
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.get(context)

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "redollars_enc",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Fallback to plain on devices where Keystore is broken
            context.getSharedPreferences("redollars", Context.MODE_PRIVATE)
        }
    }

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
