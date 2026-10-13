/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.twitch;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * First playback proof for Twitch inside the patched YouTube process.
 *
 * <p>This intentionally starts on Twitch's Following page. The user signs in through Twitch's
 * own page and chooses a live stream or VOD there; no stream URL is collected or stored by Morphe.
 * It is not the final Twitch UI, OAuth client, chat implementation, or VOD-resume feature.</p>
 */
@SuppressWarnings("deprecation")
public final class TwitchBrowserFragment extends PreferenceFragment {
    private static final String START_URL = "https://m.twitch.tv/directory/following/live";
    private static WeakReference<TwitchBrowserFragment> activeFragment = new WeakReference<>(null);

    private WebView webView;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        webView = new WebView(getActivity());
        webView.setBackgroundColor(ThemeUtils.getAppBackgroundColor());

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternalScheme(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternalScheme(Uri.parse(url));
            }
        });

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL);
        }
        return webView;
    }

    @Override
    public void onResume() {
        super.onResume();
        activeFragment = new WeakReference<>(this);
        if (webView != null) webView.onResume();
    }

    @Override
    public void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        if (activeFragment.get() == this) activeFragment = new WeakReference<>(null);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroyView();
    }

    /** Called by the host activity before it closes. */
    public static boolean handleBackPress() {
        TwitchBrowserFragment fragment = activeFragment.get();
        if (fragment == null || fragment.webView == null || !fragment.webView.canGoBack()) return false;
        fragment.webView.goBack();
        return true;
    }

    private boolean openExternalScheme(Uri uri) {
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception exception) {
            Logger.printException(() -> "Unable to open Twitch link: " + uri, exception);
        }
        return true;
    }
}
