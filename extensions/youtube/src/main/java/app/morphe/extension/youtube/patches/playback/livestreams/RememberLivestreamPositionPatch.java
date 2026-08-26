/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.playback.livestreams;

import android.content.SharedPreferences;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Remembers the playback position of ongoing livestreams.
 * <p>
 * An ongoing livestream is detected by its reported duration growing in real time
 * (the duration of a regular video never changes while watching). For detected
 * livestreams the last playback position is periodically saved and restored the
 * next time the same livestream is opened.
 */
@SuppressWarnings("unused")
public final class RememberLivestreamPositionPatch {

    /**
     * Minimum duration growth to confirm a video is an ongoing livestream while watching.
     */
    private static final long LIVESTREAM_DURATION_GROWTH_WHILE_WATCHING_MS = 3000;

    /**
     * Minimum duration growth (compared to the saved value) to confirm the stream
     * is still ongoing when reopening it.
     */
    private static final long LIVESTREAM_DURATION_GROWTH_ON_REOPEN_MS = 1000;

    /**
     * If the saved position is closer than this to the end of the stream at save time,
     * the livestream was being watched at the live edge.
     */
    private static final long LIVE_EDGE_THRESHOLD_MS = 30_000;

    /**
     * How often the playback position is saved while watching an ongoing livestream.
     */
    private static final long SAVE_INTERVAL_MS = 5000;

    /**
     * Delay between checks when trying to restore a saved position.
     */
    private static final long RESTORE_POLL_DELAY_MS = 500;

    /**
     * Maximum number of restore checks. Roughly 20 seconds, which is enough time
     * for the duration to grow past the saved value after quickly closing and reopening.
     */
    private static final int RESTORE_MAX_ATTEMPTS = 40;

    /**
     * Maximum number of saved streams kept. Prevents unbounded growth of the settings storage.
     */
    private static final int MAX_SAVED_STREAMS = 100;

    private static final String STORAGE_KEY_PREFIX = "morphe_livestream_playback_position_";

    /**
     * Changed during patching.
     */
    private static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Duration of the current video when first observed. Zero if not yet observed.
     */
    private static volatile long baselineVideoLength;

    /**
     * True if the current video was confirmed to be an ongoing livestream.
     */
    private static volatile boolean livestreamConfirmed;

    private static long lastSaveTime;

    /**
     * Incremented on every new video, used to cancel pending restore checks.
     */
    private static volatile long newVideoGeneration;

    private static int restoreAttempts;

    private static final class SavedPosition {
        final long position;
        final long videoLength;
        final long timestamp;

        SavedPosition(long position, long videoLength, long timestamp) {
            this.position = position;
            this.videoLength = videoLength;
            this.timestamp = timestamp;
        }
    }

    /**
     * Injection point.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        baselineVideoLength = 0;
        livestreamConfirmed = false;
        lastSaveTime = 0;
        restoreAttempts = 0;
        newVideoGeneration++;

        if (!Settings.REMEMBER_LIVESTREAM_POSITION.get()) {
            return;
        }

        startRestoreCheck(newVideoGeneration);
    }

    /**
     * Injection point. Called approximately once per second during playback.
     */
    public static void videoTimeChanged(long playbackTimeMs) {
        try {
            if (!Settings.REMEMBER_LIVESTREAM_POSITION.get()) {
                return;
            }
            if (playbackTimeMs <= 0) {
                return;
            }

            final long videoLength = VideoInformation.getVideoLength();
            if (videoLength <= 0) {
                // Player is not fully loaded yet.
                return;
            }

            if (baselineVideoLength == 0) {
                baselineVideoLength = videoLength;
                return;
            }

            if (!livestreamConfirmed) {
                // The duration of an ongoing livestream grows in real time,
                // while the duration of a regular video never changes.
                if (videoLength - baselineVideoLength < LIVESTREAM_DURATION_GROWTH_WHILE_WATCHING_MS) {
                    return;
                }
                livestreamConfirmed = true;
                Logger.printDebug(() -> "Detected ongoing livestream");
            }

            final long now = System.currentTimeMillis();
            if (now - lastSaveTime < SAVE_INTERVAL_MS) {
                return;
            }
            lastSaveTime = now;

            savePlaybackPosition(VideoInformation.getVideoId(), playbackTimeMs, videoLength);
        } catch (Exception ex) {
            Logger.printException(() -> "videoTimeChanged failure", ex);
        }
    }

    private static void startRestoreCheck(final long generation) {
        Utils.runOnMainThreadDelayed(() -> checkRestore(generation), RESTORE_POLL_DELAY_MS);
    }

    private static void checkRestore(final long generation) {
        try {
            if (generation != newVideoGeneration) {
                // Another video started meanwhile.
                return;
            }

            final String videoId = VideoInformation.getVideoId();
            if (videoId.isEmpty()) {
                // Video id not available yet.
                rescheduleOrGiveUp(generation);
                return;
            }

            final SavedPosition saved = loadPlaybackPosition(videoId);
            if (saved == null) {
                // Nothing remembered for this video.
                return;
            }

            final long videoLength = VideoInformation.getVideoLength();
            if (videoLength <= 0 || videoLength < saved.videoLength + LIVESTREAM_DURATION_GROWTH_ON_REOPEN_MS) {
                // The duration has not yet grown beyond the saved value, so it is not yet
                // confirmed the stream is still ongoing. Keep checking for a while.
                rescheduleOrGiveUp(generation);
                return;
            }

            // The stream is still ongoing and advanced since it was last watched.
            final boolean watchedAtLiveEdge = saved.videoLength - saved.position < LIVE_EDGE_THRESHOLD_MS;
            if (!watchedAtLiveEdge || Settings.REMEMBER_LIVESTREAM_POSITION_RESUME_WHEN_LIVE.get()) {
                Logger.printDebug(() -> "Restoring livestream playback position: " + saved.position);
                VideoInformation.seekTo(saved.position);
            } else {
                Logger.printDebug(() -> "Livestream was watched live, jumping to the live edge");
            }

            // Position has been consumed. A fresh position will be saved while watching.
            deletePlaybackPosition(videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "checkRestore failure", ex);
        }
    }

    private static void rescheduleOrGiveUp(final long generation) {
        if (++restoreAttempts <= RESTORE_MAX_ATTEMPTS) {
            startRestoreCheck(generation);
        }
    }

    private static SharedPreferences preferences() {
        return Setting.preferences.preferences;
    }

    private static void savePlaybackPosition(String videoId, long positionMs, long videoLengthMs) {
        if (videoId.isEmpty()) {
            return;
        }

        final SharedPreferences preferences = preferences();
        preferences.edit()
                .putString(STORAGE_KEY_PREFIX + videoId,
                        positionMs + "|" + videoLengthMs + "|" + System.currentTimeMillis())
                .apply();

        trimSavedPositions(preferences);
    }

    private static SavedPosition loadPlaybackPosition(String videoId) {
        final String encoded = preferences().getString(STORAGE_KEY_PREFIX + videoId, null);
        if (encoded == null) {
            return null;
        }

        final String[] parts = encoded.split("\\|");
        if (parts.length != 3) {
            deletePlaybackPosition(videoId);
            return null;
        }

        try {
            return new SavedPosition(Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (NumberFormatException ex) {
            deletePlaybackPosition(videoId);
            return null;
        }
    }

    private static void deletePlaybackPosition(String videoId) {
        preferences().edit().remove(STORAGE_KEY_PREFIX + videoId).apply();
    }

    private static void trimSavedPositions(SharedPreferences preferences) {
        final Map<String, ?> all = preferences.getAll();
        if (all.size() <= MAX_SAVED_STREAMS) {
            return;
        }

        // Delete the oldest entries until below the limit.
        while (all.size() > MAX_SAVED_STREAMS) {
            String oldestKey = null;
            long oldestTimestamp = Long.MAX_VALUE;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (!entry.getKey().startsWith(STORAGE_KEY_PREFIX)) {
                    continue;
                }
                final Object value = entry.getValue();
                if (!(value instanceof String)) {
                    continue;
                }
                final String[] parts = ((String) value).split("\\|");
                if (parts.length != 3) {
                    oldestKey = entry.getKey();
                    break;
                }
                try {
                    final long timestamp = Long.parseLong(parts[2]);
                    if (timestamp < oldestTimestamp) {
                        oldestTimestamp = timestamp;
                        oldestKey = entry.getKey();
                    }
                } catch (NumberFormatException ex) {
                    oldestKey = entry.getKey();
                    break;
                }
            }

            if (oldestKey == null) {
                return;
            }
            preferences.edit().remove(oldestKey).apply();
            all.remove(oldestKey);
        }
    }
}
