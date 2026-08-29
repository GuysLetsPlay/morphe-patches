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
 * An ongoing livestream is detected two ways: either the reported duration grows
 * in real time (the duration of a regular video never changes while watching),
 * or for streams with a capped duration (24/7 streams, where the reported length
 * is the DVR window), by continuously playing at the live edge. Streams without
 * a seekable DVR window cannot be restored anyway. For detected livestreams the
 * last playback position is periodically saved and restored the next time the
 * same livestream is opened.
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
     * Delay between seek attempts when restoring.
     */
    private static final long RESTORE_SEEK_RETRY_DELAY_MS = 1000;

    /**
     * Maximum number of seek attempts. The player sometimes ignores very early
     * seek calls (while it is still loading), so the seek is retried until the
     * playback time matches the target.
     */
    private static final int RESTORE_SEEK_MAX_ATTEMPTS = 10;

    /**
     * How close the playback time must be to the target to consider a restore seek successful.
     */
    private static final long RESTORE_SEEK_TOLERANCE_MS = 3000;

    /**
     * Playback within this distance of the reported end of the video is considered
     * "at the live edge". Used to detect live streams with a capped duration
     * (24/7 streams), whose reported duration never grows.
     * <p>
     * The confirm time ({@link #LIVE_EDGE_CONFIRM_WHILE_WATCHING_MS}) must be
     * larger than this zone, so a regular video always ends before the live
     * stream confirmation can trigger and a VOD cannot be misdetected.
     */
    private static final long LIVE_EDGE_ZONE_MS = 35_000;

    /**
     * Playback time that must be spent advancing inside {@link #LIVE_EDGE_ZONE_MS}
     * to confirm a capped duration (24/7) livestream. A regular video always ends
     * before this much time can accumulate inside the live edge zone, so a VOD
     * can never be misdetected.
     */
    private static final long LIVE_EDGE_CONFIRM_WHILE_WATCHING_MS = 40_000;

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

    /**
     * True while a saved position is waiting to be restored for the current video.
     * While pending, saving is suppressed, otherwise the live playback position
     * would overwrite the saved position before it can be restored.
     */
    private static volatile boolean restorePending;

    /**
     * Wall clock time the user started continuously playing inside the live edge
     * zone. Zero when not currently in the zone. Used to detect live streams
     * with a capped duration (24/7 streams).
     */
    private static volatile long liveEdgeWatchStartMs;

    /**
     * Playback time of the previous videoTimeChanged call, used to detect
     * whether playback is actually advancing.
     */
    private static volatile long lastVideoTimeMs;

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
        final boolean settingEnabled = Settings.REMEMBER_LIVESTREAM_POSITION.get();
        final boolean resumeWhenLive = Settings.REMEMBER_LIVESTREAM_POSITION_RESUME_WHEN_LIVE.get();
        Logger.printInfo(() -> "RememberLivestream newVideoStarted id=" + VideoInformation.getVideoId()
                + " gen=" + (newVideoGeneration + 1)
                + " patchIncluded=" + isPatchIncluded()
                + " settingEnabled=" + settingEnabled
                + " resumeWhenLive=" + resumeWhenLive);

        baselineVideoLength = 0;
        livestreamConfirmed = false;
        lastSaveTime = 0;
        restoreAttempts = 0;
        restorePending = false;
        liveEdgeWatchStartMs = 0;
        lastVideoTimeMs = 0;
        newVideoGeneration++;

        if (!settingEnabled) {
            return;
        }

        restorePending = true;
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
            if (restorePending) {
                // A saved position is waiting to be restored. Do not save anything,
                // otherwise the live position would overwrite the position to restore.
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
                if (videoLength - baselineVideoLength >= LIVESTREAM_DURATION_GROWTH_WHILE_WATCHING_MS) {
                    livestreamConfirmed = true;
                    Logger.printInfo(() -> "RememberLivestream Detected ongoing livestream (growing duration) len=" + videoLength + " base=" + baselineVideoLength);
                } else {
                    // Streams with a capped duration (24/7 streams) never report a growing
                    // duration: the reported length is the DVR window (12 hours by default,
                    // 7 days with the Morphe 'Expand livestream DVR duration' setting).
                    // These are detected by playing at the live edge, which is where
                    // YouTube always joins a livestream. A regular video cannot be
                    // misdetected: playback always ends before the confirm time can
                    // accumulate inside the live edge zone.
                    final boolean advancing = playbackTimeMs > lastVideoTimeMs + 200;
                    lastVideoTimeMs = playbackTimeMs;

                    if (videoLength - playbackTimeMs <= LIVE_EDGE_ZONE_MS && advancing) {
                        final long nowMs = System.currentTimeMillis();
                        if (liveEdgeWatchStartMs == 0) {
                            liveEdgeWatchStartMs = nowMs;
                        } else if (nowMs - liveEdgeWatchStartMs >= LIVE_EDGE_CONFIRM_WHILE_WATCHING_MS) {
                            livestreamConfirmed = true;
                            Logger.printInfo(() -> "RememberLivestream Detected ongoing livestream (capped duration, 24/7) len=" + videoLength + " pos=" + playbackTimeMs);
                        }
                    } else {
                        liveEdgeWatchStartMs = 0;
                    }

                    if (!livestreamConfirmed) {
                        return;
                    }
                }
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
                // Nothing remembered for this video. Allow saving again.
                restorePending = false;
                return;
            }

            final long videoLength = VideoInformation.getVideoLength();
            if (videoLength <= 0) {
                // Player is not fully loaded yet.
                rescheduleOrGiveUp(generation);
                return;
            }

            // YouTube always joins a livestream at the live edge, while a regular video
            // starts at the beginning. Joining at the live edge confirms the saved video
            // is an ongoing livestream. This also covers streams with a capped duration
            // (24/7 streams), whose reported duration never grows.
            final long currentVideoTime = VideoInformation.getVideoTime();
            final boolean joinedAtLiveEdge = currentVideoTime > 0
                    && videoLength - currentVideoTime <= LIVE_EDGE_ZONE_MS;

            if (videoLength < saved.videoLength + LIVESTREAM_DURATION_GROWTH_ON_REOPEN_MS
                    && !joinedAtLiveEdge) {
                // Not yet confirmed the stream is still ongoing. Keep checking for a while.
                rescheduleOrGiveUp(generation);
                return;
            }

            // The stream is still ongoing and advanced since it was last watched.
            final boolean watchedAtLiveEdge = saved.videoLength - saved.position < LIVE_EDGE_THRESHOLD_MS;
            Logger.printInfo(() -> "RememberLivestream checkRestore id=" + videoId
                    + " savedPos=" + saved.position + " savedLen=" + saved.videoLength
                    + " curLen=" + videoLength + " liveEdge=" + watchedAtLiveEdge);

            if (!watchedAtLiveEdge || Settings.REMEMBER_LIVESTREAM_POSITION_RESUME_WHEN_LIVE.get()) {
                // Delete the saved position only after the seek is confirmed (or given up on).
                attemptRestoreSeek(generation, videoId, saved.position, 1);
            } else {
                // Watched at the live edge and resuming is disabled. Consume the position.
                Logger.printInfo(() -> "RememberLivestream was watched live, jumping to the live edge");
                restorePending = false;
                deletePlaybackPosition(videoId);
            }
        } catch (Exception ex) {
            restorePending = false;
            Logger.printException(() -> "checkRestore failure", ex);
        }
    }

    private static void attemptRestoreSeek(final long generation, final String videoId,
                                           final long targetPositionMs, final int attempt) {
        if (generation != newVideoGeneration) {
            return;
        }

        final boolean seekAccepted = VideoInformation.seekTo(targetPositionMs);
        Logger.printInfo(() -> "RememberLivestream restore attempt " + attempt + "/" + RESTORE_SEEK_MAX_ATTEMPTS
                + " target=" + targetPositionMs + " accepted=" + seekAccepted);

        Utils.runOnMainThreadDelayed(() -> {
            if (generation != newVideoGeneration) {
                return;
            }

            final long currentTime = VideoInformation.getVideoTime();
            if (Math.abs(currentTime - targetPositionMs) <= RESTORE_SEEK_TOLERANCE_MS) {
                Logger.printInfo(() -> "RememberLivestream Restored livestream playback position: " + currentTime);
                restorePending = false;
                deletePlaybackPosition(videoId);
                return;
            }

            if (attempt < RESTORE_SEEK_MAX_ATTEMPTS) {
                // Player may not be ready to seek yet. Try again.
                attemptRestoreSeek(generation, videoId, targetPositionMs, attempt + 1);
                return;
            }

            Logger.printInfo(() -> "RememberLivestream restore gave up. Current time: " + currentTime);
            restorePending = false;
            deletePlaybackPosition(videoId);
        }, RESTORE_SEEK_RETRY_DELAY_MS);
    }

    private static void rescheduleOrGiveUp(final long generation) {
        if (++restoreAttempts <= RESTORE_MAX_ATTEMPTS) {
            startRestoreCheck(generation);
            return;
        }

        Logger.printInfo(() -> "RememberLivestream restore polling gave up. videoId=" + VideoInformation.getVideoId()
                + " videoLength=" + VideoInformation.getVideoLength());
        restorePending = false;
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
        Logger.printInfo(() -> "RememberLivestream saved pos=" + positionMs + " len=" + videoLengthMs + " id=" + videoId);

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

        // Only the entries belonging to this patch are counted and trimmed.
        // The settings preferences file contains many unrelated entries,
        // so the total entry count cannot be used.
        String oldestKey = null;
        long oldestTimestamp = Long.MAX_VALUE;
        int savedCount = 0;
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (!entry.getKey().startsWith(STORAGE_KEY_PREFIX)) {
                continue;
            }

            final Object value = entry.getValue();
            final String[] parts = value instanceof String
                    ? ((String) value).split("\\|")
                    : null;
            boolean valid = parts != null && parts.length == 3;
            if (valid) {
                try {
                    final long timestamp = Long.parseLong(parts[2]);
                    if (timestamp < oldestTimestamp) {
                        oldestTimestamp = timestamp;
                        oldestKey = entry.getKey();
                    }
                } catch (NumberFormatException ex) {
                    valid = false;
                }
            }

            if (valid) {
                savedCount++;
            } else {
                // Corrupted entry. Delete it.
                preferences.edit().remove(entry.getKey()).apply();
            }
        }

        // Delete the oldest entries until below the limit.
        while (savedCount > MAX_SAVED_STREAMS && oldestKey != null) {
            preferences.edit().remove(oldestKey).apply();
            savedCount--;

            // Find the next oldest entry.
            oldestKey = null;
            oldestTimestamp = Long.MAX_VALUE;
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (!entry.getKey().startsWith(STORAGE_KEY_PREFIX) || !(entry.getValue() instanceof String)) {
                    continue;
                }
                try {
                    final String[] parts = ((String) entry.getValue()).split("\\|");
                    if (parts.length != 3) {
                        continue;
                    }
                    final long timestamp = Long.parseLong(parts[2]);
                    if (timestamp < oldestTimestamp) {
                        oldestTimestamp = timestamp;
                        oldestKey = entry.getKey();
                    }
                } catch (NumberFormatException ex) {
                    // Ignore. Will be cleaned up on the next trim.
                }
            }
        }
    }
}
