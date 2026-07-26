package com.trellis.studio

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.trellis.studio.data.BackgroundRemover
import com.trellis.studio.data.ChatRepository
import com.trellis.studio.data.FalRepository
import com.trellis.studio.data.ImageRepository
import com.trellis.studio.data.Model3DProvider
import com.trellis.studio.data.Model3DRepository
import com.trellis.studio.data.PollinationsTrellisRepository
import com.trellis.studio.data.SettingsRepository
import com.trellis.studio.data.TrellisRepository
import com.trellis.studio.data.VoiceSpeaker
import com.trellis.studio.data.api.PollinationsApi
import com.trellis.studio.data.api.TrellisApi
import com.trellis.studio.data.db.AppDatabase
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {

    /** One-shot handoff of an image from the Text-to-Image tab to the Image-to-3D tab. */
    var pendingImagePath: String? = null

    /** One-shot handoff of a code block's content to the Canvas viewer. */
    var pendingCanvasContent: Pair<String, String>? = null // language to code

    val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "trellis-studio.db"
    )
        // Chat tables were added after the first release; no user data worth
        // preserving across that schema bump yet, so drop and recreate.
        .fallbackToDestructiveMigration()
        .build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val pollinationsApi: PollinationsApi = Retrofit.Builder()
        .baseUrl("https://image.pollinations.ai/")
        .client(okHttpClient)
        .build()
        .create(PollinationsApi::class.java)

    private val trellisApi: TrellisApi = Retrofit.Builder()
        .baseUrl("https://ai.api.nvidia.com/")
        .client(okHttpClient)
        .build()
        .create(TrellisApi::class.java)

    val settingsRepository = SettingsRepository(context.applicationContext)

    val imageRepository = ImageRepository(context.applicationContext, pollinationsApi)

    val backgroundRemover = BackgroundRemover(context.applicationContext)

    val trellisRepository = TrellisRepository(
        context.applicationContext,
        trellisApi,
        apiKeyProvider = {
            settingsRepository.nvidiaApiKey.value.ifBlank { BuildConfig.TRELLIS_API_KEY }
        }
    )

    val falRepository = FalRepository(
        context.applicationContext,
        okHttpClient,
        apiKeyProvider = { settingsRepository.falApiKey.value }
    )

    val pollinationsTrellisRepository = PollinationsTrellisRepository(
        context.applicationContext,
        okHttpClient,
        apiKeyProvider = { settingsRepository.pollinationsApiKey.value }
    )

    /** The currently active Image-to-3D backend, per the Settings provider choice. */
    val model3DRepository: Model3DRepository
        get() = when (settingsRepository.provider.value) {
            Model3DProvider.NVIDIA_TRELLIS -> trellisRepository
            Model3DProvider.FAL_TRELLIS -> falRepository
            Model3DProvider.POLLINATIONS_TRELLIS -> pollinationsTrellisRepository
        }

    // Chat reuses the NVIDIA key — same nvapi- key that powers TRELLIS also unlocks
    // 100+ text/reasoning models on NVIDIA's OpenAI-compatible NIM endpoint.
    val chatRepository = ChatRepository(
        okHttpClient,
        apiKeyProvider = {
            settingsRepository.nvidiaApiKey.value.ifBlank { BuildConfig.TRELLIS_API_KEY }
        }
    )

    val voiceSpeaker = VoiceSpeaker(context.applicationContext)
}
