package com.reactlibrary.player;

import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Pair;
import android.view.Surface;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.RawResourceDataSource;
import com.google.android.exoplayer2.util.Util;

import android.util.Log;

import java.io.IOException;

import com.reactlibrary.AVModule;

class SimpleExoPlayerData extends PlayerData
    implements Player.EventListener, ExtractorMediaSource.EventListener {

  private static final String IMPLEMENTATION_NAME = "SimpleExoPlayer";

  private SimpleExoPlayer mSimpleExoPlayer = null;
  private String mOverridingExtension;
  private LoadCompletionListener mLoadCompletionListener = null;
  private Integer mLastPlaybackState = null;
  private boolean mIsLooping = false;
  private boolean mIsLoading = true;
  private ReactContext mReactContext;

  private static final String TAG = "PakExo";
  // Measures bandwidth during playback. Can be null if not required.
  private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();

  SimpleExoPlayerData(final AVModule avModule, final ReactContext context, final Uri uri, final String overridingExtension) {
    super(avModule, uri);
    mReactContext = context;
    mOverridingExtension = overridingExtension;
  }

  @Override
  String getImplementationName() {
    return IMPLEMENTATION_NAME;
  }

    @Override
    Looper getExoPlayerLooper() {
        return mSimpleExoPlayer.getApplicationLooper();
    }

    // --------- PlayerData implementation ---------

  // Lifecycle

  @Override
  public void load(final ReadableMap status, final LoadCompletionListener loadCompletionListener) {
    mLoadCompletionListener = loadCompletionListener;

    // Create a default TrackSelector
    final Handler mainHandler = new Handler();
    final TrackSelector trackSelector = new DefaultTrackSelector(mReactContext);

    // Create the player
    mSimpleExoPlayer = new SimpleExoPlayer.Builder(mReactContext).build();
    mSimpleExoPlayer.addListener(this);

    // Produces DataSource instances through which media data is loaded.
    final DataSource.Factory dataSourceFactory = new SharedCookiesDataSourceFactory(mUri, mReactContext, Util.getUserAgent(mReactContext, "yourApplicationName"));

    try {
      // This is the MediaSource representing the media to be played.
      final MediaSource source = buildMediaSource(mUri, mOverridingExtension, mainHandler, dataSourceFactory);
      mSimpleExoPlayer.setMediaSource(source);
      // Prepare the player with the source.
      mSimpleExoPlayer.prepare();
      setStatus(status, null);
    } catch (IllegalStateException e) {
      Log.d(TAG, "pak exception when mSimpleExoPlayer.prepare");
      onFatalError(e);
    }
  }

  @Override
  public synchronized void release() {
    if (mSimpleExoPlayer != null) {
      mSimpleExoPlayer.release();
      mSimpleExoPlayer = null;
    }
  }

  @Override
  boolean shouldContinueUpdatingProgress() {
    return mSimpleExoPlayer != null && mSimpleExoPlayer.getPlayWhenReady();
  }

  // Set status

  @Override
  void playPlayerWithRateAndMuteIfNecessary() throws AVModule.AudioFocusNotAcquiredException {
    if (mSimpleExoPlayer == null || !shouldPlayerPlay()) {
      return;
    }

    if (!mIsMuted) {
      mAVModule.acquireAudioFocus();
    }

    updateVolumeMuteAndDuck();

    mSimpleExoPlayer.setPlaybackParameters(new PlaybackParameters(mRate, mShouldCorrectPitch ? 1.0f : mRate));

    mSimpleExoPlayer.setPlayWhenReady(mShouldPlay);

    beginUpdatingProgressIfNecessary();
  }

  @Override
  void applyNewStatus(final Integer newPositionMillis, final Boolean newIsLooping)
      throws AVModule.AudioFocusNotAcquiredException, IllegalStateException {
    //pak here is an error!
    if (mSimpleExoPlayer == null) {
      throw new IllegalStateException("mSimpleExoPlayer is null!");
    }

    // Set looping idempotently
    if (newIsLooping != null) {
      mIsLooping = newIsLooping;
      if (mIsLooping) {
        mSimpleExoPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
      } else {
        mSimpleExoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
      }
    }

    // Pause first if necessary.
    if (!shouldPlayerPlay()) {
      mSimpleExoPlayer.setPlayWhenReady(false);
      stopUpdatingProgressIfNecessary();
    }

    // Mute / update volume if it doesn't require a request of the audio focus.
    updateVolumeMuteAndDuck();

    // Seek
    if (newPositionMillis != null) {
      // TODO handle different timeline cases for streaming
      mSimpleExoPlayer.seekTo(newPositionMillis);
    }

    // Play / unmute
    playPlayerWithRateAndMuteIfNecessary();
  }

  // Get status

  @Override
  boolean isLoaded() {
    return mSimpleExoPlayer != null;
  }

  @Override
  void getExtraStatusFields(final WritableMap map) {
    // TODO handle different timeline cases for streaming
    final int duration = (int) mSimpleExoPlayer.getDuration();
    map.putInt(STATUS_DURATION_MILLIS_KEY_PATH, duration);
    map.putInt(STATUS_POSITION_MILLIS_KEY_PATH,
        getClippedIntegerForValue((int) mSimpleExoPlayer.getCurrentPosition(), 0, duration));
    map.putInt(STATUS_PLAYABLE_DURATION_MILLIS_KEY_PATH,
        getClippedIntegerForValue((int) mSimpleExoPlayer.getBufferedPosition(), 0, duration));

    map.putBoolean(STATUS_IS_PLAYING_KEY_PATH,
        mSimpleExoPlayer.getPlayWhenReady() && mSimpleExoPlayer.getPlaybackState() == Player.STATE_READY);
    map.putBoolean(STATUS_IS_BUFFERING_KEY_PATH,
        mIsLoading || mSimpleExoPlayer.getPlaybackState() == Player.STATE_BUFFERING);

    map.putBoolean(STATUS_IS_LOOPING_KEY_PATH, mIsLooping);
  }

  @Override
  public int getAudioSessionId() {
    return mSimpleExoPlayer != null ? mSimpleExoPlayer.getAudioSessionId() : 0;
  }

  // --------- Interface implementation ---------

  // AudioEventHandler

  @Override
  public void pauseImmediately() {
    if (mSimpleExoPlayer != null) {
      mSimpleExoPlayer.setPlayWhenReady(false);
    }
    stopUpdatingProgressIfNecessary();
  }

  @Override
  public boolean requiresAudioFocus() {
    return mSimpleExoPlayer != null && (mSimpleExoPlayer.getPlayWhenReady() || shouldPlayerPlay()) && !mIsMuted;
  }

  @Override
  public void updateVolumeMuteAndDuck() {
    if (mSimpleExoPlayer != null) {
      mSimpleExoPlayer.setVolume(mAVModule.getVolumeForDuckAndFocus(mIsMuted, mVolume));
    }
  }

  // ExoPlayer.EventListener

  @Override
  public void onLoadingChanged(final boolean isLoading) {
    mIsLoading = isLoading;
    callStatusUpdateListener();
  }

  @Override
  public void onPlaybackParametersChanged(PlaybackParameters parameters) {
  }

  @Override
  public void onSeekProcessed() {

  }

  @Override
  public void onRepeatModeChanged(int repeatMode) {
  }

  @Override
  public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {

  }

  @Override
  public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {

  }

  @Override
  public void onTracksChanged(TrackGroupArray trackGroups,
                              TrackSelectionArray trackSelections) {

  }

  @Override
  public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
    if (playbackState == Player.STATE_READY && mLoadCompletionListener != null) {
      final LoadCompletionListener listener = mLoadCompletionListener;
      mLoadCompletionListener = null;
      listener.onLoadSuccess(getStatus());
    }

    if (mLastPlaybackState != null
        && playbackState != mLastPlaybackState
        && playbackState == Player.STATE_ENDED) {
      callStatusUpdateListenerWithDidJustFinish();
    } else {
      callStatusUpdateListener();
    }
    mLastPlaybackState = playbackState;
  }



  @Override
  public void onPlayerError(final ExoPlaybackException error) {
    mErrorListener.onError("Player error: " + error.getMessage());
  }

  @Override
  public void onPositionDiscontinuity(int reason) {

  }

  // ExtractorMediaSource.EventListener

  @Override
  public void onLoadError(final IOException error) {
    onFatalError(error);
  }

  private void onFatalError(final Exception error) {
    Log.d(TAG, "onFatalError: " + error);

    if (mLoadCompletionListener != null) {
      final LoadCompletionListener listener = mLoadCompletionListener;
      mLoadCompletionListener = null;
      listener.onLoadError(error.toString());
    }
    release();
  }

  // https://github.com/google/ExoPlayer/blob/2b20780482a9c6b07416bcbf4de829532859d10a/demos/main/src/main/java/com/google/android/exoplayer2/demo/PlayerActivity.java#L365-L393
  private MediaSource buildMediaSource(Uri uri, String overrideExtension, Handler mainHandler, DataSource.Factory factory) {
    @C.ContentType int type = TextUtils.isEmpty(overrideExtension) ? Util.inferContentType(String.valueOf(uri)) : Util.inferContentType("." + overrideExtension);

    switch (type) {
      case C.TYPE_OTHER://this one is ours 3
        return new ExtractorMediaSource(uri, factory, new DefaultExtractorsFactory(), mainHandler, this);
      default: {
        throw new IllegalStateException("Content of this type is unsupported at the moment. Unsupported type: " + type);
      }
    }
  }
}
