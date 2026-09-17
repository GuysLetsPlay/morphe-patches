/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.playback.quality;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import kotlin.Unit;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.VideoInformation.*;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import j$.util.Optional;

@SuppressWarnings({"rawtypes", "unused"})
public class RememberVideoQualityPatch {

    private static final IntegerSetting videoQualityWifi = Settings.VIDEO_QUALITY_DEFAULT_WIFI;
    private static final IntegerSetting videoQualityMobile = Settings.VIDEO_QUALITY_DEFAULT_MOBILE;
    private static final IntegerSetting shortsQualityWifi = Settings.SHORTS_QUALITY_DEFAULT_WIFI;
    private static final IntegerSetting shortsQualityMobile = Settings.SHORTS_QUALITY_DEFAULT_MOBILE;

    private static final Object networkCallbackLock = new Object();
    private static volatile ConnectivityManager networkManager;
    /**
     * Network type of the default network, tracked using a system network callback.
     * This is updated the moment the OS switches the default network, which can be
     * noticeably earlier than {@link Utils#getNetworkType()} (and YouTube's internal
     * state) reflects the change. Null until the callback first fires.
     */
    private static volatile Boolean currentNetworkIsMobile;

    private static boolean isMobileNetwork() {
        final Boolean tracked = currentNetworkIsMobile;
        if (tracked != null) {
            return tracked;
        }
        return Utils.getNetworkType() == Utils.NetworkType.MOBILE;
    }

    public static boolean shouldRememberVideoQuality() {
        BooleanSetting preference = ShortsPlayerState.isOpen()
                ? Settings.REMEMBER_SHORTS_QUALITY_LAST_SELECTED
                : Settings.REMEMBER_VIDEO_QUALITY_LAST_SELECTED;
        return preference.get();
    }

    public static int getDefaultQualityResolution() {
        final boolean isShorts = ShortsPlayerState.isOpen();
        IntegerSetting preference = isMobileNetwork()
                ? (isShorts ? shortsQualityMobile : videoQualityMobile)
                : (isShorts ? shortsQualityWifi : videoQualityWifi);
        return preference.get();
    }

    public static void saveDefaultQuality(int qualityResolution) {
        final boolean shortPlayerOpen = ShortsPlayerState.isOpen();
        final boolean isMobile = isMobileNetwork();
        IntegerSetting qualitySetting;
        if (isMobile) {
            qualitySetting = shortPlayerOpen ? shortsQualityMobile : videoQualityMobile;
        } else {
            qualitySetting = shortPlayerOpen ? shortsQualityWifi : videoQualityWifi;
        }

        if (qualitySetting.get() == qualityResolution) {
            // User clicked the same video quality as the current video,
            // or changed between 1080p Premium and non-Premium.
            return;
        }
        qualitySetting.save(qualityResolution);

        if (Settings.REMEMBER_VIDEO_QUALITY_LAST_SELECTED_TOAST.get()) {
            String qualityLabel = qualityResolution + "p";
            final String toastStringId = getString(shortPlayerOpen, isMobile);
            Utils.showToastShort(str(toastStringId, qualityLabel));
        }
    }

    @NonNull
    private static String getString(boolean shortPlayerOpen, boolean isMobile) {
        final String toastStringId;
        if (shortPlayerOpen && isMobile) {
            toastStringId = "morphe_remember_video_quality_toast_shorts_mobile";
        } else if (shortPlayerOpen) {
            toastStringId = "morphe_remember_video_quality_toast_shorts_wifi";
        } else if (isMobile) {
            toastStringId = "morphe_remember_video_quality_toast_mobile";
        } else {
            toastStringId = "morphe_remember_video_quality_toast_wifi";
        }
        return toastStringId;
    }

    /**
     * Injection point.
     * <p>
     * Overrides the initial video quality to not follow the 'Video quality preferences' in YouTube settings.
     * (e.g. 'Auto (recommended)' - 360p/480p, 'Higher picture quality' - 720p/1080p...)
     * If the maximum video quality available is 1080p and the default video quality is 2160p,
     * 1080p is used as an initial video quality.
     * <p>
     * Called before {@link #newVideoStarted(VideoInformation.PlaybackController)}.
     */
    public static Optional getInitialVideoQuality(Optional optional) {
        int preferredQuality = getDefaultQualityResolution();
        if (preferredQuality != VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE) {
            Logger.printDebug(() -> "initialVideoQuality: " + preferredQuality);
            return Optional.of(preferredQuality);
        }
        return optional;
    }

    /**
     * Injection point.
     * @param userSelectedQualityIndex Element index of {@link VideoInformation#getCurrentQualities()}.
     */
    public static void userChangedShortsQuality(int userSelectedQualityIndex) {
        try {
            if (shouldRememberVideoQuality()) {
                VideoQualityInterface[] currentQualities = VideoInformation.getCurrentQualities();
                if (currentQualities == null) {
                    Logger.printDebug(() -> "Cannot save default quality, qualities is null");
                    return;
                }
                VideoQualityInterface quality = currentQualities[userSelectedQualityIndex];
                saveDefaultQuality(quality.patch_getResolution());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "userChangedShortsQuality failure", ex);
        }
    }

    /**
     * Injection point.  Regular videos.
     * @param videoResolution Human-readable resolution: 480, 720, 1080.
     */
    public static void userChangedQuality(int videoResolution) {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "User changed quality to: " + videoResolution);

        if (shouldRememberVideoQuality() && !VideoInformation.programmaticQualityChangeRecently()) {
            saveDefaultQuality(videoResolution);
        }
    }

    /**
     * Injection point.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        ensureNetworkAndPlayerHooks();
        VideoInformation.setDesiredVideoResolution(getDefaultQualityResolution());
        // Re-check shortly after the video starts. If the network type changed immediately
        // before playback, the initially chosen quality (and the network type reported to
        // YouTube) can be stale.
        new Handler(Looper.getMainLooper()).postDelayed(
                RememberVideoQualityPatch::applyDefaultQualityAfterNetworkOrPlayerChange, 1500);
    }

    private static void ensureNetworkAndPlayerHooks() {
        if (networkManager != null) {
            return;
        }
        synchronized (networkCallbackLock) {
            if (networkManager != null) {
                return;
            }
            try {
                Context context = Utils.getContext();
                if (context == null) {
                    return;
                }
                ConnectivityManager connectivityManager = (ConnectivityManager)
                        context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (connectivityManager == null) {
                    return;
                }

                // Track the default network, so quality defaults always use the
                // current network type, even if it changed moments ago.
                ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onCapabilitiesChanged(@NonNull Network network,
                                                      @NonNull NetworkCapabilities capabilities) {
                        try {
                            final boolean isMobile = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                            final Boolean previous = currentNetworkIsMobile;
                            if (previous != null && previous != isMobile) {
                                Logger.printDebug(() -> "Network type changed to: "
                                        + (isMobile ? "mobile" : "not mobile"));
                                Utils.runOnMainThread(
                                        RememberVideoQualityPatch::applyDefaultQualityAfterNetworkOrPlayerChange);
                            }
                            currentNetworkIsMobile = isMobile;
                        } catch (Exception ex) {
                            Logger.printException(() -> "Network capabilities change failure", ex);
                        }
                    }
                };
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
                networkManager = connectivityManager;

                // If the network changed while the player was closed or hidden, the quality
                // was never applied. Re-apply the default whenever the player becomes active.
                PlayerType.getOnChange().addObserver((PlayerType type) -> {
                    try {
                        if (!type.isNoneOrHidden()) {
                            Utils.runOnMainThread(
                                    RememberVideoQualityPatch::applyDefaultQualityAfterNetworkOrPlayerChange);
                        }
                    } catch (Exception ex) {
                        Logger.printException(() -> "Player type change failure", ex);
                    }
                    return Unit.INSTANCE;
                });

                Logger.printDebug(() -> "Registered network and player change hooks");
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to register network and player change hooks", ex);
            }
        }
    }

    private static void applyDefaultQualityAfterNetworkOrPlayerChange() {
        try {
            if (!Settings.APPLY_DEFAULT_QUALITY_ON_NETWORK_CHANGE.get()) {
                return;
            }
            if (ShortsPlayerState.isOpen()) {
                return; // Shorts switching is not handled, because it would restart the Short.
            }
            PlayerType playerType = PlayerType.getCurrent();
            if (playerType.isNoneOrHidden() || playerType == PlayerType.INLINE_MINIMAL) {
                return; // No active video. The next video start applies the default quality.
            }
            if (VideoInformation.getPlayerResponseVideoId().isEmpty()) {
                return; // No video loaded.
            }

            IntegerSetting preference = isMobileNetwork()
                    ? videoQualityMobile
                    : videoQualityWifi;
            final int newDefaultQuality = preference.get();
            if (newDefaultQuality == VideoInformation.getDesiredVideoResolution()) {
                return; // Desired quality is already correct.
            }

            Logger.printDebug(() -> "Network or player changed, applying default quality: " + newDefaultQuality);
            VideoInformation.setDesiredVideoResolution(newDefaultQuality);
            VideoInformation.applyPreferredQualityToCurrentVideo();
        } catch (Exception ex) {
            Logger.printException(() -> "applyDefaultQualityAfterNetworkOrPlayerChange failure", ex);
        }
    }
}
