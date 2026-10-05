/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest.ChannelSearchResponse;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest.ChannelSearchResult;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class ChannelSearchPatch {

    private static final int CHANNEL_ID_LENGTH = 24;

    private static final int THUMBNAIL_WIDTH = Dim.dp(120);
    private static final int THUMBNAIL_HEIGHT = Dim.dp(68);
    private static final int THUMBNAIL_CORNER_RADIUS = Dim.dp(8);
    private static final int THUMBNAIL_TIMEOUT_MILLISECONDS = 10 * 1000;

    private static final int DIALOG_ANIMATION_DURATION_MILLISECONDS = 300;

    /**
     * The app runs a submit through more than one code path, and only the first one counts.
     */
    private static final long DUPLICATE_SUBMIT_MILLISECONDS = 1000;

    /**
     * Bitmaps are large, so they are kept only while a result list is open.
     */
    private static final Map<String, Bitmap> thumbnailCache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(40));

    private static WeakReference<Activity> mainActivityRef = new WeakReference<>(null);

    /**
     * Browse id of the page the user is on. Channel pages use the channel id as their browse id.
     */
    private static String currentBrowseId = "";

    /** Kept through YouTube's transition from a channel page to the search screen. */
    private static volatile String pendingChannelSearchBrowseId = "";

    private static WeakReference<View> searchButtonParentRef = new WeakReference<>(null);
    private static WeakReference<ImageView> searchButtonViewRef = new WeakReference<>(null);
    private static WeakReference<View> channelSearchButtonRef = new WeakReference<>(null);

    private static String lastQuery = "";
    private static long lastQueryTime;

    /**
     * Injection point.
     */
    public static void setMainActivity(Activity activity) {
        mainActivityRef = new WeakReference<>(activity);
    }

    /**
     * Injection point.
     */
    public static void setBrowseId(@Nullable String browseId) {
        String nextBrowseId = browseId == null ? "" : browseId;
        // YouTube can report a temporary non-channel browse id while transitioning from a
        // channel to search. Keep the channel selected by the dedicated button until submit.
        // A genuinely different channel page invalidates a stale selection.
        if (isChannelId(nextBrowseId) && !nextBrowseId.equals(currentBrowseId)) {
            pendingChannelSearchBrowseId = "";
        }
        currentBrowseId = nextBrowseId;
        updateChannelSearchButton();
    }

    /**
     * Injection point.
     * <p>
     * The search feed is not a browse page, and returning to it from a channel sets no browse id.
     */
    public static void clearBrowseId() {
        currentBrowseId = "";
        // A channel-search click opens YouTube's search screen, whose creation clears the
        // current browse id. Keep the pending channel id until the user submits the query.
        updateChannelSearchButton();
    }

    /** Injection point. Adds a second toolbar button alongside YouTube's global search button. */
    public static void setSearchButtonView(String enumName, View parentView, ImageView imageView) {
        if (!"SEARCH".equals(enumName) && !"SEARCH_BOLD".equals(enumName)
                && !"SEARCH_CAIRO".equals(enumName)) {
            return;
        }

        searchButtonParentRef = new WeakReference<>(parentView);
        searchButtonViewRef = new WeakReference<>(imageView);
        parentView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // Touching YouTube's original search button always means global search.
                pendingChannelSearchBrowseId = "";
            }
            return false;
        });
        updateChannelSearchButton();
        // YouTube can report the item container before attaching it to the toolbar. Retry after
        // the current menu/layout pass so the adjacent button has a parent to insert into.
        parentView.post(ChannelSearchPatch::updateChannelSearchButton);
    }

    private static void updateChannelSearchButton() {
        try {
            View button = channelSearchButtonRef.get();
            View originalParent = searchButtonParentRef.get();
            ImageView originalIcon = searchButtonViewRef.get();
            boolean visible = Settings.CHANNEL_SEARCH.get() && isChannelId(currentBrowseId);

            View toolbarItem = originalParent;
            ViewGroup toolbar = null;
            if (originalParent != null) {
                // The toolbar hook gets ImageView.getParent(), but YouTube nests that view in
                // menu_item_N before the real toolbar. Find the direct toolbar child so the new
                // button is laid out beside the original menu item rather than over it.
                int toolbarId = originalParent.getResources().getIdentifier(
                        "toolbar", "id", originalParent.getContext().getPackageName());
                View current = originalParent;
                while (current.getParent() instanceof ViewGroup parent) {
                    if (toolbarId != 0 && parent.getId() == toolbarId) {
                        toolbar = parent;
                        toolbarItem = current;
                        break;
                    }
                    current = parent;
                }
            }

            if (button != null) {
                button.setVisibility(visible ? View.VISIBLE : View.GONE);
                if (button.getParent() == toolbar) {
                    return;
                }
                // YouTube can replace the toolbar while retaining the old menu item tree. A
                // button still attached to that old tree must not block insertion into the new one.
                if (button.getParent() instanceof ViewGroup oldParent) {
                    oldParent.removeView(button);
                }
                channelSearchButtonRef = new WeakReference<>(null);
            }
            if (!visible || originalParent == null || originalIcon == null
                    || toolbar == null || toolbarItem == null) {
                return;
            }

            // The toolbar hook receives the ImageView's immediate parent. YouTube nests that
            // inside a menu_item_N container, which is the actual direct child of the toolbar.
            ViewGroup.LayoutParams originalParams = toolbarItem.getLayoutParams();
            ViewGroup.LayoutParams buttonParams = copyLayoutParams(toolbar, originalParams);
            if (buttonParams instanceof LinearLayout.LayoutParams linearParams) {
                // Search menu items can have a weighted width that changes as the available
                // toolbar actions change between channels. Keep the extra icon in a fixed slot.
                linearParams.width = Dim.dp48;
                linearParams.weight = 0;
            }
            FrameLayout channelButton = new FrameLayout(toolbarItem.getContext());
            channelButton.setLayoutParams(buttonParams);
            channelButton.setContentDescription("Search in channel");
            channelButton.setFocusable(true);
            channelButton.setClickable(true);

            ImageView icon = new ImageView(originalParent.getContext());
            Drawable originalDrawable = originalIcon.getDrawable();
            if (originalDrawable != null) {
                Drawable.ConstantState state = originalDrawable.getConstantState();
                icon.setImageDrawable(state == null
                        ? originalDrawable.mutate()
                        : state.newDrawable(originalParent.getResources()).mutate());
            }
            icon.setScaleType(originalIcon.getScaleType());
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    Dim.dp24,
                    Dim.dp24,
                    Gravity.CENTER);
            channelButton.addView(icon, iconParams);
            channelButton.setOnClickListener(view -> {
                pendingChannelSearchBrowseId = currentBrowseId;
                ImageView searchButton = searchButtonViewRef.get();
                if (searchButton != null) {
                    searchButton.callOnClick();
                }
            });

            int index = toolbar.indexOfChild(toolbarItem);
            // Put channel search first; the original global-search button stays to its right.
            toolbar.addView(channelButton, index < 0 ? 0 : index);
            channelSearchButtonRef = new WeakReference<>(channelButton);
        } catch (Exception ex) {
            Logger.printException(() -> "updateChannelSearchButton failure", ex);
        }
    }

    private static ViewGroup.LayoutParams copyLayoutParams(
            ViewGroup parent, ViewGroup.LayoutParams original) {
        if (original == null) {
            return new ViewGroup.LayoutParams(Dim.dp48, Dim.dp48);
        }
        if (parent instanceof LinearLayout && original instanceof LinearLayout.LayoutParams linear) {
            return new LinearLayout.LayoutParams(linear);
        }
        if (parent instanceof FrameLayout && original instanceof FrameLayout.LayoutParams frame) {
            return new FrameLayout.LayoutParams(frame);
        }
        if (original instanceof ViewGroup.MarginLayoutParams margins) {
            return new ViewGroup.MarginLayoutParams(margins);
        }
        return new ViewGroup.LayoutParams(original);
    }

    /**
     * Injection point.
     *
     * @return Whether the global search was replaced with a search inside the current channel.
     */
    public static boolean searchInChannel(@Nullable String query) {
        try {
            String channelId = pendingChannelSearchBrowseId;
            if (channelId.isEmpty() || !Settings.CHANNEL_SEARCH.get()
                    || query == null || query.isEmpty()) {
                return false;
            }

            pendingChannelSearchBrowseId = "";
            if (!isChannelId(channelId)) {
                return false;
            }

            Activity activity = mainActivityRef.get();
            if (activity == null) {
                return false;
            }

            final long now = System.currentTimeMillis();
            if (query.equals(lastQuery) && now - lastQueryTime < DUPLICATE_SUBMIT_MILLISECONDS) {
                return true;
            }
            lastQuery = query;
            lastQueryTime = now;

            Logger.printDebug(() -> "Searching channel " + channelId + " for: " + query);

            Utils.runOnBackgroundThread(() -> {
                ChannelSearchResponse response = ChannelSearchRequest
                        .fetchRequestIfNeeded(channelId, query)
                        .getResponse();

                Utils.runOnMainThread(() -> {
                    if (response == null) {
                        Utils.showToastShort(str("morphe_channel_search_failed"));
                    } else if (response.results.isEmpty()) {
                        Utils.showToastShort(str("morphe_channel_search_no_results"));
                    } else {
                        showResults(activity, query, response);
                    }
                });
            });

            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "searchInChannel failure", ex);
        }

        return false;
    }

    private static void showResults(Activity activity, String query, ChannelSearchResponse response) {
        try {
            hideKeyboard(activity);

            SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                    .createMainLayout(activity, null);
            mainLayout.addView(createHeader(activity, query, response.channelName));
            mainLayout.addView(createDivider(activity));

            LinearLayout listContainer = new LinearLayout(activity);
            listContainer.setOrientation(LinearLayout.VERTICAL);

            ScrollView scrollView = SheetBottomDialog.createCappedScrollView(activity);
            scrollView.addView(listContainer);
            mainLayout.addView(scrollView);

            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog
                    .createSlideDialog(activity, mainLayout, DIALOG_ANIMATION_DURATION_MILLISECONDS);

            for (ChannelSearchResult result : response.results) {
                View row = createResultRow(activity, result);
                row.setOnClickListener(view -> {
                    dialog.dismiss();
                    // Opening the video right away interrupts the dismiss animation, and the
                    // dialog then stays on screen because that animation never ends.
                    Utils.runOnMainThreadDelayed(() -> {
                        closeSearch(activity);
                        LoadVideoPatch.openVideoIntent(
                                "https://www.youtube.com/watch?v=" + result.videoId, false);
                    }, DIALOG_ANIMATION_DURATION_MILLISECONDS);
                });
                listContainer.addView(row);
            }

            dialog.setOnDismissListener(dismissed -> thumbnailCache.clear());
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showResults failure", ex);
        }
    }

    /**
     * The query alone reads like a global search, so the channel it was scoped to is shown under it.
     */
    private static View createHeader(Activity activity, String query, String channelName) {
        final int foregroundColor = ThemeUtils.getAppForegroundColor();

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp12);

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(BaseSearchViewController.getSearchIconDrawable());
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Dim.dp24, Dim.dp24);
        iconParams.setMarginEnd(Dim.dp16);
        icon.setLayoutParams(iconParams);
        header.addView(icon);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView queryView = new TextView(activity);
        queryView.setText(query);
        queryView.setTextColor(foregroundColor);
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        queryView.setTypeface(Typeface.DEFAULT_BOLD);
        queryView.setSingleLine();
        queryView.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(queryView);

        String scope = str("morphe_channel_search_results");
        if (!channelName.isEmpty()) {
            scope += "  •  " + channelName;
        }

        TextView scopeView = new TextView(activity);
        scopeView.setText(scope);
        scopeView.setTextColor(foregroundColor);
        scopeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        scopeView.setAlpha(0.7f);
        scopeView.setSingleLine();
        scopeView.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(scopeView);

        header.addView(text);
        return header;
    }

    private static View createDivider(Activity activity) {
        View divider = new View(activity);
        divider.setBackgroundColor(ThemeUtils.getAppForegroundColor());
        divider.setAlpha(0.12f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp1);
        params.setMargins(0, 0, 0, Dim.dp4);
        divider.setLayoutParams(params);
        return divider;
    }

    private static View createResultRow(Activity activity, ChannelSearchResult result) {
        LinearLayout row = createResultRowContainer(activity);
        row.addView(createResultThumbnail(activity, result));
        row.addView(createResultText(activity, result));
        return row;
    }

    private static LinearLayout createResultRowContainer(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp8);
        row.setClickable(true);
        row.setFocusable(true);

        TypedValue ripple = new TypedValue();
        if (activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }

        return row;
    }

    private static View createResultThumbnail(Activity activity, ChannelSearchResult result) {
        ImageView thumbnail = new ImageView(activity);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setClipToOutline(true);
        thumbnail.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        THUMBNAIL_CORNER_RADIUS);
            }
        });
        LinearLayout.LayoutParams thumbnailParams =
                new LinearLayout.LayoutParams(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        thumbnailParams.setMarginEnd(Dim.dp12);
        thumbnail.setLayoutParams(thumbnailParams);
        loadThumbnail(thumbnail, result.thumbnailUrl);

        return thumbnail;
    }

    private static View createResultText(Activity activity, ChannelSearchResult result) {
        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView title = new TextView(activity);
        title.setText(result.title);
        title.setTextColor(ThemeUtils.getAppForegroundColor());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setMaxLines(2);
        text.addView(title);

        if (!result.metadata.isEmpty()) {
            TextView metadata = new TextView(activity);
            metadata.setText(result.metadata);
            metadata.setTextColor(ThemeUtils.getAppForegroundColor());
            metadata.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            metadata.setAlpha(0.7f);
            metadata.setSingleLine();
            text.addView(metadata);
        }

        return text;
    }

    private static void loadThumbnail(ImageView view, String url) {
        if (url.isEmpty()) {
            return;
        }

        Bitmap cached = thumbnailCache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        WeakReference<ImageView> viewRef = new WeakReference<>(view);
        Utils.runOnBackgroundThread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);

                Bitmap bitmap;
                try (InputStream stream = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(stream);
                }
                if (bitmap == null) {
                    return;
                }

                thumbnailCache.put(url, bitmap);
                Utils.runOnMainThread(() -> {
                    ImageView target = viewRef.get();
                    if (target != null) {
                        target.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not load thumbnail: " + url, ex);
            }
        });
    }

    /**
     * The search box keeps focus, since to submit it would have handled was taken over here.
     */
    private static void hideKeyboard(Activity activity) {
        View focused = activity.getCurrentFocus();
        if (focused == null) {
            return;
        }

        InputMethodManager manager = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    /**
     * Leaves the search the result was picked from, the same way the user would. Hiding the
     * keyboard is not enough, because the search box takes focus back and raises it again.
     */
    @SuppressWarnings("deprecation")
    private static void closeSearch(Activity activity) {
        try {
            activity.onBackPressed();
        } catch (Exception ex) {
            Logger.printException(() -> "closeSearch failure", ex);
        }
    }

    private static boolean isChannelId(String browseId) {
        return browseId.length() == CHANNEL_ID_LENGTH && browseId.startsWith("UC");
    }
}
