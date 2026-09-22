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

    /**
     * Certificate pinning strategy (third-party client — we do not control *.ry.mk / auth.ry.mk):
     *
     * - Prefer long-lived Let's Encrypt **intermediates** (YE1 / YE2). OkHttp accepts a
     *   connection if ANY cert in the presented chain matches a pin, so intermediate pins
     *   survive routine leaf rotations without an app update.
     * - Do **not** pin LE leaf SPKIs for hosts we do not operate; leaves rotate every ~60–90
     *   days with no advance notice to us.
     * - Dual-pin (old+new intermediate) only when *we* ship an app during a known LE
     *   intermediate migration — never as coordination with server owners.
     * - bgm.tv / lain.bgm.tv: keep the current leaf + YE1 (still matching), plus YE2 as a
     *   durable backup for LE's random intermediate selection.
     *
     * Keep OkHttp host pins and [network_security_config.xml] conceptually in sync: NSC may
     * use one shared pin-set that is a **superset** of all pins here.
     *
     * See docs/certificate-pinning.md.
     */
    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient {
        // Pins must be string literals (tools/verify-certificate-pins.py parses .add(...)).
        // YE1: brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=
        // YE2: s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=  (letsencrypt.org/certs/gen-y/int-ye2.pem)
        val pinner = CertificatePinner.Builder()
            // Backend hosts we do not control: YE1 + YE2 intermediates only
            .add("rd.ry.mk", "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=")
            .add("rd.ry.mk", "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=")
            .add("up.ry.mk", "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=")
            .add("up.ry.mk", "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=")
            .add("auth.ry.mk", "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=")
            .add("auth.ry.mk", "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=")
            // Bangumi: current leaf + YE1/YE2
            .add("bgm.tv", "sha256/eTHuRU78dJxZftsRBfCUU0cRPMW/iJKDCgMLoZkQerE=")
            .add("bgm.tv", "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=")
            .add("bgm.tv", "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=")
            .add("lain.bgm.tv", "sha256/eTHuRU78dJxZftsRBfCUU0cRPMW/iJKDCgMLoZkQerE=")
            .add("lain.bgm.tv", "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=")
            .add("lain.bgm.tv", "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=")
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
            val encrypted = EncryptedSharedPreferences.create(
                "redollars_enc",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

            // v0.3.4 introduced encrypted preferences but did not copy existing
            // auth tokens out of the legacy plain store. Leave the old store untouched
            // if an encrypted write fails.
            val legacy = context.getSharedPreferences("redollars", Context.MODE_PRIVATE)
            val legacyAuthToken = legacy.getString("dollars_auth_token", null)?.takeIf { it.isNotBlank() }
            if (!encrypted.contains("dollars_auth_token") && legacyAuthToken != null) {
                val copied = encrypted.edit().putString("dollars_auth_token", legacyAuthToken).commit()
                if (copied) legacy.edit().remove("dollars_auth_token").apply()
            }
            if (!encrypted.contains("dollars_upload_auth_token")) {
                val uploadToken = legacy.getString("dollars_upload_auth_token", null)?.takeIf { it.isNotBlank() }
                    ?: legacyAuthToken?.takeIf { it.count { ch -> ch == '.' } == 2 }
                uploadToken?.let {
                    val copied = encrypted.edit().putString("dollars_upload_auth_token", it).commit()
                    if (copied) legacy.edit().remove("dollars_upload_auth_token").apply()
                }
            }
            encrypted
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
