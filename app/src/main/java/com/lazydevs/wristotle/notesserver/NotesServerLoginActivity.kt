// SPDX-License-Identifier: AGPL-3.0-only

package com.lazydevs.wristotle.notesserver

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.lazydevs.wristotle.WristotleApplication
import kotlinx.coroutines.launch

private const val TAG = "NotesServerLogin"

/**
 * Signs in to notes.osmosis.page. rust_note does the Authentik OIDC dance
 * server-side; with `client=app` its callback mints a device token and
 * redirects to `dev.rustnote.app://auth?token=…`. We run that flow in a
 * WebView and catch the redirect ourselves, so no deep link is registered
 * and the rust-note app (which owns that scheme) is never involved.
 */
class NotesServerLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Log in to notes.osmosis.page"
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    intercept(request.url.toString())

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (intercept(url)) view.stopLoading()
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        setContentView(webView)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        if (savedInstanceState == null) webView.loadUrl(NotesServerConfig.LOGIN_URL)
        else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /** True when [url] is the token redirect (handled here) or another
     *  non-web scheme the WebView can't load. */
    private fun intercept(url: String): Boolean {
        val token = NotesServerAuth.tokenFromRedirect(url)
        if (token != null) {
            if (!finished) {
                finished = true
                completeLogin(this, token) { finish() }
            }
            return true
        }
        val scheme = Uri.parse(url).scheme
        return scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data"
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, NotesServerLoginActivity::class.java))
        }

        /**
         * Validates [token] with `/auth/me`, stores it and kicks off the first
         * sync (which also uploads any notes/tasks created before signing in).
         */
        fun completeLogin(activity: ComponentActivity, token: String, done: () -> Unit) {
            val app = activity.application as WristotleApplication
            activity.lifecycleScope.launch {
                val result = runCatching { NotesServerApi(tokenProvider = { token }).me() }
                result.onSuccess { user ->
                    app.notesServerAuth.save(token, user.email ?: user.displayName ?: user.id)
                    Toast.makeText(activity, "Connected to notes.osmosis.page", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Log.w(TAG, "token validation failed", it)
                    Toast.makeText(activity, "Login failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
                setBrowserRedirectEnabled(activity, false)
                done()
            }
        }

        /**
         * The browser fallback needs a `dev.rustnote.app://auth` handler. It
         * is only enabled while a browser login is in flight so it doesn't
         * compete with the rust-note app the rest of the time.
         */
        fun setBrowserRedirectEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, NotesServerRedirectActivity::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        /** Log in through the system browser (for passkeys / SSO the WebView can't do). */
        fun startInBrowser(context: Context) {
            setBrowserRedirectEnabled(context, true)
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(NotesServerConfig.LOGIN_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Receives `dev.rustnote.app://auth?token=…` after a browser login. Disabled by default. */
class NotesServerRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent?.data?.toString()?.let(NotesServerAuth::tokenFromRedirect)
        if (token == null) {
            finish()
            return
        }
        NotesServerLoginActivity.completeLogin(this, token) {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            finish()
        }
    }
}
