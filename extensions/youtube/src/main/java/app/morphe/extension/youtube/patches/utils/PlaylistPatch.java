/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.GuardedBy;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.LoadVideoPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.requests.CreatePlaylistRequest;
import app.morphe.extension.youtube.patches.utils.requests.EditPlaylistRequest;
import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistItemsRequest;
import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistsRequest;
import app.morphe.extension.youtube.patches.utils.requests.SavePlaylistRequest;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;
import app.morphe.extension.youtube.shared.PlayerType;
import kotlin.Pair;

@SuppressWarnings({"unused", "StaticFieldLeak"})
public class PlaylistPatch {
    private static final int PLAYLIST_LIST_MAX_HEIGHT_PERCENT = 50;

    private static final String checkFailedAuth = str("morphe_queue_manager_check_failed_auth");
    private static final String checkFailedPlaylistId = str("morphe_queue_manager_check_failed_playlist_id");
    private static final String checkFailedQueue = str("morphe_queue_manager_check_failed_queue");
    private static final String checkFailedVideoId = str("morphe_queue_manager_check_failed_video_id");
    private static final String checkFailedGeneric = str("morphe_queue_manager_check_failed_generic");

    private static final String fetchFailedAdd = str("morphe_queue_manager_fetch_failed_add");
    private static final String fetchFailedCreate = str("morphe_queue_manager_fetch_failed_create");
    private static final String fetchFailedRemove = str("morphe_queue_manager_fetch_failed_remove");
    private static final String fetchFailedSave = str("morphe_queue_manager_fetch_failed_save");

    private static final String fetchSucceededAdd = str("morphe_queue_manager_fetch_succeeded_add");
    private static final String fetchSucceededCreate = str("morphe_queue_manager_fetch_succeeded_create");
    private static final String fetchSucceededRemove = str("morphe_queue_manager_fetch_succeeded_remove");
    private static final String fetchSucceededSave = str("morphe_queue_manager_fetch_succeeded_save");

    private static volatile String playlistId = Settings.QUEUE_RESTORE.get()
            ? Settings.QUEUE_PLAYLIST_ID.get()
            : Settings.QUEUE_PLAYLIST_ID.resetToDefault();
    private static volatile String videoId = "";
    private static volatile boolean syncStarted;
    private static volatile WeakReference<Object> playbackQueueManagerRef = new WeakReference<>(null);

    @GuardedBy("itself")
    private static final BidiMap<String, String> lastVideoIds = new DualHashBidiMap<>();

    /**
     * Invoked by extension.
     */
    public static void prepareDialogBuilder(Context context, String currentVideoId) {
        Utils.verifyOnMainThread();

        if (AuthUtils.isNotLoggedIn()) {
            handleCheckError(checkFailedAuth);
            return;
        }
        if (currentVideoId.isEmpty()) {
            buildBottomSheetDialog(context, QueueManager.noVideoIdQueueEntries);
        } else {
            synchronized (lastVideoIds) {
                videoId = currentVideoId;
                QueueManager[] customActionsEntries;
                boolean canReload = !PlayerType.getCurrent().isNoneOrHidden() &&
                        lastVideoIds.get(VideoInformation.getVideoId()) != null;
                if (playlistId.isEmpty() || lastVideoIds.get(currentVideoId) == null) {
                    customActionsEntries = canReload
                            ? QueueManager.addToQueueWithReloadEntries
                            : QueueManager.addToQueueEntries;
                } else {
                    customActionsEntries = canReload
                            ? QueueManager.removeFromQueueWithReloadEntries
                            : QueueManager.removeFromQueueEntries;
                }
                buildBottomSheetDialog(context, customActionsEntries);
            }
        }
    }

    /**
     * Invoked by extension.
     */
    public static void syncIfNeeded() {
        if (!playlistId.isEmpty() && !syncStarted && !AuthUtils.isNotLoggedIn()) {
            syncStarted = true;
            syncPlaylistItems();
        }
    }

    /**
     * Injection point for YouTube's playback queue manager. The manager is owned by the active
     * player and lets us add videos to the live queue without reopening the current watch URL.
     */
    public static void initializePlaybackQueueManager(Object queueManager) {
        if (queueManager != null) {
            playbackQueueManagerRef = new WeakReference<>(queueManager);
        }
    }

    private static void syncPlaylistItems() {
        Utils.submitOnBackgroundThread(() -> {
            Map<String, String> items = GetPlaylistItemsRequest.fetch(playlistId, AuthUtils.getRequestHeader());
            if (items != null && !items.isEmpty()) {
                synchronized (lastVideoIds) {
                    for (Map.Entry<String, String> entry : items.entrySet()) {
                        lastVideoIds.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                }
                Logger.printDebug(() -> "Synced " + items.size() + " items from queue playlist");
            }
            return null;
        });
    }

    private static void buildBottomSheetDialog(Context context, QueueManager[] queueManagerEntries) {
        SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                .createMainLayout(context, null);

        Map<View, Function<Context, Void>> actionsMap = new LinkedHashMap<>(2 * queueManagerEntries.length);
        for (QueueManager queueManager : queueManagerEntries) {
            View itemLayout = createItemLayout(context, queueManager.label, queueManager.drawableId);
            actionsMap.put(itemLayout, queueManager.onClickAction);
            mainLayout.addView(itemLayout);
        }

        SheetBottomDialog.SlideDialog dialog = SheetBottomDialog
                .createSlideDialog(context, mainLayout, 300);

        for (Map.Entry<View, Function<Context, Void>> entry : actionsMap.entrySet()) {
            Function<Context, Void> action = entry.getValue();
            entry.getKey().setOnClickListener(v -> {
                dialog.dismiss();
                action.apply(context);
            });
        }

        dialog.show();
    }

    @SuppressLint("ResourceType")
    private static View createItemLayout(Context context, String title, int iconId) {
        LinearLayout row = createRow(context);

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconId);
        icon.setColorFilter(ThemeUtils.getAppForegroundColor());
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Dim.dp24, Dim.dp24);
        iconParams.setMarginEnd(Dim.dp16);
        icon.setLayoutParams(iconParams);
        row.addView(icon);

        TextView text = new TextView(context);
        text.setText(title);
        text.setTextColor(ThemeUtils.getAppForegroundColor());
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        text.setLayoutParams(textParams);
        row.addView(text);

        return row;
    }

    private static LinearLayout createRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Dim.dp16, Dim.dp16, Dim.dp16, Dim.dp16);
        row.setClickable(true);
        row.setFocusable(true);

        row.setBackgroundResource(ThemeUtils.getThemeResourceId(
                context, android.R.attr.selectableItemBackground));
        return row;
    }

    private static void fetchQueue(Context context, boolean remove, boolean openPlaylist,
                                   boolean openVideo, boolean reload, boolean retry) {
        String currentPlaylistId = playlistId;
        String currentVideoId = videoId;
        Utils.runOnBackgroundThread(() -> {
            synchronized (lastVideoIds) {
                if (currentPlaylistId.isEmpty()) {
                    CreatePlaylistRequest.fetchRequestIfNeeded(currentVideoId, AuthUtils.getRequestHeader());
                    CreatePlaylistRequest request = CreatePlaylistRequest.getRequestForVideoId(currentVideoId);
                    if (request != null) {
                        Pair<String, String> playlistIds = request.getPlaylistId();
                        if (playlistIds != null) {
                            String createdPlaylistId = playlistIds.getFirst();
                            String setVideoId = playlistIds.getSecond();
                            if (createdPlaylistId != null && setVideoId != null) {
                                playlistId = createdPlaylistId;
                                if (Settings.QUEUE_RESTORE.get()) {
                                    Settings.QUEUE_PLAYLIST_ID.save(createdPlaylistId);
                                }
                                lastVideoIds.putIfAbsent(currentVideoId, setVideoId);
                                showToast(fetchSucceededCreate);
                                Logger.printDebug(() -> "Queue created, playlistId: "
                                        + createdPlaylistId + ", setVideoId: " + setVideoId);
                                if (openPlaylist) {
                                    openQueue(context, currentVideoId, openVideo, reload);
                                }
                                return;
                            }
                        }
                    }
                    showToast(fetchFailedCreate);
                } else {
                    String setVideoId = lastVideoIds.get(currentVideoId);
                    EditPlaylistRequest.fetchRequestIfNeeded(currentVideoId, currentPlaylistId,
                            setVideoId, AuthUtils.getRequestHeader());
                    EditPlaylistRequest request = EditPlaylistRequest.getRequestForVideoId(currentVideoId);
                    if (request != null) {
                        String fetchedSetVideoId = request.getResult();
                        Logger.printDebug(() -> "fetchedSetVideoId: " + fetchedSetVideoId);
                        if (remove) {
                            if ("".equals(fetchedSetVideoId)) {
                                lastVideoIds.remove(currentVideoId, setVideoId);
                                EditPlaylistRequest.clearVideoId(currentVideoId);
                                showToast(fetchSucceededRemove);
                                if (openPlaylist) {
                                    openQueue(context, currentVideoId, openVideo, reload);
                                }
                                return;
                            }
                            showToast(fetchFailedRemove);
                        } else {
                            if (fetchedSetVideoId == null || fetchedSetVideoId.isEmpty()) {
                                Logger.printDebug(() -> "Playlist not available fetchedSetVideoId: "
                                        + fetchedSetVideoId);
                                if (!retry) {
                                    // Already retried, give up.
                                    showToast(fetchFailedAdd);
                                    return;
                                }
                                // Clear saved playlist and try again.
                                playlistId = Settings.QUEUE_PLAYLIST_ID.resetToDefault();
                                fetchQueue(context, false, openPlaylist, openVideo, reload, false);
                                return;
                            }

                            lastVideoIds.putIfAbsent(currentVideoId, fetchedSetVideoId);
                            EditPlaylistRequest.clearVideoId(currentVideoId);
                            Logger.printDebug(() -> "Video added, setVideoId: " + fetchedSetVideoId);
                            showToast(fetchSucceededAdd);
                            if (openPlaylist) {
                                openQueue(context, currentVideoId, openVideo, reload);
                            }
                        }
                    }
                }
            }
        });
    }

    private static void addToQueue(Context context, boolean openPlaylist,
                                   boolean openVideo, boolean reload) {
        String queuedVideoId = videoId;
        String playingVideoId = VideoInformation.getVideoId();
        if (queuedVideoId.isEmpty() || playingVideoId.isEmpty()
                || PlayerType.getCurrent().isNoneOrHidden()) {
            fetchQueue(context, false, openPlaylist, openVideo, reload, true);
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            synchronized (lastVideoIds) {
                String currentPlaylistId = playlistId;
                boolean currentVideoWasBound = !currentPlaylistId.isEmpty()
                        && currentPlaylistId.equals(VideoInformation.getPlaylistId());

                if (!currentPlaylistId.isEmpty()
                        && (!lastVideoIds.containsKey(playingVideoId)
                        || !lastVideoIds.containsKey(queuedVideoId))) {
                    Map<String, String> items = GetPlaylistItemsRequest.fetch(
                            currentPlaylistId, AuthUtils.getRequestHeader());
                    if (items == null) {
                        Logger.printDebug(() -> "Could not sync temporary queue items; trying cached IDs and playlist edits");
                    } else {
                        lastVideoIds.putAll(items);
                    }
                }

                boolean creatingQueue = playlistId.isEmpty();
                if (creatingQueue) {
                    if (!createQueueWithVideos(playingVideoId, queuedVideoId)) {
                        showToast(fetchFailedCreate);
                        return;
                    }
                } else if (!ensureVideoInQueue(playingVideoId)
                        || !queuedVideoId.equals(playingVideoId)
                        && !ensureVideoInQueue(queuedVideoId)) {
                    // Temporary queue playlists can become unavailable to browse/edit requests.
                    // Recreate them with both videos in one create request so neither item is lost.
                    playlistId = Settings.QUEUE_PLAYLIST_ID.resetToDefault();
                    lastVideoIds.clear();
                    EditPlaylistRequest.clear();
                    CreatePlaylistRequest.clear();
                    creatingQueue = true;
                    currentVideoWasBound = false;
                    if (!createQueueWithVideos(playingVideoId, queuedVideoId)) {
                        showToast(fetchFailedCreate);
                        return;
                    }
                }

                showToast(creatingQueue ? fetchSucceededCreate : fetchSucceededAdd);
                if (openPlaylist && !openVideo) {
                    openQueue(context);
                } else if (openVideo) {
                    openQueue(context, queuedVideoId, true, reload);
                } else if (reload) {
                    openQueue(context, playingVideoId, true, true);
                } else if (!addVideoToLivePlaybackQueue(
                        playingVideoId,
                        queuedVideoId,
                        playlistId)) {
                    // Preserve the reload fallback unless it is disabled for live queue testing.
                    if (!currentVideoWasBound && !Settings.QUEUE_DISABLE_RELOAD_FALLBACK.get()) {
                        openQueue(context, playingVideoId, true, true);
                    } else {
                        showToast(fetchFailedAdd);
                    }
                }
            }
        });
    }

    private static boolean addVideoToLivePlaybackQueue(String playingVideoId,
                                                       String queuedVideoId,
                                                       String currentPlaylistId) {
        if (Utils.isCurrentlyOnMainThread()) {
            return insertVideoIntoLivePlaybackQueue(playingVideoId, queuedVideoId, currentPlaylistId);
        }

        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch completed = new CountDownLatch(1);
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                result.set(insertVideoIntoLivePlaybackQueue(
                        playingVideoId, queuedVideoId, currentPlaylistId));
            } finally {
                completed.countDown();
            }
        });
        try {
            return completed.await(2, TimeUnit.SECONDS) && result.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            Logger.printException(() -> "Interrupted while updating the live playback queue", ex);
            return false;
        }
    }

    private static boolean insertVideoIntoLivePlaybackQueue(String playingVideoId,
                                                            String queuedVideoId,
                                                            String currentPlaylistId) {
        Object queueManager = playbackQueueManagerRef.get();
        if (queueManager == null) {
            Logger.printDebug(() -> "Playback queue manager is not available");
            return false;
        }

        try {
            Object queue = findLiveQueue(queueManager, playingVideoId);
            if (queue == null) {
                Logger.printDebug(() -> "Live playback queue is not available");
                return false;
            }

            Method sizeMethod = queue.getClass().getMethod("i", int.class);
            Method getItemMethod = queue.getClass().getMethod("B", int.class, int.class);
            Method insertItemsMethod = queue.getClass().getMethod(
                    "n", int.class, int.class, Collection.class);

            int itemCount = (int) sizeMethod.invoke(queue, 0);
            int activeIndex = -1;
            Object activeItem = null;
            Method getDescriptorMethod = null;
            int playbackIndex = (int) queue.getClass().getMethod("j").invoke(queue);

            for (int index = 0; index < itemCount; index++) {
                Object item = getItemMethod.invoke(queue, 0, index);
                Method itemDescriptorMethod = item.getClass().getMethod("a");
                Object descriptor = itemDescriptorMethod.invoke(item);
                Method videoIdMethod = descriptor.getClass().getMethod("v");
                String itemVideoId = (String) videoIdMethod.invoke(descriptor);

                if (queuedVideoId.equals(itemVideoId)) {
                    Logger.printDebug(() -> "Video is already in the live playback queue");
                    return true;
                }
                if (playingVideoId.equals(itemVideoId)) {
                    activeIndex = index;
                    activeItem = item;
                    getDescriptorMethod = itemDescriptorMethod;
                }
            }

            // The queue reports its active entry directly. Its descriptor can lag behind
            // VideoInformation while YouTube is transitioning between items, so prefer this
            // index over matching the current video ID when it is valid.
            if (playbackIndex >= 0 && playbackIndex < itemCount) {
                activeIndex = playbackIndex;
                activeItem = getItemMethod.invoke(queue, 0, activeIndex);
                getDescriptorMethod = activeItem.getClass().getMethod("a");
                Object playbackDescriptor = getDescriptorMethod.invoke(activeItem);
                String playbackVideoId = (String) playbackDescriptor.getClass()
                        .getMethod("v").invoke(playbackDescriptor);
                if (!playingVideoId.equals(playbackVideoId)) {
                    Logger.printDebug(() -> "Using active playback queue index " + playbackIndex
                            + " for video " + playbackVideoId
                            + " while player reports " + playingVideoId);
                }
            }

            List<Object> itemsToInsert = new ArrayList<>(2);
            int insertionIndex;
            if (activeIndex < 0 || activeItem == null) {
                Logger.printDebug(() -> "No active entry in the live playback queue"
                        + " (items=" + itemCount + ", playbackIndex=" + playbackIndex
                        + "); seeding it with the playing and queued videos");
                Object currentDescriptor = createPlaybackQueueDescriptor(
                        getItemMethod, playingVideoId, currentPlaylistId);
                Object queuedDescriptor = createPlaybackQueueDescriptor(
                        getItemMethod, queuedVideoId, currentPlaylistId);
                if (currentDescriptor == null || queuedDescriptor == null) {
                    return false;
                }
                itemsToInsert.add(createPlaybackQueueItem(
                        getItemMethod.getReturnType(), currentDescriptor));
                itemsToInsert.add(createPlaybackQueueItem(
                        getItemMethod.getReturnType(), queuedDescriptor));
                insertionIndex = 0;
            } else {
                Object activeDescriptor = getDescriptorMethod.invoke(activeItem);
                Object descriptorBuilder = activeDescriptor.getClass().getMethod("f")
                        .invoke(activeDescriptor);
                Field videoIdField = descriptorBuilder.getClass().getField("r");
                Field playlistIdField = descriptorBuilder.getClass().getField("s");
                videoIdField.set(descriptorBuilder, queuedVideoId);
                if (!TextUtils.isEmpty(currentPlaylistId)) {
                    playlistIdField.set(descriptorBuilder, currentPlaylistId);
                }
                Object queuedDescriptor = descriptorBuilder.getClass().getMethod("a")
                        .invoke(descriptorBuilder);
                clearPlaybackQueuePlaylistIndex(queuedDescriptor);
                itemsToInsert.add(createPlaybackQueueItem(activeItem.getClass(), queuedDescriptor));
                insertionIndex = activeIndex + 1;
            }

            insertItemsMethod.invoke(queue, 0, insertionIndex, itemsToInsert);
            Logger.printDebug(() -> "Added video to the live playback queue at index "
                    + insertionIndex);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add video to the live playback queue", ex);
            return false;
        }
    }

    private static Object createPlaybackQueueDescriptor(Method getItemMethod,
                                                        String videoId,
                                                        String currentPlaylistId) {
        try {
            Class<?> descriptorClass = getItemMethod.getReturnType().getMethod("a").getReturnType();
            Class<?> builderClass = descriptorClass.getMethod("f").getReturnType();
            Constructor<?> builderConstructor = builderClass.getDeclaredConstructor();
            builderConstructor.setAccessible(true);
            Object descriptorBuilder = builderConstructor.newInstance();

            // YouTube's descriptor builder requires its local protobuf to be present before
            // accepting the video and playlist IDs.
            Field protoBuilderField = builderClass.getField("q");
            Object emptyProto = protoBuilderField.getType().getField("a").get(null);
            protoBuilderField.set(descriptorBuilder, emptyProto);
            builderClass.getField("r").set(descriptorBuilder, videoId);
            if (!TextUtils.isEmpty(currentPlaylistId)) {
                builderClass.getField("s").set(descriptorBuilder, currentPlaylistId);
            }

            Object descriptor = builderClass.getMethod("a").invoke(descriptorBuilder);
            clearPlaybackQueuePlaylistIndex(descriptor);
            return descriptor;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not create a playback queue descriptor", ex);
            return null;
        }
    }

    private static void clearPlaybackQueuePlaylistIndex(Object descriptor) throws Exception {
        Field protoField = descriptor.getClass().getField("a");
        Object playbackProto = protoField.get(descriptor);
        Field playlistIndexField = playbackProto.getClass().getField("g");
        Field presenceBitsField = playbackProto.getClass().getField("b");
        playlistIndexField.setInt(playbackProto, 0);
        presenceBitsField.setInt(playbackProto, presenceBitsField.getInt(playbackProto) & ~4);
    }

    private static Object createPlaybackQueueItem(Class<?> itemClass, Object descriptor)
            throws Exception {
        for (Constructor<?> constructor : itemClass.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 2
                    && parameterTypes[0] == UUID.class
                    && parameterTypes[1].isInstance(descriptor)) {
                constructor.setAccessible(true);
                return constructor.newInstance(UUID.randomUUID(), descriptor);
            }
        }
        throw new NoSuchMethodException("Could not find a constructor for YouTube's playback queue item");
    }

    private static Object findLiveQueue(Object queueManager, String playingVideoId) {
        Object bestCandidate = null;
        int bestCandidateScore = -1;
        int bestCandidateItemCount = -1;
        for (Class<?> type = queueManager.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object candidate = field.get(queueManager);
                    if (candidate == null) {
                        continue;
                    }
                    Class<?> candidateType = candidate.getClass();
                    Method sizeMethod = candidateType.getMethod("i", int.class);
                    Method activeIndexMethod = candidateType.getMethod("j");
                    Method getItemMethod = candidateType.getMethod("B", int.class, int.class);
                    candidateType.getMethod("n", int.class, int.class, Collection.class);

                    int itemCount = (int) sizeMethod.invoke(candidate, 0);
                    int activeIndex = (int) activeIndexMethod.invoke(candidate);
                    boolean containsPlayingVideo = false;
                    boolean activeItemIsPlayingVideo = false;
                    for (int index = 0; index < itemCount; index++) {
                        Object item = getItemMethod.invoke(candidate, 0, index);
                        String itemVideoId = getPlaybackQueueItemVideoId(item);
                        if (playingVideoId.equals(itemVideoId)) {
                            containsPlayingVideo = true;
                            activeItemIsPlayingVideo = activeIndex == index;
                        }
                    }

                    int score = activeItemIsPlayingVideo ? 4
                            : containsPlayingVideo ? 3
                            : activeIndex >= 0 && activeIndex < itemCount ? 2
                            : itemCount == 0 ? 1
                            : 0;
                    if (score > bestCandidateScore
                            || score == bestCandidateScore && itemCount > bestCandidateItemCount) {
                        bestCandidate = candidate;
                        bestCandidateScore = score;
                        bestCandidateItemCount = itemCount;
                    }
                } catch (Exception ignored) {
                    // Try the next field. Most manager fields are unrelated to the video queue.
                }
            }
        }
        Object selectedCandidate = bestCandidate;
        int selectedCandidateScore = bestCandidateScore;
        int selectedCandidateItemCount = bestCandidateItemCount;
        if (selectedCandidate != null) {
            Logger.printDebug(() -> "Selected live playback queue "
                    + selectedCandidate.getClass().getName()
                    + " with match score " + selectedCandidateScore
                    + " and " + selectedCandidateItemCount + " items");
        }
        return selectedCandidate;
    }

    private static String getPlaybackQueueItemVideoId(Object item) throws Exception {
        Object descriptor = item.getClass().getMethod("a").invoke(item);
        return (String) descriptor.getClass().getMethod("v").invoke(descriptor);
    }

    private static boolean createQueueWithVideos(String firstVideoId, String secondVideoId) {
        List<String> videoIds = firstVideoId.equals(secondVideoId)
                ? List.of(firstVideoId)
                : List.of(firstVideoId, secondVideoId);
        CreatePlaylistRequest.fetchRequestIfNeeded(videoIds, AuthUtils.getRequestHeader());
        CreatePlaylistRequest request = CreatePlaylistRequest.getRequestForVideoIds(videoIds);
        Pair<String, Map<String, String>> result = request == null ? null : request.getPlaylistItems();
        if (result == null || result.getFirst() == null
                || result.getSecond().size() != videoIds.size()) {
            return false;
        }

        playlistId = result.getFirst();
        if (Settings.QUEUE_RESTORE.get()) {
            Settings.QUEUE_PLAYLIST_ID.save(playlistId);
        }
        lastVideoIds.putAll(result.getSecond());
        Logger.printDebug(() -> "Queue created with " + result.getSecond().size()
                + " videos, playlistId: " + result.getFirst());
        return true;
    }

    private static boolean ensureVideoInQueue(String targetVideoId) {
        if (targetVideoId.isEmpty() || lastVideoIds.containsKey(targetVideoId)) {
            return !targetVideoId.isEmpty();
        }

        String currentPlaylistId = playlistId;
        if (currentPlaylistId.isEmpty()) {
            CreatePlaylistRequest.fetchRequestIfNeeded(targetVideoId, AuthUtils.getRequestHeader());
            CreatePlaylistRequest request = CreatePlaylistRequest.getRequestForVideoId(targetVideoId);
            Pair<String, String> playlistIds = request == null ? null : request.getPlaylistId();
            if (playlistIds == null || playlistIds.getFirst() == null
                    || playlistIds.getSecond() == null) {
                return false;
            }

            playlistId = playlistIds.getFirst();
            if (Settings.QUEUE_RESTORE.get()) {
                Settings.QUEUE_PLAYLIST_ID.save(playlistId);
            }
            lastVideoIds.putIfAbsent(targetVideoId, playlistIds.getSecond());
            return true;
        }

        EditPlaylistRequest.fetchRequestIfNeeded(targetVideoId, currentPlaylistId,
                null, AuthUtils.getRequestHeader());
        EditPlaylistRequest request = EditPlaylistRequest.getRequestForVideoId(targetVideoId);
        String setVideoId = request == null ? null : request.getResult();
        if (setVideoId == null || setVideoId.isEmpty()) {
            return false;
        }

        lastVideoIds.putIfAbsent(targetVideoId, setVideoId);
        EditPlaylistRequest.clearVideoId(targetVideoId);
        return true;
    }

    private static void saveToPlaylist(Context context) {
        String currentPlaylistId = playlistId;
        if (currentPlaylistId.isEmpty()) {
            handleCheckError(checkFailedQueue);
            return;
        }
        try {
            GetPlaylistsRequest request = GetPlaylistsRequest.fetchRequestIfNeeded(
                    currentPlaylistId, AuthUtils.getRequestHeader());
            if (request == null) {
                return;
            }
            Utils.runOnBackgroundThread(() ->  {
                Pair<String, String>[] playlists = request.getPlaylists();
                if (playlists == null || playlists.length == 0) return;
                Utils.runOnMainThread(() -> {
                    SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                            .createMainLayout(context, null);
                    Map<View, Runnable> actionsMap = new LinkedHashMap<>(2 * playlists.length);
                    int libraryIconId = QueueManager.SAVE_QUEUE.drawableId;

                    LinearLayout listContainer = new LinearLayout(context);
                    listContainer.setOrientation(LinearLayout.VERTICAL);

                    for (Pair<String, String> playlist : playlists) {
                        String listId = playlist.getFirst();
                        String title = playlist.getSecond();
                        Runnable action = () -> saveToPlaylist(listId, title);
                        View itemLayout = createItemLayout(context, title, libraryIconId);
                        actionsMap.put(itemLayout, action);
                        listContainer.addView(itemLayout);
                    }

                    ScrollView scrollView = SheetBottomDialog.createCappedScrollView(
                            context, PLAYLIST_LIST_MAX_HEIGHT_PERCENT);
                    scrollView.addView(listContainer);
                    mainLayout.addView(scrollView);

                    SheetBottomDialog.SlideDialog dialog = SheetBottomDialog
                            .createSlideDialog(context, mainLayout, 300);
                    for (Map.Entry<View, Runnable> entry : actionsMap.entrySet()) {
                        Runnable action = entry.getValue();
                        entry.getKey().setOnClickListener(v -> {
                            dialog.dismiss();
                            action.run();
                        });
                    }
                    dialog.show();
                    GetPlaylistsRequest.clear();
                });
            });
        } catch (Exception ex) {
            Logger.printException(() -> "saveToPlaylist failure", ex);
        }
    }

    private static void saveToPlaylist(@Nullable String libraryId, @Nullable String libraryTitle) {
        try {
            if (TextUtils.isEmpty(libraryId)) {
                handleCheckError(checkFailedPlaylistId);
                return;
            }
            SavePlaylistRequest request = SavePlaylistRequest.fetchRequestIfNeeded(
                    playlistId, libraryId, AuthUtils.getRequestHeader());
            if (request == null) {
                return;
            }
            Utils.runOnBackgroundThread(() -> {
                Boolean result = request.getResult();
                if (Boolean.TRUE.equals(result)) {
                    showToast(String.format(fetchSucceededSave, libraryTitle));
                    SavePlaylistRequest.clear();
                    return;
                }
                showToast(fetchFailedSave);
            });
        } catch (Exception ex) {
            Logger.printException(() -> "saveToPlaylist failure", ex);
        }
    }

    private static void openQueue(Context context) {
        openQueue(context, "", false, false);
    }

    private static void openQueue(Context context, String currentVideoId, boolean openVideo, boolean reload) {
        Utils.runOnMainThreadNowOrLater(() -> {
            String currentPlaylistId = playlistId;
            if (currentPlaylistId.isEmpty()) {
                handleCheckError(checkFailedQueue);
                return;
            }
            try {
                String url;
                if (openVideo) {
                    if (TextUtils.isEmpty(currentVideoId)) {
                        handleCheckError(checkFailedVideoId);
                        return;
                    }
                    if (reload) {
                        final long videoTime = VideoInformation.getVideoTime();

                        url = "https://www.youtube.com/watch?v=" +
                                VideoInformation.getVideoId() +
                                "&list=" +
                                currentPlaylistId +
                                (videoTime > 0 ? "&t=" + (videoTime / 1000) : "");
                    } else {
                        url = "https://www.youtube.com/watch?v=" +
                                currentVideoId +
                                "&list=" +
                                currentPlaylistId;
                    }
                } else {
                    url = "https://www.youtube.com/playlist?list=" +
                            currentPlaylistId;
                }

                LoadVideoPatch.openVideoIntent(url, reload);
            } catch (Exception ex) {
                Logger.printException(() -> "openQueue failure", ex);
            }
        });
    }

    private static void handleCheckError(String reason) {
        showToast(String.format(checkFailedGeneric, reason));
    }

    private static void showToast(String reason) {
        Utils.showToastShort(reason);
    }

    public enum QueueManager {
        ADD_TO_QUEUE(
                "morphe_queue_manager_add_to_queue",
                "yt_outline_list_add_black_24",
                "yt_outline_experimental_playlist_add_vd_theme_24",
                context -> {
                    addToQueue(context, false, false, false);
                    return null;
                }
        ),
        ADD_TO_QUEUE_AND_OPEN_QUEUE(
                "morphe_queue_manager_add_to_queue_and_open_queue",
                "yt_outline_list_add_black_24",
                "yt_outline_experimental_playlist_add_vd_theme_24",
                context -> {
                    addToQueue(context, true, false, false);
                    return null;
                }
        ),
        ADD_TO_QUEUE_AND_PLAY_VIDEO(
                "morphe_queue_manager_add_to_queue_and_play_video",
                "yt_outline_list_play_arrow_black_24",
                "yt_outline_experimental_playlist_vd_theme_24",
                context -> {
                    addToQueue(context, true, true, false);
                    return null;
                }
        ),
        ADD_TO_QUEUE_AND_RELOAD_VIDEO(
                "morphe_queue_manager_add_to_queue_and_reload_video",
                "yt_outline_arrow_circle_black_24",
                "yt_outline_experimental_replay_vd_theme_24",
                context -> {
                    addToQueue(context, true, true, true);
                    return null;
                }
        ),
        REMOVE_FROM_QUEUE(
                "morphe_queue_manager_remove_from_queue",
                "yt_outline_trash_can_black_24",
                "yt_outline_experimental_circle_slash_vd_theme_24",
                context -> {
                    fetchQueue(context, true, false, false, false, true);
                    return null;
                }
        ),
        REMOVE_FROM_QUEUE_AND_OPEN_QUEUE(
                "morphe_queue_manager_remove_from_queue_and_open_queue",
                "yt_outline_trash_can_black_24",
                "yt_outline_experimental_circle_slash_vd_theme_24",
                context -> {
                    fetchQueue(context, true, true, false, false, true);
                    return null;
                }
        ),
        REMOVE_FROM_QUEUE_AND_RELOAD_VIDEO(
                "morphe_queue_manager_remove_from_queue_and_reload_video",
                "yt_outline_arrow_circle_black_24",
                "yt_outline_experimental_replay_vd_theme_24",
                context -> {
                    fetchQueue(context, true, true, true, true, true);
                    return null;
                }
        ),
        OPEN_QUEUE(
                "morphe_queue_manager_open_queue",
                "yt_outline_list_view_black_24",
                "yt_outline_experimental_queue_vd_theme_24",
                context -> {
                    PlaylistPatch.openQueue(context);
                    return null;
                }
        ),
        SAVE_QUEUE(
                "morphe_queue_manager_save_queue",
                "yt_outline_bookmark_black_24",
                "yt_outline_experimental_bookmark_vd_theme_24",
                context -> {
                    PlaylistPatch.saveToPlaylist(context);
                    return null;
                }
        );

        public final int drawableId;
        public final String label;

        public final Function<Context, Void> onClickAction;

        QueueManager(String label, String icon, String boldIcon, Function<Context, Void> onClickAction) {
            this.drawableId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE,
                    YouTubeActivityHook.USE_BOLD_ICONS ? boldIcon : icon);
            this.label = str(label);
            this.onClickAction = onClickAction;
        }

        public static final QueueManager[] addToQueueEntries = {
                ADD_TO_QUEUE,
                ADD_TO_QUEUE_AND_OPEN_QUEUE,
                ADD_TO_QUEUE_AND_PLAY_VIDEO,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] addToQueueWithReloadEntries = {
                ADD_TO_QUEUE,
                ADD_TO_QUEUE_AND_OPEN_QUEUE,
                ADD_TO_QUEUE_AND_PLAY_VIDEO,
                ADD_TO_QUEUE_AND_RELOAD_VIDEO,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] removeFromQueueEntries = {
                REMOVE_FROM_QUEUE,
                REMOVE_FROM_QUEUE_AND_OPEN_QUEUE,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] removeFromQueueWithReloadEntries = {
                REMOVE_FROM_QUEUE,
                REMOVE_FROM_QUEUE_AND_OPEN_QUEUE,
                REMOVE_FROM_QUEUE_AND_RELOAD_VIDEO,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] noVideoIdQueueEntries = {
                OPEN_QUEUE,
                SAVE_QUEUE,
        };
    }
}
