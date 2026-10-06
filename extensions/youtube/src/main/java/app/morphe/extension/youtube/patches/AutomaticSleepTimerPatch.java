/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Calendar;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/** Pauses playback after a period without user interaction during configured hours. */
@SuppressWarnings("unused")
public final class AutomaticSleepTimerPatch {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static boolean playbackActive;
    private static boolean enabledPreviously;
    private static boolean pauseIssued;
    private static boolean pausePlaybackSent;
    private static long lastInteractionElapsedMs;
    private static long scheduleStartTimeMs = -1;
    private static Runnable pauseRunnable;

    private AutomaticSleepTimerPatch() {}

    /** Injection point. Called with YouTube's current player state. */
    public static void playerStatusChanged(Enum<?> status) {
        try {
            boolean playing = status != null && "VIDEO_PLAYING".equals(status.name());
            synchronized (AutomaticSleepTimerPatch.class) {
                playbackActive = playing;
            }
            updateTimer();
        } catch (Exception ex) {
            Logger.printException(() -> "Automatic sleep timer player status failure", ex);
        }
    }

    /** Injection point. Called about once per second while the video time is updated. */
    public static void videoTimeChanged(long ignoredVideoTime) {
        try {
            updateTimer();
        } catch (Exception ex) {
            Logger.printException(() -> "Automatic sleep timer video time failure", ex);
        }
    }

    /** Injection point. Called by YouTube's Activity whenever the user interacts with the app. */
    public static void userInteraction() {
        try {
            synchronized (AutomaticSleepTimerPatch.class) {
                if (!Settings.AUTO_SLEEP_TIMER_ENABLED.get()) return;
                long currentScheduleStart = getActiveScheduleStartTimeMs();
                if (currentScheduleStart < 0) return;
                if (scheduleStartTimeMs != currentScheduleStart) {
                    cancelTimer();
                    scheduleStartTimeMs = currentScheduleStart;
                    pauseIssued = false;
                    pausePlaybackSent = false;
                }
                lastInteractionElapsedMs = SystemClock.elapsedRealtime();
                pauseIssued = false;
                pausePlaybackSent = false;
                cancelTimer();
            }
            updateTimer();
        } catch (Exception ex) {
            Logger.printException(() -> "Automatic sleep timer interaction failure", ex);
        }
    }

    private static void updateTimer() {
        synchronized (AutomaticSleepTimerPatch.class) {
            if (!Settings.AUTO_SLEEP_TIMER_ENABLED.get()) {
                resetScheduleState();
                enabledPreviously = false;
                return;
            }

            long currentScheduleStart = getActiveScheduleStartTimeMs();
            if (currentScheduleStart < 0) {
                resetScheduleState();
                enabledPreviously = true;
                return;
            }

            if (!enabledPreviously || scheduleStartTimeMs != currentScheduleStart) {
                cancelTimer();
                scheduleStartTimeMs = currentScheduleStart;
                lastInteractionElapsedMs = SystemClock.elapsedRealtime();
                pauseIssued = false;
                pausePlaybackSent = false;
            }
            enabledPreviously = true;

            if (!playbackActive) return;

            if (lastInteractionElapsedMs == 0) {
                lastInteractionElapsedMs = SystemClock.elapsedRealtime();
            }

            if (pauseIssued) {
                issuePauseIfNeeded();
                return;
            }

            long remainingMs = getDurationMs() -
                    (SystemClock.elapsedRealtime() - lastInteractionElapsedMs);
            if (remainingMs <= 0) {
                pauseIssued = true;
                cancelTimer();
                issuePauseIfNeeded();
                return;
            }

            if (pauseRunnable == null) {
                pauseRunnable = () -> {
                    synchronized (AutomaticSleepTimerPatch.class) {
                        pauseRunnable = null;
                        if (!Settings.AUTO_SLEEP_TIMER_ENABLED.get()
                                || getActiveScheduleStartTimeMs() != scheduleStartTimeMs) {
                            updateTimer();
                            return;
                        }

                        long timeSinceInteraction = SystemClock.elapsedRealtime() - lastInteractionElapsedMs;
                        if (timeSinceInteraction < getDurationMs()) {
                            scheduleTimer(getDurationMs() - timeSinceInteraction);
                            return;
                        }

                        pauseIssued = true;
                        issuePauseIfNeeded();
                    }
                };
                scheduleTimer(remainingMs);
            }
        }
    }

    private static void scheduleTimer(long delayMs) {
        if (pauseRunnable != null) {
            MAIN_HANDLER.removeCallbacks(pauseRunnable);
        }
        MAIN_HANDLER.postDelayed(pauseRunnable, Math.max(1, delayMs));
    }

    private static void cancelTimer() {
        if (pauseRunnable != null) {
            MAIN_HANDLER.removeCallbacks(pauseRunnable);
            pauseRunnable = null;
        }
    }

    private static void resetScheduleState() {
        cancelTimer();
        scheduleStartTimeMs = -1;
        lastInteractionElapsedMs = 0;
        pauseIssued = false;
        pausePlaybackSent = false;
    }

    private static void pausePlayback() {
        // Defer the player call until after releasing the class monitor.
        MAIN_HANDLER.post(VideoInformation::pausePlayback);
    }

    private static void issuePauseIfNeeded() {
        if (!playbackActive || pausePlaybackSent) return;
        pausePlaybackSent = true;
        pausePlayback();
    }

    private static long getDurationMs() {
        int durationMinutes = Settings.AUTO_SLEEP_TIMER_DURATION.get();
        if (durationMinutes < 1 || durationMinutes > 1440) durationMinutes = 10;
        return durationMinutes * 60_000L;
    }

    private static long getActiveScheduleStartTimeMs() {
        int startMinute = parseTime(Settings.AUTO_SLEEP_TIMER_START.get(), 22 * 60);
        int endMinute = parseTime(Settings.AUTO_SLEEP_TIMER_END.get(), 5 * 60);
        if (startMinute == endMinute) return -1;

        Calendar now = Calendar.getInstance();
        int currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        boolean isActive;
        if (startMinute < endMinute) {
            isActive = currentMinute >= startMinute && currentMinute < endMinute;
        } else {
            // The active window crosses midnight, for example 22:00 to 05:00.
            isActive = currentMinute >= startMinute || currentMinute < endMinute;
        }
        if (!isActive) return -1;

        Calendar scheduleStart = (Calendar) now.clone();
        scheduleStart.set(Calendar.HOUR_OF_DAY, startMinute / 60);
        scheduleStart.set(Calendar.MINUTE, startMinute % 60);
        scheduleStart.set(Calendar.SECOND, 0);
        scheduleStart.set(Calendar.MILLISECOND, 0);
        if (startMinute > endMinute && currentMinute < endMinute) {
            scheduleStart.add(Calendar.DAY_OF_MONTH, -1);
        }
        return scheduleStart.getTimeInMillis();
    }

    private static int parseTime(String value, int fallback) {
        if (value == null) return fallback;

        try {
            if (value.length() != 5 || value.charAt(2) != ':') return fallback;
            int hour = Integer.parseInt(value.substring(0, 2));
            int minute = Integer.parseInt(value.substring(3, 5));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return fallback;
            return hour * 60 + minute;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
