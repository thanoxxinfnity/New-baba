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
import com.trellis.studio.data.NvidiaVoiceRepository
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

    var pendingImagePath: String? = null
    var pendingCanvasContent: Pair<String, String>? = null

    val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "trellis-studio.db"
    )
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

    val model3DRepository: Model3DRepository
        get() = when (settingsRepository.provider.value) {
            Model3DProvider.NVIDIA_TRELLIS     -> trellisRepository
            Model3DProvider.FAL_TRELLIS        -> falRepository
            Model3DProvider.POLLINATIONS_TRELLIS -> pollinationsTrellisRepository
        }

    val chatRepository = ChatRepository(
        okHttpClient,
        apiKeyProvider = {
            settingsRepository.nvidiaApiKey.value.ifBlank { BuildConfig.TRELLIS_API_KEY }
        }
    )

    val nvidiaVoiceRepository = NvidiaVoiceRepository(
        context    = context.applicationContext,
        okHttpClient = okHttpClient,
        apiKeyProvider   = { settingsRepository.nvidiaApiKey.value },
        voiceFileProvider = { settingsRepository.voiceCloneFilePath.value },
        voiceModelProvider = { settingsRepository.voiceModel.value }
    )

    val voiceSpeaker = VoiceSpeaker(
        context = context.applicationContext,
        nvidiaVoiceRepository = nvidiaVoiceRepository,
        nvidiaVoiceEnabledProvider = { settingsRepository.nvidiaVoiceEnabled.value }
    )
}
