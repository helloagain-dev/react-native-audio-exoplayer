package com.reactlibrary.player;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.util.Timer;
import java.util.TimerTask;

import com.google.android.exoplayer2.upstream.RawResourceDataSource;
import com.reactlibrary.AVModule;
import com.reactlibrary.AudioEventHandler;

public abstract class PlayerData implements AudioEventHandler {
  static final String STATUS_ANDROID_IMPLEMENTATION_KEY_PATH = "androidImplementation";
  static final String STATUS_IS_LOADED_KEY_PATH = "isLoaded";
  public static final String STATUS_URI_KEY_PATH = "uri";
  static final String STATUS_OVERRIDING_EXTENSION_KEY_PATH = "overridingExtension";
  static final String STATUS_PROGRESS_UPDATE_INTERVAL_MILLIS_KEY_PATH = "progressUpdateIntervalMillis";
  static final String STATUS_DURATION_MILLIS_KEY_PATH = "durationMillis";
  static final String STATUS_POSITION_MILLIS_KEY_PATH = "positionMillis";
  static final String STATUS_PLAYABLE_DURATION_MILLIS_KEY_PATH = "playableDurationMillis";
  static final String STATUS_SHOULD_PLAY_KEY_PATH = "shouldPlay";
  static final String STATUS_IS_PLAYING_KEY_PATH = "isPlaying";
  static final String STATUS_IS_BUFFERING_KEY_PATH = "isBuffering";
  static final String STATUS_RATE_KEY_PATH = "rate";
  static final String STATUS_SHOULD_CORRECT_PITCH_KEY_PATH = "shouldCorrectPitch";
  static final String STATUS_VOLUME_KEY_PATH = "volume";
  static final String STATUS_IS_MUTED_KEY_PATH = "isMuted";
  static final String STATUS_IS_LOOPING_KEY_PATH = "isLooping";
  static final String STATUS_DID_JUST_FINISH_KEY_PATH = "didJustFinish";

  private static final String TAG = "PakExo";

  public static WritableMap getUnloadedStatus() {
    final WritableMap map = Arguments.createMap();
    map.putBoolean(STATUS_IS_LOADED_KEY_PATH, false);
    return map;
  }

  public interface ErrorListener {
    void onError(final String error);
  }

  public interface LoadCompletionListener {
    void onLoadSuccess(final WritableMap status);

    void onLoadError(final String error);
  }

  public interface StatusUpdateListener {
    void onStatusUpdate(final WritableMap status);
  }

  interface SetStatusCompletionListener {
    void onSetStatusComplete();

    void onSetStatusError(final String error);
  }

  public interface FullscreenPresenter {
    boolean isBeingPresentedFullscreen();
    void setFullscreenMode(boolean isFullscreen);
  }

  final AVModule mAVModule;
  final Uri mUri;

  private Timer mTimer = null;
  private StatusUpdateListener mStatusUpdateListener = null;
  ErrorListener mErrorListener = null;

  private int mProgressUpdateIntervalMillis = 500;
  boolean mShouldPlay = false;
  float mRate = 1.0f;
  boolean mShouldCorrectPitch = false;
  float mVolume = 1.0f;
  boolean mIsMuted = false;

  PlayerData(final AVModule avModule, final Uri uri) {
    mAVModule = avModule;
    mUri = uri;
  }

  public static PlayerData createUnloadedPlayerData(final AVModule avModule, final ReactContext context, final ReadableMap source, final ReadableMap status) {
    final String uriString = source.getString(STATUS_URI_KEY_PATH);
    final String uriOverridingExtension = source.hasKey(STATUS_OVERRIDING_EXTENSION_KEY_PATH) ? source.getString(STATUS_OVERRIDING_EXTENSION_KEY_PATH) : null;

    if (startsWithValidScheme(uriString)) {
      final Uri srcUri = Uri.parse(uriString);
      Log.d(TAG, "startsWithValidScheme, srcUri: " + srcUri);

      if (srcUri != null) {
        return new SimpleExoPlayerData(avModule, context, srcUri, uriOverridingExtension);
      }
    } else {
      int identifier = context.getResources().getIdentifier(
              uriString,
              "raw",
              context.getPackageName()
      );

      if (identifier == 0) {
        identifier = context.getResources().getIdentifier(
                uriString,
                "drawable",
                context.getPackageName()
        );
      }

      if (identifier > 0) {
        Uri srcUri = RawResourceDataSource.buildRawResourceUri(identifier);
        Log.d(TAG, "identifier > 0, srcUri: " + srcUri);
        if (srcUri != null) {
          return new SimpleExoPlayerData(avModule, context, srcUri, uriOverridingExtension);
        }
      }
    }

    // uriString is guaranteed not to be null (Sound.loadAsync handle that case)
    final Uri uri = Uri.parse(uriString);
    return new SimpleExoPlayerData(avModule, context, uri, uriOverridingExtension);
  }

  private static boolean startsWithValidScheme(String uriString) {
    return uriString.startsWith("http://")
            || uriString.startsWith("https://")
            || uriString.startsWith("content://")
            || uriString.startsWith("file://")
            || uriString.startsWith("asset://");
  }

  abstract String getImplementationName();

  abstract Looper getExoPlayerLooper();

  // Lifecycle

  public abstract void load(final ReadableMap status, final LoadCompletionListener loadCompletionListener);

  public abstract void release();

  // Status update listener

  private void callStatusUpdateListenerWithStatus(final WritableMap status) {
    if (mStatusUpdateListener != null) {
      mStatusUpdateListener.onStatusUpdate(status);
    }
  }

  final void callStatusUpdateListenerWithDidJustFinish() {
    final WritableMap status = getStatus();
    status.putBoolean(STATUS_DID_JUST_FINISH_KEY_PATH, true);
    callStatusUpdateListenerWithStatus(status);
  }

  final void callStatusUpdateListener() {
    callStatusUpdateListenerWithStatus(getStatus());
  }

  abstract boolean shouldContinueUpdatingProgress();

  final void stopUpdatingProgressIfNecessary() {
    if (mTimer != null) {
      final Timer timer = mTimer;
      mTimer = null;
      timer.cancel();
    }
  }

//  private void progressUpdateLoop() {
//    if (shouldContinueUpdatingProgress()) {
//      mTimer = new Timer();
//      mTimer.schedule(new TimerTask() {
//        @Override
//        public void run() {
//          callStatusUpdateListener();
//          progressUpdateLoop();
//        }
//      }, mProgressUpdateIntervalMillis);
//    } else {
//      stopUpdatingProgressIfNecessary();
//    }
//  }

    private void progressUpdateLoop() {
        if (shouldContinueUpdatingProgress()) {
            mTimer = new Timer();
            mTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    // Post to the main thread to ensure all ExoPlayer interactions happen on the UI thread
                    new Handler(getExoPlayerLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            callStatusUpdateListener();
                            progressUpdateLoop();
                        }
                    });
                }
            }, mProgressUpdateIntervalMillis);
        } else {
            stopUpdatingProgressIfNecessary();
        }
    }

  final void beginUpdatingProgressIfNecessary() {
    if (mTimer == null) {
      progressUpdateLoop();
    }
  }

  public final void setStatusUpdateListener(final StatusUpdateListener listener) {
    mStatusUpdateListener = listener;
    if (mStatusUpdateListener != null) {
      beginUpdatingProgressIfNecessary();
    }
  }

  // Error listener

  public final void setErrorListener(final ErrorListener listener) {
    mErrorListener = listener;
  }

  // Status

  final boolean shouldPlayerPlay() {
    return mShouldPlay && mRate > 0.0;
  }

  abstract void playPlayerWithRateAndMuteIfNecessary() throws AVModule.AudioFocusNotAcquiredException;

  abstract void applyNewStatus(final Integer newPositionMillis, final Boolean newIsLooping)
      throws AVModule.AudioFocusNotAcquiredException, IllegalStateException;

  final void setStatusWithListener(final ReadableMap status, final SetStatusCompletionListener setStatusCompletionListener) {
    if (status.hasKey(STATUS_PROGRESS_UPDATE_INTERVAL_MILLIS_KEY_PATH)) {
      mProgressUpdateIntervalMillis = (int) status.getDouble(STATUS_PROGRESS_UPDATE_INTERVAL_MILLIS_KEY_PATH);
    }

    final Integer newPositionMillis;
    if (status.hasKey(STATUS_POSITION_MILLIS_KEY_PATH)) {
      // Even though we set the position with an int, this is a double in the map because iOS can
      // take a floating point value for positionMillis.
      newPositionMillis = (int) status.getDouble(STATUS_POSITION_MILLIS_KEY_PATH);
    } else {
      newPositionMillis = null;
    }

    if (status.hasKey(STATUS_SHOULD_PLAY_KEY_PATH)) {
      mShouldPlay = status.getBoolean(STATUS_SHOULD_PLAY_KEY_PATH);
    }

    if (status.hasKey(STATUS_RATE_KEY_PATH)) {
      mRate = (float) status.getDouble(STATUS_RATE_KEY_PATH);
    }

    if (status.hasKey(STATUS_SHOULD_CORRECT_PITCH_KEY_PATH)) {
      mShouldCorrectPitch = status.getBoolean(STATUS_SHOULD_CORRECT_PITCH_KEY_PATH);
    }

    if (status.hasKey(STATUS_VOLUME_KEY_PATH)) {
      mVolume = (float) status.getDouble(STATUS_VOLUME_KEY_PATH);
    }

    if (status.hasKey(STATUS_IS_MUTED_KEY_PATH)) {
      mIsMuted = status.getBoolean(STATUS_IS_MUTED_KEY_PATH);
    }

    final Boolean newIsLooping;
    if (status.hasKey(STATUS_IS_LOOPING_KEY_PATH)) {
      newIsLooping = status.getBoolean(STATUS_IS_LOOPING_KEY_PATH);
    } else {
      newIsLooping = null;
    }

    try {
      applyNewStatus(newPositionMillis, newIsLooping);
    } catch (final Throwable throwable) {
      Log.d(TAG, "Error in applyNewStatus thrown!");
      mAVModule.abandonAudioFocusIfUnused();
      setStatusCompletionListener.onSetStatusError(throwable.toString());
      return;
    }
    mAVModule.abandonAudioFocusIfUnused();
    setStatusCompletionListener.onSetStatusComplete();
  }

  public final void setStatus(final ReadableMap status, final Promise promise) {
    if (status == null) {
      if (promise != null) {
        promise.reject("E_AV_SETSTATUS", "Cannot set null status.");
      }
      return;
    }

    try {
      setStatusWithListener(status, new SetStatusCompletionListener() {
        @Override
        public void onSetStatusComplete() {
          if (promise == null) {
            callStatusUpdateListener();
          } else {
            promise.resolve(getStatus());
          }
        }

        @Override
        public void onSetStatusError(final String error) {
          if (promise == null) {
            callStatusUpdateListener();
          } else {
            promise.reject("E_AV_SETSTATUS", error);
          }
        }
      });
    } catch (final Throwable throwable) {
      if (promise != null) {
        promise.reject("E_AV_SETSTATUS", "Encountered an error while setting status!", throwable);
      }
    }
  }

  final int getClippedIntegerForValue(final Integer value, final Integer min, final Integer max) {
    return (min != null && value < min) ? min : (max != null && value > max) ? max : value;
  }

  abstract boolean isLoaded();

  abstract void getExtraStatusFields(final WritableMap map);

  // Sometimes another thread would release the player
  // in the middle of `getStatus()` call, which would result
  // in a null reference method invocation in `getExtraStatusFields`,
  // so we need to ensure nothing will release or nullify the property
  // while we get the latest status.
  public synchronized final WritableMap getStatus() {
    if (!isLoaded()) {
      final WritableMap map = getUnloadedStatus();
      map.putString(STATUS_ANDROID_IMPLEMENTATION_KEY_PATH, getImplementationName());
      return map;
    }

    final WritableMap map = Arguments.createMap();

    map.putBoolean(STATUS_IS_LOADED_KEY_PATH, true);
    map.putString(STATUS_ANDROID_IMPLEMENTATION_KEY_PATH, getImplementationName());

    map.putString(STATUS_URI_KEY_PATH, mUri.getPath());

    map.putInt(STATUS_PROGRESS_UPDATE_INTERVAL_MILLIS_KEY_PATH, mProgressUpdateIntervalMillis);
    // STATUS_DURATION_MILLIS_KEY_PATH, STATUS_POSITION_MILLIS_KEY_PATH,
    // and STATUS_PLAYABLE_DURATION_MILLIS_KEY_PATH are set in addExtraStatusFields().

    map.putBoolean(STATUS_SHOULD_PLAY_KEY_PATH, mShouldPlay);
    // STATUS_IS_PLAYING_KEY_PATH and STATUS_IS_BUFFERING_KEY_PATH are set
    // in addExtraStatusFields().

    map.putDouble(STATUS_RATE_KEY_PATH, mRate);
    map.putBoolean(STATUS_SHOULD_CORRECT_PITCH_KEY_PATH, mShouldCorrectPitch);
    map.putDouble(STATUS_VOLUME_KEY_PATH, mVolume);
    map.putBoolean(STATUS_IS_MUTED_KEY_PATH, mIsMuted);
    // STATUS_IS_LOOPING_KEY_PATH is set in addExtraStatusFields().

    map.putBoolean(STATUS_DID_JUST_FINISH_KEY_PATH, false);

    getExtraStatusFields(map);

    return map;
  }

  abstract int getAudioSessionId();

  // AudioEventHandler

  @Override
  public final void handleAudioFocusInterruptionBegan() {
    if (!mIsMuted) {
      pauseImmediately();
    }
  }

  @Override
  public final void handleAudioFocusGained() {
    try {
      playPlayerWithRateAndMuteIfNecessary();
    } catch (final AVModule.AudioFocusNotAcquiredException e) {
      // This is ok -- we might be paused or audio might have been disabled.
    }
  }

  @Override
  public final void onPause() {
    pauseImmediately();
  }

  @Override
  public final void onResume() {
    try {
      playPlayerWithRateAndMuteIfNecessary();
    } catch (final AVModule.AudioFocusNotAcquiredException e) {
      // Do nothing -- another app has audio focus for now, and handleAudioFocusGained() will be
      // called when it abandons it.
    }
  }

}
