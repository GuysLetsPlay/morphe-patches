/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.BaseSettings;
import kotlin.Pair;

public class CreatePlaylistRequest {
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000;

    public static final Map<String, CreatePlaylistRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private final Future<Pair<String, Map<String, String>>> future;

    private CreatePlaylistRequest(List<String> videoIds, Map<String, String> requestHeader) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoIds, requestHeader));
    }

    @Nullable
    public Pair<String, String> getPlaylistId() {
        Pair<String, Map<String, String>> result = getPlaylistItems();
        if (result == null || result.getSecond().isEmpty()) {
            return null;
        }
        return new Pair<>(result.getFirst(), result.getSecond().values().iterator().next());
    }

    @Nullable
    public Pair<String, Map<String, String>> getPlaylistItems() {
        try {
            if (BaseSettings.DEBUG.get() && !future.isDone() && Utils.isCurrentlyOnMainThread()) {
                Logger.printException(() -> "Debug: Blocking main thread");
            }
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getPlaylistId timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getPlaylistId interrupted", ex);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getPlaylistId failure", ex);
        }
        return null;
    }

    public static void clear() {
        cache.clear();
    }

    public static void fetchRequestIfNeeded(String videoId, Map<String, String> requestHeader) {
        fetchRequestIfNeeded(List.of(Objects.requireNonNull(videoId)), requestHeader);
    }

    public static void fetchRequestIfNeeded(List<String> videoIds, Map<String, String> requestHeader) {
        if (videoIds.isEmpty()) {
            return;
        }
        List<String> safeVideoIds = List.copyOf(videoIds);
        String cacheKey = getCacheKey(safeVideoIds);
        cache.computeIfAbsent(
                cacheKey,
                k -> new CreatePlaylistRequest(safeVideoIds, requestHeader)
        );
    }

    @Nullable
    public static CreatePlaylistRequest getRequestForVideoId(String videoId) {
        return cache.get(getCacheKey(List.of(Objects.requireNonNull(videoId))));
    }

    @Nullable
    public static CreatePlaylistRequest getRequestForVideoIds(List<String> videoIds) {
        return cache.get(getCacheKey(videoIds));
    }

    private static String getCacheKey(List<String> videoIds) {
        return videoIds.size() == 1 ? videoIds.get(0) : "multi:" + String.join(",", videoIds);
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static JSONObject sendCreatePlaylistRequest(
            List<String> videoIds,
            Map<String, String> requestHeader
    ) {
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching create playlist request for: " + videoIds);

        try {
            byte[] requestBody = PlaylistRoutes.createPlaylistBody(videoIds, str("morphe_queue_manager_playlist_title"));
            HttpURLConnection connection = PlaylistRoutes.getConnection(PlaylistRoutes.CREATE_PLAYLIST, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return Requester.parseJSONObject(connection);
            }
            handleConnectionError("Create playlist failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "sendCreatePlaylistRequest failed", ex);
        } finally {
            Logger.printDebug(() -> "videos: " + videoIds + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static JSONObject sendSetVideoIdRequest(
            String videoId,
            String playlistId,
            Map<String, String> requestHeader
    ) {
        Objects.requireNonNull(playlistId);
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching set video id request for: " + playlistId);

        try {
            byte[] requestBody = PlaylistRoutes.getSetVideoIdBody(videoId, playlistId);
            HttpURLConnection connection = PlaylistRoutes.getConnection(PlaylistRoutes.GET_SET_VIDEO_ID, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return Requester.parseJSONObject(connection);
            }
            handleConnectionError("Get set video id failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "sendSetVideoIdRequest failed", ex);
        } finally {
            Logger.printDebug(() -> "playlist: " + playlistId + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static String parseCreatePlaylistResponse(JSONObject json) {
        try {
            return json.getString("playlistId");
        } catch (JSONException e) {
            Logger.printException(() -> "parseCreatePlaylistResponse failed: " + json, e);
        }
        return null;
    }

    @Nullable
    private static String parseSetVideoIdResponse(JSONObject json) {
        try {
            Object secondaryContentsElement = json.getJSONObject("contents")
                    .getJSONObject("singleColumnWatchNextResults")
                    .getJSONObject("playlist")
                    .getJSONObject("playlist")
                    .getJSONArray("contents")
                    .get(0);

            if (secondaryContentsElement instanceof JSONObject elementJson) {
                return elementJson
                        .getJSONObject("playlistPanelVideoRenderer")
                        .getString("playlistSetVideoId");
            }
        } catch (JSONException e) {
            Logger.printException(() -> "parseSetVideoIdResponse failed: " + json, e);
        }
        return null;
    }

    @Nullable
    private static Pair<String, Map<String, String>> fetch(List<String> videoIds, Map<String, String> requestHeader) {
        JSONObject createPlaylistJson = sendCreatePlaylistRequest(videoIds, requestHeader);
        if (createPlaylistJson != null) {
            String playlistId = parseCreatePlaylistResponse(createPlaylistJson);
            if (playlistId != null) {
                Map<String, String> setVideoIds = new LinkedHashMap<>();
                for (String videoId : videoIds) {
                    JSONObject setVideoIdJson = sendSetVideoIdRequest(videoId, playlistId, requestHeader);
                    if (setVideoIdJson == null) {
                        return null;
                    }
                    String setVideoId = parseSetVideoIdResponse(setVideoIdJson);
                    if (setVideoId == null) {
                        return null;
                    }
                    setVideoIds.put(videoId, setVideoId);
                }
                return new Pair<>(playlistId, setVideoIds);
            }
        }
        return null;
    }
}
