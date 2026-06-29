package com.pinna.app.runtime

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.room.Room
import com.pinna.app.connectivity.AndroidLocalHotspotCoordinator
import com.pinna.app.connectivity.DefaultNetworkAddressProvider
import com.pinna.app.library.AndroidTrackImporter
import com.pinna.app.library.PinnaDatabase
import com.pinna.app.library.RoomTrackRepository
import com.pinna.app.library.youtube.NewPipeYouTubeAudioResolver
import com.pinna.app.library.youtube.OkHttpDownloader
import com.pinna.app.library.youtube.YouTubeTrackImporter
import okhttp3.OkHttpClient
import com.pinna.app.network.HttpLocalRoomClient
import com.pinna.app.network.HttpLocalRoomServer
import com.pinna.app.playback.Media3PlaybackController
import com.pinna.app.sync.AudioRoute
import com.pinna.app.voice.AudioRecordVoiceSource
import com.pinna.app.voice.AudioTrackVoiceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PinnaRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeJob = SupervisorJob()
    private val runtimeScope = CoroutineScope(runtimeJob + Dispatchers.Main.immediate)
    private val playback = Media3PlaybackController(appContext)
    private val server = HttpLocalRoomServer(
        bindHost = "0.0.0.0",
        addressProvider = DefaultNetworkAddressProvider(),
    )
    private val client = HttpLocalRoomClient()
    private val importer = AndroidTrackImporter(appContext)
    private val httpClient = OkHttpClient()
    private val remoteImporter = YouTubeTrackImporter(
        context = appContext,
        resolver = NewPipeYouTubeAudioResolver(OkHttpDownloader(httpClient)),
        httpClient = httpClient,
    )
    private val hotspotCoordinator = AndroidLocalHotspotCoordinator(appContext)
    private val voiceSource = AudioRecordVoiceSource()
    private val voiceSink = AudioTrackVoiceSink()
    private val database = Room.databaseBuilder(appContext, PinnaDatabase::class.java, "pinna.db").build()
    private val trackRepository = RoomTrackRepository(database.trackDao())

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    val controller: PinnaSessionController = PinnaSessionController(
        server = server,
        client = client,
        playback = playback,
        importer = importer,
        remoteImporter = remoteImporter,
        trackRepository = trackRepository,
        hotspotCoordinator = hotspotCoordinator,
        voiceSource = voiceSource,
        voiceSink = voiceSink,
        audioRouteProvider = ::currentAudioRoute,
        scope = runtimeScope,
    )

    private fun currentAudioRoute(): AudioRoute {
        val manager = audioManager ?: return AudioRoute.UNKNOWN
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var sawWired = false
        var sawSpeaker = false
        outputs.forEach { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                -> return AudioRoute.BLUETOOTH
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                -> sawWired = true
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> sawSpeaker = true
            }
        }
        return when {
            sawWired -> AudioRoute.WIRED
            sawSpeaker -> AudioRoute.SPEAKER
            else -> AudioRoute.UNKNOWN
        }
    }

    init {
        runtimeScope.launch {
            controller.loadPersistedTracks()
        }
    }

    fun release() {
        runBlocking { controller.shutdown() }
        runtimeScope.cancel()
        playback.release()
        database.close()
    }
}
