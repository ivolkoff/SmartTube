package com.liskovsoft.smartyoutubetv2.common.vox;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.vot.TranslationAudioPlayer;
import com.liskovsoft.smartyoutubetv2.common.vot.VotAudioSource;
import com.liskovsoft.smartyoutubetv2.common.vot.VotClient;
import com.liskovsoft.smartyoutubetv2.common.vot.VotMediaFormatSelector;
import com.liskovsoft.smartyoutubetv2.common.vot.VotProgress;
import com.liskovsoft.smartyoutubetv2.common.vot.VotYouTubeAudioSource;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;

/**
 * Yandex voice-over translation: plays the Russian track in a second player over the video,
 * keeps it in sync with the main player and ducks the original audio.
 */
public class VoxTranslateController extends BasePlayerController {
    private static final String TAG = VoxTranslateController.class.getSimpleName();
    private static final int ACTION_ID = R.id.action_vox_translate;
    private static final long SYNC_INTERVAL_MS = 2_000;
    private static final long MAX_DRIFT_MS = 400;
    private static final float[] ORIGINAL_VOLUMES = {0f, 0.15f, 0.3f, 0.5f};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSync = this::syncTick;
    private VotClient mClient;
    private TranslationAudioPlayer mTranslationPlayer;
    private Disposable mRequest;
    private Disposable mLogin;
    private int mSessionId;
    /** User wants translation: survives switching to the next video. */
    private boolean mArmed;
    private boolean mTranslationStarted;
    private boolean mDucked;
    private float mSavedVolume = 1f;
    private int mLastShownEta = -1;
    private boolean mUsedLively;

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId != ACTION_ID) {
            return;
        }
        mArmed = buttonState != PlayerUI.BUTTON_ON;
        if (mArmed) {
            start();
        } else {
            stop();
        }
    }

    @Override
    public void onButtonLongClicked(int buttonId, int buttonState) {
        if (buttonId == ACTION_ID) {
            showSettings();
        }
    }

    @Override
    public void onNewVideo(Video item) {
        stop();
    }

    @Override
    public void onVideoLoaded(Video item) {
        if (mArmed) {
            if (mTranslationStarted) {
                duck(); // VideoStateController resets the volume on every load
            } else if (mRequest == null) {
                start();
            }
        }
    }

    @Override
    public void onPlay() {
        if (mTranslationStarted) {
            mTranslationPlayer.seekTo(getPlayer().getPositionMs());
            mTranslationPlayer.resume();
        }
    }

    @Override
    public void onPause() {
        if (mTranslationPlayer != null) {
            mTranslationPlayer.pause();
        }
    }

    @Override
    public void onSeekEnd() {
        if (mTranslationStarted) {
            mTranslationPlayer.seekTo(getPlayer().getPositionMs());
        }
    }

    @Override
    public void onSpeedChanged(float speed) {
        if (mTranslationPlayer != null) {
            mTranslationPlayer.setPlaybackSpeed(speed);
        }
    }

    @Override
    public void onEngineReleased() {
        stop();
    }

    @Override
    public void onFinish() {
        mArmed = false;
        stop();
        if (mLogin != null) {
            mLogin.dispose();
        }
    }

    private void start() {
        Video video = getVideo();
        if (getPlayer() == null || video == null || video.videoId == null || video.isLive) {
            return;
        }
        stop();
        setButton(true);
        MessageHelpers.showMessage(getContext(), R.string.vox_requesting);

        if (mClient == null) {
            mClient = new VotClient(getContext());
        }
        String videoId = video.videoId;
        String url = "https://www.youtube.com/watch?v=" + videoId;
        long durationSec = Math.max(1, getPlayer().getDurationMs() / 1000);
        int sessionId = ++mSessionId;
        mUsedLively = VoxPrefs.instance(getContext()).useLively();
        Log.i(TAG, "Translation requested: video=%s, duration=%ds, lively=%b", videoId, durationSec, mUsedLively);

        mRequest = mClient.observeTranslation(url, durationSec, false, requestedUrl -> getAudioSource(videoId))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        progress -> onProgress(sessionId, progress),
                        error -> onFailed(sessionId, error.getClass().getSimpleName())
                );
    }

    /** Yandex asks for the original audio of videos it has not translated yet. Runs on an io thread. */
    @Nullable
    private static VotAudioSource getAudioSource(String videoId) {
        MediaItemFormatInfo info = YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(videoId);
        MediaFormat best = info != null ? VotMediaFormatSelector.selectBestAudioFormat(info.getAdaptiveFormats()) : null;
        if (best == null) {
            Log.w(TAG, "No audio format to upload for %s", videoId);
            return null;
        }
        Log.i(TAG, "Uploading audio: %s", VotMediaFormatSelector.safeFormatDescription(best));
        return VotYouTubeAudioSource.fromMediaFormat(best);
    }

    private void onProgress(int sessionId, VotProgress progress) {
        if (sessionId != mSessionId) {
            return;
        }
        switch (progress.type) {
            case VotProgress.TYPE_WAITING:
                Log.i(TAG, "Translation waiting: eta=%ds, status=%d", progress.remainingTimeSec, progress.status);
                if (progress.remainingTimeSec > 0 && progress.remainingTimeSec != mLastShownEta) {
                    mLastShownEta = progress.remainingTimeSec;
                    MessageHelpers.showMessage(getContext(), R.string.vox_waiting, progress.remainingTimeSec);
                }
                break;
            case VotProgress.TYPE_LIVELY_FALLBACK:
                mUsedLively = false;
                MessageHelpers.showMessage(getContext(), R.string.vox_lively_fallback);
                break;
            case VotProgress.TYPE_READY:
                Log.i(TAG, "Translation ready, lively=%b", mUsedLively);
                mRequest = null;
                play(sessionId, progress.audioUrl);
                break;
            case VotProgress.TYPE_FAILED:
                onFailed(sessionId, progress.message);
                break;
        }
    }

    private void onFailed(int sessionId, String reason) {
        if (sessionId != mSessionId) {
            return;
        }
        Log.e(TAG, "Translation failed: %s", reason);
        if (VotClient.ERROR_MARKER_AUTH_REJECTED.equals(reason)) {
            MessageHelpers.showLongMessage(getContext(), R.string.vox_auth_rejected);
        } else {
            MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.vox_error, reason));
        }
        mArmed = false;
        stop();
    }

    private void play(int sessionId, String audioUrl) {
        if (getPlayer() == null) {
            return;
        }
        mTranslationPlayer = new TranslationAudioPlayer(getContext());
        mTranslationPlayer.prepare(sessionId, audioUrl, getPlayer().getSpeed(), new TranslationAudioPlayer.PlaybackCallback() {
            @Override
            public void onPrepared() {
                if (sessionId == mSessionId && getPlayer() != null) {
                    mTranslationPlayer.seekTo(getPlayer().getPositionMs());
                }
            }

            @Override
            public void onSeekProcessed() {
                if (sessionId != mSessionId || mTranslationStarted || getPlayer() == null) {
                    return;
                }
                mTranslationStarted = true;
                mTranslationPlayer.startPlayback(1f);
                if (!getPlayer().isPlaying()) {
                    mTranslationPlayer.pause();
                }
                duck();
                mHandler.postDelayed(mSync, SYNC_INTERVAL_MS);
                MessageHelpers.showMessage(getContext(), mUsedLively ? R.string.vox_ready_lively : R.string.vox_ready);
            }

            @Override
            public void onError(Exception error) {
                onFailed(sessionId, error != null ? error.getClass().getSimpleName() : "player");
            }
        });
    }

    private void syncTick() {
        if (!mTranslationStarted || getPlayer() == null) {
            return;
        }
        boolean mainPlaying = getPlayer().isPlaying();
        if (mainPlaying != mTranslationPlayer.isPlaying()) {
            if (mainPlaying) {
                onPlay();
            } else {
                mTranslationPlayer.pause();
            }
        } else if (mainPlaying) {
            long mainPos = getPlayer().getPositionMs();
            if (Math.abs(mTranslationPlayer.getPositionMs() - mainPos) > MAX_DRIFT_MS) {
                Log.d(TAG, "Drift fix: main=%d, translation=%d", mainPos, mTranslationPlayer.getPositionMs());
                mTranslationPlayer.seekTo(mainPos);
            }
        }
        mHandler.postDelayed(mSync, SYNC_INTERVAL_MS);
    }

    private void stop() {
        mSessionId++;
        mHandler.removeCallbacks(mSync);
        if (mRequest != null) {
            mRequest.dispose();
            mRequest = null;
        }
        if (mClient != null) {
            mClient.cancelActiveAudioUpload();
        }
        if (mTranslationPlayer != null) {
            mTranslationPlayer.release();
            mTranslationPlayer = null;
        }
        mTranslationStarted = false;
        mLastShownEta = -1;
        restoreVolume();
        setButton(mArmed && getVideo() != null);
    }

    private void duck() {
        if (getPlayer() == null) {
            return;
        }
        if (!mDucked) {
            mSavedVolume = getPlayer().getVolume();
            mDucked = true;
        }
        getPlayer().setVolume(mSavedVolume * VoxPrefs.instance(getContext()).getOriginalVolume());
    }

    private void restoreVolume() {
        if (mDucked && getPlayer() != null) {
            getPlayer().setVolume(mSavedVolume);
        }
        mDucked = false;
    }

    private void setButton(boolean on) {
        if (getPlayer() != null) {
            getPlayer().setButtonState(ACTION_ID, on ? PlayerUI.BUTTON_ON : PlayerUI.BUTTON_OFF);
        }
    }

    private void showSettings() {
        VoxPrefs prefs = VoxPrefs.instance(getContext());
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());

        dialog.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.vox_lively),
                option -> prefs.setLivelyEnabled(option.isSelected()), prefs.isLivelyEnabled()));

        boolean signedIn = prefs.getOAuthToken() != null;
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(signedIn ? R.string.vox_logout : R.string.vox_login),
                option -> {
                    dialog.closeDialog();
                    if (signedIn) {
                        prefs.setOAuthToken(null);
                    } else {
                        login();
                    }
                }));

        List<OptionItem> volumes = new ArrayList<>();
        for (float volume : ORIGINAL_VOLUMES) {
            volumes.add(UiOptionItem.from(Math.round(volume * 100) + "%", option -> {
                prefs.setOriginalVolume(volume);
                if (mDucked) {
                    duck();
                }
            }, Math.abs(prefs.getOriginalVolume() - volume) < 0.01f));
        }
        dialog.appendRadioCategory(getContext().getString(R.string.vox_original_volume), volumes);

        dialog.showDialog(getContext().getString(R.string.vox_settings));
    }

    private void login() {
        if (!VoxYandexAuth.isConfigured()) {
            MessageHelpers.showLongMessage(getContext(), R.string.vox_login_no_app);
            return;
        }
        if (mLogin != null) {
            mLogin.dispose();
        }
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        mLogin = VoxYandexAuth.requestDeviceCode()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(code -> {
                    dialog.appendSingleButton(UiOptionItem.from(
                            getContext().getString(R.string.vox_login_code, code.verificationUrl, code.userCode),
                            option -> dialog.closeDialog()));
                    // Closing the dialog cancels polling
                    dialog.setOnFinish(() -> {
                        if (mLogin != null) {
                            mLogin.dispose();
                        }
                    });
                    dialog.showDialog(getContext().getString(R.string.vox_login_title));
                })
                .observeOn(io.reactivex.schedulers.Schedulers.io())
                .concatMap(VoxYandexAuth::pollToken)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(token -> {
                    VoxPrefs.instance(getContext()).setOAuthToken(token);
                    dialog.closeDialog();
                    MessageHelpers.showLongMessage(getContext(), R.string.vox_login_ok);
                }, error -> {
                    Log.e(TAG, "Yandex login failed: %s", error.getMessage());
                    MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.vox_login_failed, error.getMessage()));
                });
    }
}
