package app.morphe.extension.youtube.patches.playback.sleeptimer;

import static app.morphe.extension.shared.StringRef.str;

import android.view.MotionEvent;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Stops video playback after a user configurable amount of idle time.
 *
 * Intended for falling asleep while watching: without this the video keeps
 * playing and consuming data/battery all night if the user forgets to
 * manually start the regular (manual) sleep timer.
 *
 * The timer is always running while the feature setting is enabled:
 * every screen tap restarts it, so active watching is not interrupted.
 * If the user stops touching the screen (falls asleep), playback is paused
 * after the configured duration, just like the regular sleep timer.
 */
@SuppressWarnings("unused")
public final class AlwaysOnSleepTimerPatch {

    /**
     * Changed during patching.
     */
    private static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Small delay before pausing, so the pause happens outside of the
     * player time update callback.
     */
    private static final long PAUSE_DELAY_MILLISECONDS = 100;

    /**
     * Wall clock time of the last user interaction (screen tap or video change).
     */
    private static volatile long lastUserInteractionTime = System.currentTimeMillis();

    /**
     * Incremented on every new video, used to invalidate pending pause jobs
     * that were scheduled for a previous video.
     */
    private static volatile long timerGeneration = 0;

    /**
     * Cached timer duration in milliseconds. Re-read from the settings when zero.
     */
    private static volatile long cachedDurationMs = 0;

    private static long getDurationMs() {
        long durationMs = cachedDurationMs;
        if (durationMs <= 0) {
            durationMs = Settings.ALWAYS_ON_SLEEP_TIMER_DURATION.get() * 60_000;
            if (durationMs <= 0) {
                // User cleared the setting. Use a sane fallback instead of pausing instantly.
                durationMs = 20 * 60_000;
            }
            cachedDurationMs = durationMs;
        }
        return durationMs;
    }

    /**
     * Records user presence and restarts the idle timer.
     */
    private static void touchUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis();
    }

    /**
     * Injection point. Called when a new video starts playing.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        try {
            Logger.printDebug(() -> "SleepTimer newVideoStarted");
            // Invalidate duration cache so setting changes are picked up on the next video.
            cachedDurationMs = 0;
            timerGeneration++;
            touchUserInteraction();
        } catch (Exception ex) {
            Logger.printException(() -> "SleepTimer newVideoStarted failure", ex);
        }
    }

    /**
     * Injection point. Called on every touch event on the watch layout.
     *
     * @param motionEvent The touch event (unused, only here to match the hooked method signature).
     */
    public static void userTouchedScreen(MotionEvent motionEvent) {
        try {
            if (!Settings.ALWAYS_ON_SLEEP_TIMER.get()) {
                return;
            }
            touchUserInteraction();
            Logger.printDebug(() -> "SleepTimer user interaction, timer restarted");
        } catch (Exception ex) {
            Logger.printException(() -> "SleepTimer userTouchedScreen failure", ex);
        }
    }

    /**
     * Injection point. Called approximately once per second during playback.
     */
    public static void videoTimeChanged(long playbackTimeMs) {
        try {
            if (!Settings.ALWAYS_ON_SLEEP_TIMER.get()) {
                return;
            }
            if (playbackTimeMs <= 0) {
                return;
            }

            final long now = System.currentTimeMillis();
            if (now - lastUserInteractionTime >= getDurationMs()) {
                touchUserInteraction();
                schedulePause(timerGeneration);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "SleepTimer videoTimeChanged failure", ex);
        }
    }

    /**
     * Pauses playback on the main thread, mimicking what the regular sleep timer does.
     */
    private static void schedulePause(final long generation) {
        if (generation != timerGeneration) {
            return;
        }

        Utils.runOnMainThreadDelayed(() -> {
            if (generation != timerGeneration ||
                    !Settings.ALWAYS_ON_SLEEP_TIMER.get()) {
                return;
            }

            Logger.printDebug(() -> "SleepTimer timer expired, pausing video");
            VideoInformation.pauseVideo();
            Utils.showToastShort(str("morphe_always_on_sleep_timer_stopped_toast"));
        }, PAUSE_DELAY_MILLISECONDS);
    }
}