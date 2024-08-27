package com.futo.fcast.receiver

import android.content.Context
import android.graphics.drawable.Animatable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max


class PlayerActivity : AppCompatActivity() {
    private lateinit var _playerControlView: PlayerView
    private lateinit var _imageSpinner: ImageView
    private lateinit var _textMessage: TextView
    private lateinit var _layoutOverlay: ConstraintLayout
    private lateinit var _exoPlayer: ExoPlayer
    private lateinit var _subtitleView: View
    private var _shouldPlaybackRestartOnConnectivity: Boolean = false
    private lateinit var _connectivityManager: ConnectivityManager
    private var _wasPlaying = false
    private var speedToast: Toast? = null

    private val _connectivityEvents = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.i(TAG, "_connectivityEvents onAvailable")

            try {
                lifecycleScope.launch(Dispatchers.Main) {
                    Log.i(TAG, "onConnectionAvailable")

                    val pos = _exoPlayer.currentPosition
                    val dur = _exoPlayer.duration
                    if (_shouldPlaybackRestartOnConnectivity && abs(pos - dur) > 2000) {
                        Log.i(TAG, "Playback ended due to connection loss, resuming playback since connection is restored.")
                        _exoPlayer.playWhenReady = true
                        _exoPlayer.prepare()
                        _exoPlayer.play()
                    }
                }
            } catch(ex: Throwable) {
                Log.w(TAG, "Failed to handle connection available event", ex)
            }
        }
    }

    private val _playerEventListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            Log.i(TAG, "onPlaybackStateChanged playbackState=$playbackState")

            if (_shouldPlaybackRestartOnConnectivity && playbackState == ExoPlayer.STATE_READY) {
                Log.i(TAG, "_shouldPlaybackRestartOnConnectivity=false")
                _shouldPlaybackRestartOnConnectivity = false
            }

            if (playbackState == ExoPlayer.STATE_READY) {
                setStatus(false, null)
            } else if (playbackState == ExoPlayer.STATE_BUFFERING) {
                setStatus(true, null)
            }

            sendPlaybackUpdate()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)
            sendPlaybackUpdate()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            sendPlaybackUpdate()
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)

            Log.e(TAG, "onPlayerError: $error")

            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                    Log.i(TAG, "IO error, set _shouldPlaybackRestartOnConnectivity=true")
                    _shouldPlaybackRestartOnConnectivity = true
                }
            }

            val fullMessage = getFullExceptionMessage(error)
            setStatus(false, fullMessage)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    NetworkService.instance?.sendPlaybackUpdate(PlaybackUpdateMessage(
                        System.currentTimeMillis(),
                        0.0,
                        0.0,
                        0,
                        0.0
                    ))
                    NetworkService.instance?.sendPlaybackError(fullMessage)
                } catch (e: Throwable) {
                    Log.e(TAG, "Unhandled error sending playback error", e)
                }
            }
        }

        override fun onVolumeChanged(volume: Float) {
            super.onVolumeChanged(volume)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    NetworkService.instance?.sendCastVolumeUpdate(VolumeUpdateMessage(System.currentTimeMillis(), volume.toDouble()))
                } catch (e: Throwable) {
                    Log.e(TAG, "Unhandled error sending volume update", e)
                }
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            super.onPlaybackParametersChanged(playbackParameters)
            sendPlaybackUpdate()
        }
    }

    private fun sendPlaybackUpdate() {
        val state: Int
        if (_exoPlayer.playbackState == ExoPlayer.STATE_READY) {
            if (_exoPlayer.playWhenReady) {
                state = 1

            } else {
                state = 2
            }
        } else if (_exoPlayer.playbackState == ExoPlayer.STATE_BUFFERING) {
            if (_exoPlayer.playWhenReady) {
                state = 1
            } else {
                state = 2
            }
        } else if (_exoPlayer.playbackState == ExoPlayer.STATE_ENDED) {
            state = 2
        } else {
            state = 0
        }

        val time: Double
        val duration: Double
        val speed: Double
        if (state != 0) {
            duration = (_exoPlayer.duration / 1000.0).coerceAtLeast(1.0)
            time = (_exoPlayer.currentPosition / 1000.0).coerceAtLeast(0.0).coerceAtMost(duration)
            speed = _exoPlayer.playbackParameters.speed.toDouble().coerceAtLeast(0.01)
        } else {
            time = 0.0
            duration = 0.0
            speed = 1.0
        }

        val playbackUpdate = PlaybackUpdateMessage(
            System.currentTimeMillis(),
            time,
            duration,
            state,
            speed
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                NetworkService.instance?.sendPlaybackUpdate(playbackUpdate)
            } catch (e: Throwable) {
                Log.e(TAG, "Unhandled error sending playback update", e)
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")

        setContentView(R.layout.activity_player)
        setFullScreen()

        _playerControlView = findViewById(R.id.player_control_view)
        _imageSpinner = findViewById(R.id.image_spinner)
        _textMessage = findViewById(R.id.text_message)
        _layoutOverlay = findViewById(R.id.layout_overlay)

        setStatus(true, null)

        val trackSelector = DefaultTrackSelector(this)
        trackSelector.parameters = trackSelector.parameters
            .buildUpon()
            .setPreferredTextLanguage("df")
            .setSelectUndeterminedTextLanguage(true)
            .build()

        trackSelector.setParameters(trackSelector.buildUponParameters().apply {
            setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.DEFAULT.buildUpon().apply {
                    setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                }.build()
            )
        })

        val renderersFactory =
            DefaultRenderersFactory(this).forceEnableMediaCodecAsynchronousQueueing()
        _exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector).build()
        _exoPlayer.addListener(_playerEventListener)
        _playerControlView.player = _exoPlayer
        _playerControlView.controllerAutoShow = false

        _subtitleView = _playerControlView.findViewById(androidx.media3.ui.R.id.exo_subtitles)
        val exoBasicControls = _playerControlView.findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls)
        val exoSettings = exoBasicControls.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_settings)
        exoSettings.onLongClickListener = View.OnLongClickListener {
            return@OnLongClickListener toggleTunneling()
        }

        Log.i(TAG, "Attached onConnectionAvailable listener.")
        _connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netReq = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        _connectivityManager.registerNetworkCallback(netReq, _connectivityEvents)

        val playMessage = intent.getStringExtra("message")?.let {
            try {
                Json.decodeFromString<PlayMessage>(it)
            } catch (e: Throwable) {
                Log.i(TAG, "Failed to deserialize play message.", e)
                null
            }
        }
        playMessage?.let { play(it) }

        instance = this
        NetworkService.activityCount++

        lifecycleScope.launch(Dispatchers.Main) {
            while (lifecycleScope.isActive) {
                try {
                    sendPlaybackUpdate()
                    delay(1000)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to send playback update.", e)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setFullScreen()
    }

    private fun getFullExceptionMessage(ex: Throwable): String {
        val messages = mutableListOf<String>()
        var current: Throwable? = ex
        while (current != null) {
            messages.add(current.message ?: "Unknown error")
            current = current.cause
        }
        return messages.joinToString(separator = " → ")
    }

    private fun setStatus(isLoading: Boolean, message: String?) {
        if (isLoading) {
            (_imageSpinner.drawable as Animatable?)?.start()
            _imageSpinner.visibility = View.VISIBLE
        } else {
            (_imageSpinner.drawable as Animatable?)?.stop()
            _imageSpinner.visibility = View.GONE
        }

        if (message != null) {
            _textMessage.visibility = View.VISIBLE
            _textMessage.text = message
        } else {
            _textMessage.visibility = View.GONE
        }

        _layoutOverlay.visibility = if (isLoading || message != null) View.VISIBLE else View.GONE
    }

    private fun setFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
            window.insetsController?.hide(WindowInsets.Type.navigationBars())
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
    }

    override fun onPause() {
        super.onPause()

        _wasPlaying = _exoPlayer.isPlaying
        _exoPlayer.pause()
    }

    override fun onResume() {
        super.onResume()
        if (_wasPlaying) {
            _exoPlayer.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")

        instance = null
        _connectivityManager.unregisterNetworkCallback(_connectivityEvents)
        _exoPlayer.removeListener(_playerEventListener)
        _exoPlayer.stop()
        _playerControlView.player = null
        NetworkService.activityCount--

        GlobalScope.launch(Dispatchers.IO) {
            try {
                NetworkService.instance?.sendPlaybackUpdate(PlaybackUpdateMessage(
                    System.currentTimeMillis(),
                    0.0,
                    0.0,
                    0,
                    0.0
                ))
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to send playback update.", e)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun setTunneling(new: Boolean, skipToast: Boolean = true): Boolean {
        if (!_exoPlayer.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS))
            return false

        val trackSelector = _exoPlayer.trackSelector as? DefaultTrackSelector ?: return false
        if (trackSelector.parameters.tunnelingEnabled == new)
            return true

        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                //.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, new)
                .setRendererDisabled(C.TRACK_TYPE_AUDIO, new)
                .setTunnelingEnabled(new)
                .build()
        )

        if (!skipToast) {
            Toast.makeText(
                this,
                if (new) "Audio track disabled" else "Audio track enabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        return true
    }

    @OptIn(UnstableApi::class)
    private fun toggleTunneling(): Boolean {
        val trackSelector = _exoPlayer.trackSelector as? DefaultTrackSelector ?: return false
        return setTunneling(!trackSelector.parameters.tunnelingEnabled, false)
    }

    private fun setSpeedKey(speed: Float) {
        if (_exoPlayer.playbackParameters.speed == speed || !_exoPlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))
            return
        _exoPlayer.setPlaybackSpeed(speed)
        speedToast?.cancel()
        speedToast = Toast.makeText(this, speed.toString() + "x", Toast.LENGTH_SHORT)
        speedToast!!.show()
    }

    @OptIn(UnstableApi::class)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (_playerControlView.isControllerFullyVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                _playerControlView.hideController()
                return true
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    _exoPlayer.seekTo(max(0, _exoPlayer.currentPosition - SEEK_BACKWARD_MILLIS))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    _exoPlayer.seekTo(_exoPlayer.currentPosition + SEEK_FORWARD_MILLIS)
                    return true
                }
            }
        }

        if (keyCode == KeyEvent.KEYCODE_CAPTIONS && event.action == KeyEvent.ACTION_DOWN) {
            _subtitleView.visibility = if (_subtitleView.visibility != View.VISIBLE) View.VISIBLE else View.INVISIBLE
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_PROG_RED && event.action == KeyEvent.ACTION_DOWN) {
            setSpeedKey(1.0f)
            setTunneling(false)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_PROG_GREEN && event.action == KeyEvent.ACTION_DOWN) {
            setTunneling(false)
            setSpeedKey(1.25f)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_PROG_YELLOW && event.action == KeyEvent.ACTION_DOWN) {
            setTunneling(false)
            when (_exoPlayer.playbackParameters.speed) {
                1.45f -> setSpeedKey(1.75f)
                else -> setSpeedKey(1.45f)
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_PROG_BLUE && event.action == KeyEvent.ACTION_DOWN) {
            if (_exoPlayer.playbackParameters.speed == 2.25f) {
                setTunneling(false)
                setSpeedKey(2.0f)
            } else {
                setTunneling(true)
                setSpeedKey(2.25f)
            }

            return true
        }

        return super.dispatchKeyEvent(event)
    }

    @OptIn(UnstableApi::class)
    fun play(playMessage: PlayMessage) {
        val mediaItemBuilder = MediaItem.Builder()
        if (playMessage.container.isNotEmpty()) {
            mediaItemBuilder.setMimeType(playMessage.container)
        }

        if (!playMessage.url.isNullOrEmpty()) {
            mediaItemBuilder.setUri(Uri.parse(playMessage.url))
        } else if (!playMessage.content.isNullOrEmpty()) {
            val tempFile = File.createTempFile("content_", ".tmp", cacheDir)
            tempFile.deleteOnExit()
            FileOutputStream(tempFile).use { output ->
                output.bufferedWriter().use { writer ->
                    writer.write(playMessage.content)
                }
            }

            mediaItemBuilder.setUri(Uri.fromFile(tempFile))
        } else {
            throw IllegalArgumentException("Either URL or content must be provided.")
        }

        val cronetDataSourceFactory =
            CronetDataSource.Factory(FCastApplication.getCronetEngine(), FCastApplication.getCronetCallbackExecutorService())
                .setConnectionTimeoutMs(30000)
                .setReadTimeoutMs(30000)

        val dataSourceFactory = if (playMessage.headers != null) {
            cronetDataSourceFactory.setDefaultRequestProperties(playMessage.headers)
            DefaultDataSource.Factory(this, cronetDataSourceFactory)
        } else {
            DefaultDataSource.Factory(this, cronetDataSourceFactory)
        }

        val mediaItem = mediaItemBuilder.build()
        val mediaSource = when (playMessage.container) {
            "application/dash+xml" -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            "application/x-mpegurl" -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            "application/vnd.apple.mpegurl" -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            else -> DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }

        _exoPlayer.setMediaSource(mediaSource)
        _exoPlayer.setPlaybackSpeed(playMessage.speed?.toFloat() ?: 1.0f)

        if (playMessage.time != null) {
            _exoPlayer.seekTo((playMessage.time * 1000).toLong())
        }

        setStatus(true, null)
        _subtitleView.visibility = View.VISIBLE
        _wasPlaying = false
        _exoPlayer.playWhenReady = true
        _exoPlayer.prepare()
        _exoPlayer.play()
    }

    fun pause() {
        _exoPlayer.pause()
    }

    fun resume() {
        if (_exoPlayer.playbackState == ExoPlayer.STATE_ENDED && _exoPlayer.duration - _exoPlayer.currentPosition < 1000) {
            _exoPlayer.seekTo(0)
        }

        _exoPlayer.play()
    }

    fun seek(seekMessage: SeekMessage) {
        _exoPlayer.seekTo((seekMessage.time * 1000.0).toLong())
    }

    fun setSpeed(setSpeedMessage: SetSpeedMessage) {
        _exoPlayer.setPlaybackSpeed(setSpeedMessage.speed.toFloat())
    }

    fun setVolume(setVolumeMessage: SetVolumeMessage) {
        _exoPlayer.volume = setVolumeMessage.volume.toFloat()
    }

    companion object {
        var instance: PlayerActivity? = null
        private const val TAG = "PlayerActivity"

        private const val SEEK_BACKWARD_MILLIS = 10_000
        private const val SEEK_FORWARD_MILLIS = 10_000
    }
}