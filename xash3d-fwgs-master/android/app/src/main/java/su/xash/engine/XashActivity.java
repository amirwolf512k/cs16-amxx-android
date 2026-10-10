package su.xash.engine;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings.Secure;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

import su.xash.engine.util.CrashReports;
import su.xash.engine.util.SoftKeyboardPan;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class XashActivity extends SDLActivity {
        private boolean mUseVolumeKeys;
        private String mPackageName;
        private static final String TAG = "XashActivity";

        // sandboxed HTML MOTD (set in getArguments())
        private String mMotdBaseDir;
        private String mMotdGameDir;
        private Dialog mMotdDialog;
        // the MOTD WebView of the currently-open dialog. Every map
        // change makes the server re-send the MOTD, so without explicit
        // destroy() the old WebViews would pile up in the native renderer
        // and eventually crash the game after several map changes.
        private WebView mMotdWebView;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        //getWindow().addFlags(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES);
                        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }

                SoftKeyboardPan.assistActivity(this);
        }

        @Override
        public void onDestroy() {
                super.onDestroy();

                // Now that we don't exit from native code, we need to exit here, resetting
                // application state (actually global variables that we don't cleanup on exit)
                //
                // When the issue with global variables will be resolved, remove that exit() call
                System.exit(0);
        }

        @Override
        protected String[] getLibraries() {
                return new String[]{"SDL2", "xash"};
        }

        @SuppressLint("HardwareIds")
        private String getAndroidID() {
                return Secure.getString(getContentResolver(), Secure.ANDROID_ID);
        }

        @SuppressLint("ApplySharedPref")
        private void saveAndroidID(String id) {
                getSharedPreferences("xash_preferences", MODE_PRIVATE).edit().putString("xash_id", id).commit();
        }

        private String loadAndroidID() {
                return getSharedPreferences("xash_preferences", MODE_PRIVATE).getString("xash_id", "");
        }

        @Override
        public String getCallingPackage() {
                if (mPackageName != null) {
                        return mPackageName;
                }

                return super.getCallingPackage();
        }

        private AssetManager getAssets(boolean isEngine) {
                AssetManager am = null;

                if (isEngine) {
                        am = getAssets();
                } else {
                        try {
                                am = getPackageManager().getResourcesForApplication(getCallingPackage()).getAssets();
                        } catch (Exception e) {
                                Log.e(TAG, "Unable to load mod assets!");
                                e.printStackTrace();
                        }
                }

                return am;
        }

        private String[] getAssetsList(boolean isEngine, String path) {
                AssetManager am = getAssets(isEngine);

                // never let a NullPointerException reach the JNI caller
                if (am == null)
                        return new String[]{};

                try {
                        String[] list = am.list(path);
                        return list != null ? list : new String[]{};
                } catch (Exception e) {
                        e.printStackTrace();
                }

                return new String[]{};
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
                if (SDLActivity.mBrokenLibraries) {
                        return false;
                }

                int keyCode = event.getKeyCode();
                if (!mUseVolumeKeys) {
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_ZOOM_IN || keyCode == KeyEvent.KEYCODE_ZOOM_OUT) {
                                return false;
                        }
                }

                return getWindow().superDispatchKeyEvent(event);
        }

        private static void appendStringExtra(StringBuilder sb, Intent intent, String key) {
                String value = intent.getStringExtra(key);
                if (value != null)
                        sb.append("  ").append(key).append(" = ").append(value).append('\n');
        }

        // record intent info, so that it could be consumed later for crash reporting
        private void recordLaunchInfo() {
                // do not overwrite current launch info with pending crash log, shouldn't happen but might
                File pendingCrash = new File(getFilesDir(), "crashes/" + CrashReports.STACKTRACE_NAME);
                if (pendingCrash.exists() && pendingCrash.length() > 0)
                        return;

                // write Android version, fingerprint, supported abis, etc
                CrashReports.writeSystemInfo(this);

                // now create intent info and pass it to crash reporting
                Intent intent = getIntent();
                if (intent == null)
                        return;
                StringBuilder sb = new StringBuilder();
                sb.append("Action: ").append(intent.getAction()).append('\n');
                sb.append("Data: ").append(intent.getDataString()).append('\n');
                sb.append("Calling package: ").append(getCallingPackage()).append('\n');
                sb.append("Extras:\n");
                // only write intent extras that we care about
                appendStringExtra(sb, intent, "gamedir");
                appendStringExtra(sb, intent, "gamelibdir");
                appendStringExtra(sb, intent, "pakfile");
                appendStringExtra(sb, intent, "basedir");
                appendStringExtra(sb, intent, "package");
                appendStringExtra(sb, intent, "argv");
                sb.append("  usevolume = ").append(intent.getBooleanExtra("usevolume", false)).append('\n');
                String[] env = intent.getStringArrayExtra("env");
                if (env != null)
                        sb.append("  env = ").append(Arrays.toString(env)).append('\n');
                CrashReports.writeIntentInfo(this, sb.toString());
        }

        // TODO: REMOVE LATER, temporary launchers support?
        @Override
        protected String[] getArguments() {
                File crashDir = new File(getFilesDir(), "crashes");
                crashDir.mkdirs();
                nativeSetenv("XASH3D_CRASH_DIR", crashDir.getAbsolutePath());

                recordLaunchInfo();

                String gamedir = getIntent().getStringExtra("gamedir");
                if (gamedir == null) gamedir = "valve";
                nativeSetenv("XASH3D_GAME", gamedir);

                String gamelibdir = getIntent().getStringExtra("gamelibdir");
                if (gamelibdir == null) {
                        // v7: fall back to our own nativeLibraryDir so metamod can find
                        // bundled server libraries (e.g. libhl_android_*.so for Half-Life)
                        // when running games without an external game app.
                        gamelibdir = getApplicationInfo().nativeLibraryDir;
                }
                if (gamelibdir != null) nativeSetenv("XASH3D_GAMELIBDIR", gamelibdir);

                String rodir = System.getenv("XASH3D_RODIR");
                if (rodir == null) {
                        // FIXME: we are using rodir as a supplier for downloaded game libraries
                        rodir = getFilesDir().getAbsolutePath() + "/gamelibs";
                        nativeSetenv("XASH3D_RODIR", rodir);
                }
                Log.i(TAG, "XASH3D_RODIR = " + rodir);

                String pakfile = getIntent().getStringExtra("pakfile");
                if (pakfile != null) nativeSetenv("XASH3D_EXTRAS_PAK2", pakfile);

                String basedir = getIntent().getStringExtra("basedir");
                if (basedir != null) {
                        nativeSetenv("XASH3D_BASEDIR", basedir);
                } else {
                        String rootPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/xash";
                        nativeSetenv("XASH3D_BASEDIR", rootPath);
                        basedir = rootPath;
                }

                // remember the real game dirs for the MOTD WebView sandbox
                mMotdBaseDir = basedir;
                mMotdGameDir = gamedir;

                mUseVolumeKeys = getIntent().getBooleanExtra("usevolume", false);
                mPackageName = getIntent().getStringExtra("package");

                String[] env = getIntent().getStringArrayExtra("env");
                if (env != null) {
                        for (int i = 0; i < env.length; i += 2)
                                nativeSetenv(env[i], env[i + 1]);
                }

                String argv = getIntent().getStringExtra("argv");
                if (argv == null) argv = "-console -log";

                return argv.split(" ");
        }

        // =====================================================================
        // sandboxed HTML MOTD rendering ("Message of the
        // Day" like real CS 1.6). The engine hands us the raw "MOTD" user
        // message payload as bytes; we render it in a WebView that can only
        // read files inside the current game dir (valve/ or cstrike/), can
        // never reach addons/ (metamod/AMXX data, top15 stats) and has no
        // JavaScript, DOM storage, content:// or network access at all.
        // =====================================================================

        // implemented in the engine (libxash.so); tells
        // the client dll the MOTD dialog is gone so it can show the deferred
        // team select menu (PC CS 1.6 behaviour). Name kept by proguard.
        private static native void nativeMOTDClosed();

        private static void notifyMOTDClosed() {
                try {
                        nativeMOTDClosed();
                } catch ( Throwable t ) {
                        Log.w( TAG, "nativeMOTDClosed failed", t );
                }
        }

        // surface Java-side MOTD failures INSIDE the game console (the
        // engine's Con_Printf). Logcat-only diagnostics were invisible on
        // user devices: a bare "MOTD dialog failed" gives no clue WHY
        // (the dialog returning false with no reason anywhere).
        private static native void nativeConsolePrintf( String s );

        private static void consolePrintf( String s ) {
                try {
                        nativeConsolePrintf( s );
                } catch ( Throwable t ) {
                        Log.w( TAG, "consolePrintf failed", t );
                }
        }

        /** Called from native (JNI) with the window title (the server name,
         *  like the original HL1 VGUI MOTD window) and the raw MOTD payload.
         *  Runs the dialog creation synchronously on the UI thread and
         *  returns whether the dialog is actually on screen, so the client dll
         *  can fall back to the classic HUD text renderer when the WebView
         *  dialog cannot be shown. */
        public boolean showMOTD( final byte[] titleBytes, final byte[] htmlBytes ) {
                final boolean[] shown = new boolean[1];
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch( 1 );

                runOnUiThread( new Runnable() {
                        @Override
                        public void run() {
                                try {
                                        shown[0] = showMOTDOnUiThread( titleBytes, htmlBytes );
                                } catch ( Throwable t ) {
                                        Log.w( TAG, "showMOTD failed", t );
                                        shown[0] = false;
                                } finally {
                                        latch.countDown();
                                }
                        }
                } );

                try {
                        // the engine render thread blocks briefly here; the UI thread
                        // is independent, so this cannot deadlock
                        if ( !latch.await( 5, java.util.concurrent.TimeUnit.SECONDS ) ) {
                                // make the timeout visible — it is indistinguishable
                                // from a dead activity otherwise
                                consolePrintf( "MOTD: UI thread did not answer within 5s (dialog skipped)" );
                                return false;
                        }
                } catch ( InterruptedException e ) {
                        return false;
                }

                return shown[0];
        }

        // the original HL1 VGUI colors — orange title/button text
        // (255,170,0) and the window border (178,119,0), straight from
        // hlsdk-portable cl_dll/vgui_MOTDWindow.cpp
        private static final int MOTD_VGUI_TEXT = 0xFFFFAA00;
        private static final int MOTD_VGUI_BORDER = 0xFFB37700;

        /** Destroy the previous MOTD WebView after its dialog is fully
         *  detached (WebView.destroy() must never run while the view is still
         *  attached to a window). Posted so it is ordered after dismiss. */
        private void scheduleMotdWebViewDestroy() {
                if ( mMotdWebView == null )
                        return;

                final WebView wv = mMotdWebView;
                mMotdWebView = null;

                new android.os.Handler( android.os.Looper.getMainLooper()).post(
                        new Runnable() {
                                @Override
                                public void run() {
                                        try {
                                                wv.destroy();
                                        } catch ( Throwable t ) {
                                                Log.w( TAG, "MOTD WebView destroy failed", t );
                                        }
                                }
                        } );
        }

        private boolean showMOTDOnUiThread( byte[] titleBytes, byte[] htmlBytes ) {
                try {
                        if ( mMotdDialog != null ) {
                                mMotdDialog.dismiss();
                                mMotdDialog = null;
                        }

                        // the previous map's WebView goes away with its
                        // dialog — reclaim it before building a new one
                        scheduleMotdWebViewDestroy();

                        String title = decodeMotdBytes( titleBytes );
                        String raw = new String( htmlBytes, "UTF-8" );
                        Log.i( TAG, "MOTD dialog: title=\"" + title + "\" payloadBytes="
                                + ( htmlBytes != null ? htmlBytes.length : -1 ));
                        consolePrintf( "MOTD: dialog requested (title=\"" + title + "\", "
                                + ( htmlBytes != null ? htmlBytes.length : -1 ) + " bytes)" );
                        String base = mMotdBaseDir != null ? mMotdBaseDir
                                : Environment.getExternalStorageDirectory().getAbsolutePath() + "/xash";
                        String game = mMotdGameDir != null ? mMotdGameDir : "valve";
                        final File gameDir = new File( base, game );

                        // first line of the payload into the console -- when a
                        // server's MOTD "shows as text only" this tells us what
                        // it actually sent (real HTML, plain text, or something
                        // we misdetect) without asking the user to sniff packets
                        String preview = raw.length() > 120 ? raw.substring( 0, 120 ) + "..." : raw;
                        consolePrintf( "MOTD: payload preview: " + preview.replace( "\r", "" ).replace( "\n", " " ) );

                        final Dialog dialog = new Dialog( this, android.R.style.Theme_Black_NoTitleBar );
                        Window w = dialog.getWindow();
                        w.setBackgroundDrawable( new ColorDrawable( 0x00000000 ) );
                        w.setLayout( ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT );

                        // NO fullscreen shade — the retail CS 1.6 MOTD
                        // (CMenuPanel( iShadeFullscreen=0 )) leaves the game
                        // at full brightness around the window, exactly like
                        // the PC original
                        FrameLayout root = new FrameLayout( this );
                        root.setBackgroundColor( 0x00000000 );

                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int screenW = dm.widthPixels;
                        int screenH = dm.heightPixels;

                        // 1:1 to the retail Steam CS 1.6 MOTD window
                        // (pixel-measured from the retail window):
                        // ~71.5% of the screen width x ~90% of the height,
                        // black at ~76% opacity (the game faintly shows
                        // through), Valve LineBorder (178,119,0) around it.
                        int panelW = Math.max( dp( 200 ), Math.round( screenW * 0.715f ));
                        int panelH = Math.max( dp( 140 ), Math.round( screenH * 0.90f ));

                        LinearLayout panel = new LinearLayout( this );
                        panel.setOrientation( LinearLayout.VERTICAL );
                        panel.setBackground( makeMOTDWindowBackground());
                        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams( panelW, panelH );
                        panelLp.gravity = Gravity.CENTER;
                        root.addView( panel, panelLp );

                        // title row like the reference — the CS soldier
                        // logo left, amber "Title Font" caption, sitting
                        // directly on the window background (no bar strip;
                        // the game shows through it), with a thin light
                        // separator line along its bottom edge.
                        int titleH = Math.round( panelH * 0.125f );
                        LinearLayout titleBar = new LinearLayout( this );
                        titleBar.setOrientation( LinearLayout.HORIZONTAL );
                        titleBar.setGravity( Gravity.CENTER_VERTICAL );

                        ImageView logo = new ImageView( this );
                        logo.setImageResource( R.drawable.cs_logo );
                        logo.setScaleType( ImageView.ScaleType.FIT_CENTER );
                        int logoSize = Math.round( titleH * 0.62f );
                        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                                        logoSize, logoSize );
                        logoLp.setMargins( Math.round( panelW * 0.035f ), 0, Math.round( panelW * 0.02f ), 0 );
                        titleBar.addView( logo, logoLp );

                        TextView titleView = new TextView( this );
                        titleView.setText(( title != null && !title.isEmpty()) ? title : "Counter-Strike" );
                        titleView.setTextColor( MOTD_VGUI_TEXT );
                        titleView.setTextSize( TypedValue.COMPLEX_UNIT_PX, Math.round( titleH * 0.42f ));
                        titleView.setTypeface( Typeface.DEFAULT_BOLD );
                        titleView.setSingleLine( true );
                        titleView.setEllipsize( TextUtils.TruncateAt.END );
                        titleView.setGravity( Gravity.CENTER_VERTICAL );
                        titleBar.addView( titleView, new LinearLayout.LayoutParams(
                                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f ));

                        panel.addView( titleBar, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, titleH ));

                        // light 1px separator line under the title row
                        View titleSep = new View( this );
                        titleSep.setBackground( new ColorDrawable( 0x99B4B8BC ));
                        panel.addView( titleSep, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max( 1, dp( 1 )) ));

                        // --- content: the sandboxed WebView plays the role of
                        // the original ScrollPanel + TextPanel; HTML MOTDs
                        // render for real, plain text is wrapped game-styled.
                        // if WebView is unavailable (provider missing,
                        // device policy, ...) fall back to a styled TextView via
                        // Html.fromHtml — the dialog still returns true, so the
                        // client never degrades to raw-text HUD garbage.
                        View content;

                        // the content sits in a PURE BLACK box with no
                        // border of its own (the ScrollPanel client area of
                        // the PC window) — the framed look comes from the
                        // window chrome around it
                        LinearLayout contentWrap = new LinearLayout( this );
                        contentWrap.setOrientation( LinearLayout.VERTICAL );
                        contentWrap.setBackgroundColor( 0xFF000000 );

                        try {
                                WebView wv = createMOTDWebView( gameDir );
                                wv.setBackgroundColor( 0xFF000000 );
                                wv.loadDataWithBaseURL( "http://motd.local/", buildMOTDDocument( raw ),
                                                "text/html", "utf-8", null );
                                mMotdWebView = wv;
                                content = wv;
                                consolePrintf( "MOTD: WebView created, page loading" );
                        } catch ( Throwable wt ) {
                                consolePrintf( "MOTD: WebView unavailable (" + wt + "), using styled text" );

                                TextView tv = new TextView( this );
                                tv.setText( Html.fromHtml( buildMOTDTextHtml( raw ) ) );
                                tv.setMovementMethod( ScrollingMovementMethod.getInstance() );
                                tv.setTextColor( 0xFFDEDEDE );
                                tv.setTextSize( TypedValue.COMPLEX_UNIT_SP, 14 );
                                tv.setLinkTextColor( MOTD_VGUI_TEXT );
                                content = tv;
                        }

                        contentWrap.addView(( View ) content, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT ));

                        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f );
                        contentLp.setMargins( Math.round( panelW * 0.023f ), Math.round( panelH * 0.085f ),
                                        Math.round( panelW * 0.023f ), 0 );
                        panel.addView( contentWrap, contentLp );

                        // --- the reference OK button — small, bottom-left:
                        // ~20.5% of the window wide, ~4.6% tall, near-black
                        // body with a thin LIGHT-GRAY frame (the VGUI
                        // CommandButton look) and an AMBER label; the empty
                        // window band below it matches the reference too.
                        int okW = Math.max( dp( 64 ), Math.round( panelW * 0.205f ));
                        int okH = Math.max( dp( 22 ), Math.round( panelH * 0.046f ));

                        Button ok = new Button( this );
                        ok.setText( "OK" );
                        ok.setAllCaps( false );
                        ok.setTextColor( MOTD_VGUI_TEXT );
                        ok.setTextSize( TypedValue.COMPLEX_UNIT_PX, Math.round( okH * 0.5f ));
                        ok.setBackground( makeMOTDButtonBackground());
                        ok.setStateListAnimator( null );
                        ok.setElevation( 0f );
                        ok.setPadding( dp( 6 ), 0, dp( 6 ), 0 );
                        ok.setOnClickListener( new View.OnClickListener() {
                                        @Override
                                        public void onClick( View v ) {
                                                dialog.dismiss();
                                        }
                        } );

                        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams( okW, okH );
                        okLp.setMargins( Math.round( panelW * 0.112f ), Math.round( panelH * 0.02f ),
                                        0, Math.round( panelH * 0.16f ));
                        panel.addView( ok, okLp );

                        dialog.setContentView( root );
                        dialog.setOnDismissListener( new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss( DialogInterface d ) {
                                        mMotdDialog = null;
                                        // the page itself keeps running in the
                                        // background until the next MOTD replaces
                                        // it: server auth pages (next21 style)
                                        // finish their client check from here,
                                        // and destroying the view on OK left
                                        // that check unfinished -- players got
                                        // dropped from those servers later on
                                        consolePrintf( "MOTD: window closed, page keeps running in background" );
                                        notifyMOTDClosed();
                                }
                        } );

                        mMotdDialog = dialog;
                        dialog.show();
                        Log.i( TAG, "MOTD dialog shown (WebView HTML rendering)" );
                        consolePrintf( "MOTD: dialog shown" );
                        return true;
                } catch ( Throwable t ) {
                        Log.w( TAG, "showMOTD failed", t );
                        // the reason must reach the game console, not only logcat
                        consolePrintf( "MOTD: dialog error: " + t );
                        return false;
                }
        }

        /** Engine payloads are raw UTF-8 bytes (a malformed server
         *  string must never abort NewStringUTF); decode leniently. */
        private static String decodeMotdBytes( byte[] b ) {
                try {
                        return b == null ? "" : new String( b, "UTF-8" ).trim();
                } catch ( Throwable t ) {
                        return "";
                }
        }

        /** Build the document loaded into the MOTD WebView. HTML pages
         *  render as-is (PC parity); plain-text MOTDs are wrapped into a
         *  game-styled document (black background, HL1 "Briefing Text" tan). */
        private static String buildMOTDDocument( String raw ) {
                String trimmed = raw == null ? "" : raw.trim();
                String lower = trimmed.toLowerCase( Locale.US );

                // broader tag list -- server MOTDs use every HTML tag in
                // the book (<head>, <title>, <style>, <meta>, <center>, <span>,
                // <h1>..<h6>, <li>, <b>/<i>/<u>, ...). A real HTML page always
                // carries at least one of these; plain chat-style text never
                // matches because "<" must be immediately followed by a letter
                // and form a known tag prefix.
                boolean looksHtml = lower.contains( "<html" ) || lower.contains( "<body" )
                        || lower.contains( "<head" ) || lower.contains( "<title" )
                        || lower.contains( "<meta" ) || lower.contains( "<style" )
                        || lower.contains( "<br" ) || lower.contains( "<p>" ) || lower.contains( "<p " )
                        || lower.contains( "<table" ) || lower.contains( "<div" ) || lower.contains( "<font" )
                        || lower.contains( "<img" ) || lower.contains( "<center" ) || lower.contains( "<span" )
                        || lower.contains( "<h1" ) || lower.contains( "<h2" ) || lower.contains( "<h3" )
                        || lower.contains( "<h4" ) || lower.contains( "<h5" ) || lower.contains( "<h6" )
                        || lower.contains( "<li" ) || lower.contains( "<pre" )
                        || lower.contains( "<b>" ) || lower.contains( "<i>" ) || lower.contains( "<u>" )
                        || lower.contains( "<em>" ) || lower.contains( "<strong" )
                        || lower.contains( "<hr" ) || lower.contains( "<a " ) || lower.contains( "<!doctype" );

                Log.i( TAG, "MOTD content: len=" + trimmed.length() + " html=" + looksHtml );
                consolePrintf( "MOTD: content is " + ( looksHtml ? "html" : "plain text" )
                        + " (" + trimmed.length() + " chars)" );

                if ( looksHtml )
                        return trimmed;

                StringBuilder sb = new StringBuilder();
                sb.append( "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" );
                sb.append( "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" );
                sb.append( "<style>html,body{margin:0;padding:0;background:#000;height:100%;}" );
                // plain text renders light-gray on black like the PC
                // CS 1.6 MOTD text panel (was the HL1 tan before)
                sb.append( "pre{margin:0;padding:14px;font-family:sans-serif;" );
                sb.append( "font-size:14px;line-height:1.45;color:#dedede;" );
                sb.append( "white-space:pre-wrap;word-wrap:break-word;}</style></head><body><pre>" );
                sb.append( escapeMOTDHtml( trimmed ) );
                sb.append( "</pre></body></html>" );
                return sb.toString();
        }

        private static String escapeMOTDHtml( String s ) {
                return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
        }

        /** Simplified document for the Html.fromHtml fallback path.
         *  fromHtml understands basic tags only (no CSS), so block elements
         *  are mapped to newlines and the body carries the HL1 tan color. */
        private static String buildMOTDTextHtml( String raw ) {
                String trimmed = raw == null ? "" : raw.trim();
                String lower = trimmed.toLowerCase( Locale.US );

                boolean looksHtml = lower.contains( "<html" ) || lower.contains( "<body" )
                        || lower.contains( "<head" ) || lower.contains( "<title" )
                        || lower.contains( "<meta" ) || lower.contains( "<style" )
                        || lower.contains( "<br" ) || lower.contains( "<p>" ) || lower.contains( "<p " )
                        || lower.contains( "<table" ) || lower.contains( "<div" ) || lower.contains( "<font" )
                        || lower.contains( "<img" ) || lower.contains( "<center" ) || lower.contains( "<span" )
                        || lower.contains( "<h1" ) || lower.contains( "<h2" ) || lower.contains( "<h3" )
                        || lower.contains( "<h4" ) || lower.contains( "<h5" ) || lower.contains( "<h6" )
                        || lower.contains( "<li" ) || lower.contains( "<pre" )
                        || lower.contains( "<b>" ) || lower.contains( "<i>" ) || lower.contains( "<u>" )
                        || lower.contains( "<em>" ) || lower.contains( "<strong" )
                        || lower.contains( "<hr" ) || lower.contains( "<a " ) || lower.contains( "<!doctype" );

                if ( looksHtml ) {
                        // drop <style>/<script> bodies — fromHtml would print them as text
                        String noCss = trimmed
                                .replaceAll( "(?is)<style[^>]*>.*?</style>", "" )
                                .replaceAll( "(?is)<script[^>]*>.*?</script>", "" );
                        return "<font color='#dedede'>" + noCss + "</font>";
                }

                return "<font color='#dedede'><pre>" + escapeMOTDHtml( trimmed ) + "</pre></font>";
        }

        /** The reference window — black at ~76% opacity (the game
         *  faintly shows through, like the PC retail MOTD over the map)
         *  with Valve's 1px LineBorder (178,119,0). */
        private Drawable makeMOTDWindowBackground() {
                GradientDrawable d = new GradientDrawable();
                d.setColor( 0xC2000000 );
                d.setStroke( Math.max( 1, dp( 1 )), MOTD_VGUI_BORDER );
                return d;
        }

        /** The small command button — near-black body, thin light-gray
         *  frame (the VGUI CommandButton look), amber when pressed. */
        private StateListDrawable makeMOTDButtonBackground() {
                GradientDrawable normal = new GradientDrawable();
                normal.setColor( 0xE6000000 );
                normal.setStroke( Math.max( 1, dp( 1 )), 0xFFC8C4BC );

                GradientDrawable pressed = new GradientDrawable();
                pressed.setColor( 0xFF3A2E10 );
                pressed.setStroke( Math.max( 1, dp( 1 )), MOTD_VGUI_TEXT );

                StateListDrawable sld = new StateListDrawable();
                sld.addState( new int[] { android.R.attr.state_pressed }, pressed );
                sld.addState( new int[] { -android.R.attr.state_pressed }, normal );
                return sld;
        }

        @SuppressLint("SetJavaScriptEnabled")
        private WebView createMOTDWebView( final File gameDir ) {
                WebView wv = new WebView( this );
                WebSettings s = wv.getSettings();

                // --- sandbox defaults ---------------------------------------
                // PC parity -- the retail CS 1.6 MOTD window (Steam
                // browser) renders REMOTE images and lets links navigate
                // inside the window. JS is enabled because modern server
                // rank/ban pages need it; there is no JS-to-native bridge,
                // so the sandbox holds: file:// and content:// stay blocked
                // and shouldInterceptRequest still gates every request.
                s.setJavaScriptEnabled( true );
                s.setDomStorageEnabled( true );
                s.setAllowFileAccess( false );
                s.setAllowContentAccess( false );
                s.setAllowFileAccessFromFileURLs( false );
                s.setAllowUniversalAccessFromFileURLs( false );
                s.setBlockNetworkLoads( false );
                s.setBlockNetworkImage( false );
                s.setSavePassword( false );
                s.setCacheMode( WebSettings.LOAD_NO_CACHE );
                s.setMediaPlaybackRequiresUserGesture( true );
                // the MOTD document is loaded from a plain-http fake origin,
                // so https pages opening http frames never hit mixed content
                // -- always allow, like the PC window did
                s.setMixedContentMode( WebSettings.MIXED_CONTENT_ALWAYS_ALLOW );

                // server MOTD pages are designed for the ~640px-wide PC
                // CS 1.6 window; lay them out wide and zoom to fit, so they
                // appear complete just like on PC
                s.setUseWideViewPort( true );
                s.setLoadWithOverviewMode( true );

                wv.setWebViewClient( new WebViewClient() {
                        @Override
                        public WebResourceResponse shouldInterceptRequest( WebView view, WebResourceRequest request ) {
                                Uri url = request.getUrl();
                                String scheme = url.getScheme();

                                if ( scheme == null )
                                        return emptyResponse();

                                // game-dir relative resources: served from disk by us
                                // (fake origin is http now, https kept for safety)
                                if ( ( scheme.equals( "http" ) || scheme.equals( "https" ) ) && "motd.local".equals( url.getHost() ) ) {
                                        File f = resolveInGameDir( gameDir, url.getPath() );
                                        if ( f != null ) {
                                                try {
                                                        return new WebResourceResponse( guessMime( f.getName() ),
                                                                null, new FileInputStream( f ) );
                                                } catch ( Throwable t ) {
                                                        return emptyResponse();
                                                }
                                        }
                                        return emptyResponse();
                                }

                                if ( scheme.equals( "data" ) )
                                        return null; // inline data URIs are harmless

                                // real http(s) goes to the network (PC
                                // parity: server MOTDs embed remote images,
                                // rank/ban pages, web fonts)
                                if ( scheme.equals( "http" ) || scheme.equals( "https" ) )
                                        return null;

                                // file://, content:// and anything else is still blocked
                                return emptyResponse();
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading( WebView view, WebResourceRequest request ) {
                                // PC parity -- http(s) links navigate INSIDE
                                // the MOTD window; everything else (file,
                                // content, mailto, intent, market) stays blocked
                                String scheme = request.getUrl().getScheme();
                                return !( "http".equals( scheme ) || "https".equals( scheme ));
                        }

                        @Override
                        public void onPageFinished( WebView view, String url ) {
                                consolePrintf( "MOTD: page ready: " + url );
                        }

                        @Override
                        public void onReceivedError( WebView view, WebResourceRequest request, android.webkit.WebResourceError error ) {
                                consolePrintf( "MOTD: load error " + error.getErrorCode() + " "
                                        + request.getUrl() + ": " + error.getDescription());
                        }

                        @Override
                        public void onReceivedHttpError( WebView view, WebResourceRequest request, WebResourceResponse response ) {
                                consolePrintf( "MOTD: http " + response.getStatusCode()
                                        + " " + request.getUrl());
                        }
                } );

                // surface page JS console output (a few lines max, some
                // server pages log every frame) -- enough to tell why a
                // server MOTD page came up blank
                wv.setWebChromeClient( new WebChromeClient() {
                        private int mLines;

                        @Override
                        public boolean onConsoleMessage( android.webkit.ConsoleMessage cm ) {
                                if( mLines++ < 20 )
                                        consolePrintf( "MOTD: js: " + cm.message() + " ("
                                                + cm.sourceId() + ":" + cm.lineNumber() + ")" );
                                return true;
                        }
                } );

                return wv;
        }

        /**
         * Resolve a URL path against the game dir with strict sandboxing:
         * no ".." traversal, no "addons" access, result must stay inside
         * the game dir and exist as a plain file.
         */
        private static File resolveInGameDir( File gameDir, String uriPath ) {
                try {
                        if ( uriPath == null || uriPath.isEmpty() )
                                return null;

                        String path = Uri.decode( uriPath );
                        if ( path.indexOf( '\0' ) >= 0 )
                                return null;

                        File root = gameDir.getCanonicalFile();
                        File cur = root;

                        for ( String seg : path.split( "/" ) ) {
                                if ( seg.isEmpty() || seg.equals( "." ) )
                                        continue;
                                if ( seg.equals( ".." ) )
                                        return null;
                                if ( seg.equalsIgnoreCase( "addons" ) )
                                        return null; // addons is off-limits (metamod/AMXX, top15 data)
                                cur = new File( cur, seg );
                        }

                        File resolved = cur.getCanonicalFile();
                        if ( !resolved.getPath().startsWith( root.getPath() + File.separator ) )
                                return null;
                        if ( !resolved.isFile() )
                                return null;
                        return resolved;
                } catch ( Throwable t ) {
                        return null;
                }
        }

        private static WebResourceResponse emptyResponse() {
                return new WebResourceResponse( "text/plain", "utf-8",
                        new ByteArrayInputStream( new byte[0] ) );
        }

        private static String guessMime( String name ) {
                String n = name.toLowerCase( Locale.US );
                if ( n.endsWith( ".html" ) || n.endsWith( ".htm" ) ) return "text/html";
                if ( n.endsWith( ".jpg" ) || n.endsWith( ".jpeg" ) ) return "image/jpeg";
                if ( n.endsWith( ".png" ) ) return "image/png";
                if ( n.endsWith( ".gif" ) ) return "image/gif";
                if ( n.endsWith( ".bmp" ) ) return "image/bmp";
                if ( n.endsWith( ".css" ) ) return "text/css";
                if ( n.endsWith( ".txt" ) ) return "text/plain";
                return "application/octet-stream";
        }

        private int dp( int v ) {
                return Math.round( v * getResources().getDisplayMetrics().density );
        }

        // ------------------------------------------------------------------
        // CS 1.6 style loading window
        //
        // the engine raises it through the loading plaque (connect,
        // changelevel) and drives both segmented bars from the resource
        // downloader: the main bar for the whole pipeline and, only
        // while a transfer runs, the download block below it (file
        // name, its own fill, counters) with Cancel beside it. The
        // footer shows the server's banner image when its connect page
        // carries one. Cancel asks the engine to disconnect.
        // ------------------------------------------------------------------

        // matched to the classic GoldSrc VGUI2 "Loading..." window:
        // olive panel, light frame, yellow segments on a dark track
        private static final int LOADING_SHADE = 0xE6000000;
        private static final int LOADING_PANEL_BG = 0xFF3E4637;
        private static final int LOADING_PANEL_BORDER = 0xFF9AA391;
        private static final int LOADING_TITLE = 0xFFE6EBDD;
        private static final int LOADING_TEXT = 0xFFC9CFC0;
        private static final int LOADING_BAR_TRACK = 0xFF2C3227;
        private static final int LOADING_BAR_BORDER = 0xFF8C9484;
        private static final int LOADING_BAR_FILL = 0xFFDDBE43;

        private FrameLayout mLoadingOverlay;
        private LoadingBar mLoadingBar;
        private TextView mLoadingStatus;
        private TextView mLoadingPct;
        private ImageView mLoadingBanner;
        private String mLoadingServer = "";

        // the rows the Cancel button rides between: beside the main
        // bar while nothing transfers, beside the transfer bar while
        // one does - the pc dialog moves it the same way
        private LinearLayout mLoadingBarRow;
        private LinearLayout mLoadingDlRow;
        private Button mLoadingCancel;

        // the download block of the classic dialog: the file the server
        // is sending, its own bar and the counter lines around it,
        // visible only while a transfer actually runs
        private TextView mLoadingDlName;
        private TextView mLoadingDlStatus;
        private LoadingBar mLoadingDlBar;
        private TextView mLoadingDlFooter;

        /** The segmented yellow progress bar of the classic loading
         *  window: small blocks filling left to right. percent < 0
         *  means "busy, nothing measured yet" and draws it empty. */
        private static class LoadingBar extends View {
                private float mPercent = -1f;

                LoadingBar( Context c ) { super( c ); }

                void setPercent( float p ) {
                        if( mPercent == p ) return;
                        mPercent = p;
                        invalidate();
                }

                float getPercent() {
                        return mPercent;
                }

                @Override
                protected void onDraw( Canvas canvas ) {
                        float w = getWidth(), h = getHeight();
                        Paint p = new Paint();

                        p.setColor( LOADING_BAR_TRACK );
                        canvas.drawRect( 0, 0, w, h, p );

                        final int segments = 18;
                        float gap = Math.max( 2, w * 0.012f );
                        float segW = ( w - gap * ( segments + 1 )) / segments;
                        float insetY = Math.max( 2, h * 0.16f );

                        int filled = 0;
                        if( mPercent >= 0 )
                                filled = Math.round( segments * Math.max( 0f, Math.min( 1f, mPercent / 100f )));

                        p.setColor( LOADING_BAR_FILL );
                        for( int i = 0; i < filled; i++ ) {
                                float x = gap + i * ( segW + gap );
                                canvas.drawRect( x, insetY, x + segW, h - insetY, p );
                        }

                        p.setColor( LOADING_BAR_BORDER );
                        p.setStrokeWidth( Math.max( 1, getResources().getDisplayMetrics().density ));
                        p.setStyle( Paint.Style.STROKE );
                        canvas.drawRect( 0, 0, w, h, p );
                }
        }

        public void loadingShow( final String serverAddr ) {
                seedDefaultAvatars();

                runOnUiThread( new Runnable() {
                        @Override public void run() {
                                try {
                                        showLoadingOnUiThread( serverAddr );
                                } catch( Throwable t ) {
                                        Log.w( TAG, "loadingShow failed", t );
                                }
                        }
                });
        }

        public void loadingStatus( final String text, final float percent ) {
                runOnUiThread( new Runnable() {
                        @Override public void run() {
                                if( mLoadingOverlay == null ) return;
                                try {
                                        if( mLoadingStatus != null && text != null && text.length() > 0 &&
                                                !text.contentEquals( mLoadingStatus.getText() ))
                                                mLoadingStatus.setText( text );
                                        if( mLoadingBar != null ) {
                                                // percent < 0 means "busy, nothing measured":
                                                // the bar keeps whatever it already shows
                                                if( percent >= 0f )
                                                        mLoadingBar.setPercent( percent );
                                                if( mLoadingPct != null )
                                                        mLoadingPct.setText( mLoadingBar.getPercent() >= 0f ?
                                                                Math.round( mLoadingBar.getPercent() ) + "%" : "" );
                                        }
                                } catch( Throwable t ) {
                                        Log.w( TAG, "loadingStatus failed", t );
                                }
                        }
                });
        }

        /** the Cancel button rides beside whichever bar is the last
         *  visible one: next to the main bar while nothing downloads,
         *  next to the transfer bar while one runs - the pc dialog
         *  moves it between the rows the same way */
        private void moveLoadingCancel( LinearLayout row ) {
                if( mLoadingCancel == null || row == null ) return;
                if( mLoadingCancel.getParent() == row ) return;

                if( mLoadingCancel.getParent() instanceof ViewGroup )
                        (( ViewGroup )mLoadingCancel.getParent()).removeView( mLoadingCancel );

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp( 26 ));
                lp.leftMargin = dp( 8 );
                row.addView( mLoadingCancel, lp );
        }

        /** the second bar of the classic loading dialog: the engine
         *  calls it while a transfer runs with the file name, that
         *  file's fill (0..100) and the counter lines around it. a
         *  null file folds the whole block away. */
        public void loadingDownload( final String file, final float percent,
                final String status, final String footer ) {
                runOnUiThread( new Runnable() {
                        @Override public void run() {
                                if( mLoadingOverlay == null ) return;
                                try {
                                        if( file == null || file.length() == 0 ) {
                                                if( mLoadingDlName != null )
                                                        mLoadingDlName.setVisibility( View.GONE );
                                                if( mLoadingDlStatus != null )
                                                        mLoadingDlStatus.setVisibility( View.GONE );
                                                if( mLoadingDlBar != null )
                                                        mLoadingDlBar.setVisibility( View.GONE );
                                                if( mLoadingDlFooter != null )
                                                        mLoadingDlFooter.setVisibility( View.GONE );
                                                if( mLoadingDlRow != null )
                                                        mLoadingDlRow.setVisibility( View.GONE );
                                                moveLoadingCancel( mLoadingBarRow );
                                                return;
                                        }

                                        if( mLoadingDlName == null ) return;

                                        mLoadingDlName.setVisibility( View.VISIBLE );
                                        if( !file.contentEquals( mLoadingDlName.getText() ))
                                                mLoadingDlName.setText( file );

                                        if( mLoadingDlStatus != null ) {
                                                if( status != null && status.length() > 0 ) {
                                                        mLoadingDlStatus.setVisibility( View.VISIBLE );
                                                        if( !status.contentEquals( mLoadingDlStatus.getText() ))
                                                                mLoadingDlStatus.setText( status );
                                                } else mLoadingDlStatus.setVisibility( View.GONE );
                                        }

                                        if( mLoadingDlBar != null ) {
                                                mLoadingDlBar.setVisibility( View.VISIBLE );
                                                mLoadingDlBar.setPercent( percent );
                                        }

                                        // transfer running: the Cancel moves
                                        // beside the transfer bar
                                        if( mLoadingDlRow != null ) {
                                                mLoadingDlRow.setVisibility( View.VISIBLE );
                                                moveLoadingCancel( mLoadingDlRow );
                                        }

                                        if( mLoadingDlFooter != null ) {
                                                if( footer != null && footer.length() > 0 ) {
                                                        mLoadingDlFooter.setVisibility( View.VISIBLE );
                                                        if( !footer.contentEquals( mLoadingDlFooter.getText() ))
                                                                mLoadingDlFooter.setText( footer );
                                                } else mLoadingDlFooter.setVisibility( View.GONE );
                                        }
                                } catch( Throwable t ) {
                                        Log.w( TAG, "loadingDownload failed", t );
                                }
                        }
                });
        }

        /** Resolve a game-relative path against the running gamedir. */
        private File gameDirFile( String relPath ) {
                try {
                        String base = mMotdBaseDir != null ? mMotdBaseDir
                                : Environment.getExternalStorageDirectory().getAbsolutePath() + "/xash";
                        String game = mMotdGameDir != null ? mMotdGameDir : "valve";
                        File f = new File( base, game + "/" + relPath );
                        return f.getCanonicalPath().startsWith( new File( base, game ).getCanonicalPath() ) ? f : null;
                } catch( Throwable t ) {
                        return null;
                }
        }

        /** Whatever the server shipped: png/jpg/bmp through BitmapFactory,
         *  24/32-bit tga (the director banner format) through a small
         *  decoder. Returns null for anything else. */
        private Bitmap decodeImageFile( File f ) {
                try {
                        byte[] data = new byte[( int )f.length()];
                        java.io.FileInputStream in = new java.io.FileInputStream( f );
                        int read = 0, n;
                        while( read < data.length && ( n = in.read( data, read, data.length - read )) > 0 )
                                read += n;
                        in.close();
                        if( read <= 0 ) return null;

                        Bitmap bmp = BitmapFactory.decodeByteArray( data, 0, read );
                        if( bmp != null ) return bmp;

                        return decodeTga( data );
                } catch( Throwable t ) {
                        return null;
                }
        }

        /** Minimal tga reader for the server banners: type 2 (raw) and
         *  10 (RLE), 24/32 bits per pixel, any origin. */
        private static Bitmap decodeTga( byte[] b ) {
                try {
                        if( b.length < 18 ) return null;
                        int idLen = b[0] & 0xFF;
                        int cmapType = b[1] & 0xFF;
                        int type = b[2] & 0xFF;
                        int w = ( b[12] & 0xFF ) | (( b[13] & 0xFF ) << 8 );
                        int h = ( b[14] & 0xFF ) | (( b[15] & 0xFF ) << 8 );
                        int bpp = b[16] & 0xFF;
                        int desc = b[17] & 0xFF;
                        boolean topOrigin = ( desc & 0x20 ) != 0;

                        if( cmapType != 0 || ( type != 2 && type != 10 ))
                                return null;
                        if( w <= 0 || h <= 0 || w > 4096 || h > 4096 )
                                return null;
                        if( bpp != 24 && bpp != 32 )
                                return null;

                        int bytes = bpp / 8;
                        int off = 18 + idLen;
                        int[] pix = new int[w * h];
                        int src = off;

                        for( int y = 0; y < h; y++ ) {
                                int row = topOrigin ? y : ( h - 1 - y );
                                for( int x = 0; x < w; ) {
                                        if( src >= b.length ) return null;
                                        int count = 1;
                                        boolean rle = false;
                                        if( type == 10 ) {
                                                int packet = b[src++] & 0xFF;
                                                rle = ( packet & 0x80 ) != 0;
                                                count = ( packet & 0x7F ) + 1;
                                        }
                                        for( int k = 0; k < count && x < w; k++, x++ ) {
                                                if( !rle ) {
                                                        if( src + bytes > b.length ) return null;
                                                }
                                                int bb, gg, rr, aa = 0xFF;
                                                if( rle ) {
                                                        if( src + bytes > b.length ) return null;
                                                        bb = b[src] & 0xFF; gg = b[src + 1] & 0xFF;
                                                        rr = b[src + 2] & 0xFF;
                                                        if( bytes == 4 ) aa = b[src + 3] & 0xFF;
                                                } else {
                                                        bb = b[src] & 0xFF; gg = b[src + 1] & 0xFF;
                                                        rr = b[src + 2] & 0xFF;
                                                        if( bytes == 4 ) aa = b[src + 3] & 0xFF;
                                                        src += bytes;
                                                }
                                                pix[row * w + x] = ( aa << 24 ) | ( rr << 16 ) | ( gg << 8 ) | bb;
                                        }
                                        if( rle ) src += bytes;
                                }
                        }
                        return Bitmap.createBitmap( pix, w, h, Bitmap.Config.ARGB_8888 );
                } catch( Throwable t ) {
                        return null;
                }
        }

        public void loadingHide() {
                runOnUiThread( new Runnable() {
                        @Override public void run() {
                                hideLoadingOverlay();
                        }
                });
        }

        private static native void nativeLoadingCancelled();

        private static native void nativeAvatarPicked();

        // the customize menu's "Choose image..." button
        private static final int REQ_PICK_AVATAR = 4711;

        // ------------------------------------------------------------------
        // TAB avatars
        //
        // the engine forwards every steamid it sees in player userinfo;
        // we resolve the steam avatar once per id (public community
        // profile xml, no key needed) and drop a png into
        // media/avatars/ where the scoreboard picks it up
        // ------------------------------------------------------------------

        private final java.util.Set<Long> mAvatarFetched =
                java.util.Collections.synchronizedSet( new java.util.HashSet<Long>() );

        // logged once per id: the accounts the photo sources can not
        // answer for right now, so the log stays readable
        private final java.util.Set<Long> mAvatarFailLogged =
                java.util.Collections.synchronizedSet( new java.util.HashSet<Long>() );

        private static final Object AVATAR_RATE_LOCK = new Object();
        private static long sAvatarLastFetch;

        public void avatarFetch( final long steamid64 ) {
                Long key = steamid64;
                if( mAvatarFetched.contains( key )) return;

                final long account = steamid64 - 76561197960265728L;
                if( account <= 0 || account > 0xFFFFFFFFL ) return;

                final File dir = avatarDir();
                if( dir == null ) {
                        mAvatarFetched.add( key );
                        return;
                }

                final File out = new File( dir, "av_" + account + ".png" );
                mAvatarFetched.add( key );

                Thread t = new Thread( new Runnable() {
                        @Override public void run() {
                                try {
                                        // a fresh copy from an earlier session is reused
                                        // while it still decodes; steam avatars change, so
                                        // anything older than a day is pulled again, and
                                        // broken leftovers are not trusted either
                                        long stale = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
                                        if( out.isFile() && out.length() > 0 && out.lastModified() > stale
                                                && BitmapFactory.decodeFile( out.getAbsolutePath()) != null )
                                                return;

                                        // steamcommunity answers nothing on filtered
                                        // networks, so keep trying while the game runs
                                        // instead of writing the player off after one go
                                        for( int attempt = 0; attempt < 4; attempt++ ) {
                                                synchronized( AVATAR_RATE_LOCK ) {
                                                        long now = System.currentTimeMillis();
                                                        long wait = 300 - ( now - sAvatarLastFetch );
                                                        if( wait > 0 ) Thread.sleep( wait );
                                                        sAvatarLastFetch = System.currentTimeMillis();
                                                }

                                                String xml = httpGetString(
                                                        "https://steamcommunity.com/profiles/" + steamid64 + "/?xml=1" );
                                                String url = xml == null ? null : extractXmlTag( xml, "avatarFull" );

                                                // steamcommunity answers nothing on some
                                                // filtered networks (iran without a vpn);
                                                // playerdb.co mirrors the same photo
                                                if( url == null || url.length() == 0 ) {
                                                        String json = httpGetString(
                                                                "https://playerdb.co/api/player/steam/" + steamid64 );
                                                        url = json == null ? null : extractJsonString( json, "avatar" );
                                                }

                                                if( url != null && url.length() > 0 ) {
                                                        byte[] img = httpGetBytes( url );
                                                        if( img != null && img.length >= 64 ) {
                                                                Bitmap bmp = BitmapFactory.decodeByteArray( img, 0, img.length );
                                                                if( bmp != null ) {
                                                                        java.io.FileOutputStream fos = new java.io.FileOutputStream( out );
                                                                        bmp.compress( Bitmap.CompressFormat.PNG, 90, fos );
                                                                        fos.close();
                                                                        consolePrintf( "Avatar: saved steam avatar for account " + account );
                                                                        return;
                                                                }
                                                        }
                                                }

                                                Thread.sleep( 15000 );
                                        }
                                } catch( Throwable t ) {
                                        Log.w( TAG, "avatar fetch failed", t );
                                }

                                // not fetched this round: free the slot and try
                                // again in a minute, the rate limiter keeps it gentle
                                mAvatarFetched.remove( key );
                                if( mAvatarFailLogged.add( key ))
                                        consolePrintf( "Avatar: no photo for account " + account +
                                                " yet (network blocked? retrying every minute)" );
                                new android.os.Handler( android.os.Looper.getMainLooper()).postDelayed(
                                        new Runnable() {
                                                @Override public void run() {
                                                        avatarFetch( steamid64 );
                                                }
                                        }, 60000 );
                        }
                });
                t.setDaemon( true );
                t.start();
        }

        /** the customize menu's "Choose image..." button: open the
         *  system image chooser; the picked picture lands in this
         *  game's own media/avatars as avatar_custom.png and cl_avatar
         *  gets pointed at it, so valve/cstrike/czero each keep
         *  their own avatar */
        public void avatarPick() {
                runOnUiThread( new Runnable() {
                        @Override public void run() {
                                try {
                                        Intent pick = new Intent( Intent.ACTION_GET_CONTENT );
                                        pick.addCategory( Intent.CATEGORY_OPENABLE );
                                        pick.setType( "image/*" );
                                        startActivityForResult( Intent.createChooser( pick, "Choose avatar image" ), REQ_PICK_AVATAR );
                                } catch( Throwable t ) {
                                        Log.w( TAG, "avatar picker start failed", t );
                                }
                        }
                });
        }

        @Override
        protected void onActivityResult( int requestCode, int resultCode, Intent data ) {
                super.onActivityResult( requestCode, resultCode, data );

                if( requestCode != REQ_PICK_AVATAR || resultCode != RESULT_OK
                        || data == null || data.getData() == null )
                        return;

                final Uri uri = data.getData();

                Thread t = new Thread( new Runnable() {
                        @Override public void run() {
                                try {
                                        File dir = avatarDir();
                                        if( dir == null ) {
                                                consolePrintf( "Avatar: no writable media/avatars folder for this game" );
                                                return;
                                        }

                                        // decode with a sample bound so a 12mp
                                        // photo cannot blow the heap; the avatar
                                        // sprite is tiny anyway
                                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                                        bounds.inJustDecodeBounds = true;
                                        java.io.InputStream in = getContentResolver().openInputStream( uri );
                                        BitmapFactory.decodeStream( in, null, bounds );
                                        if( in != null ) try { in.close(); } catch( Throwable ignored ) {}

                                        int sample = 1;
                                        while( bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256 )
                                                sample *= 2;

                                        BitmapFactory.Options opts = new BitmapFactory.Options();
                                        opts.inSampleSize = sample;
                                        in = getContentResolver().openInputStream( uri );
                                        Bitmap bmp = BitmapFactory.decodeStream( in, null, opts );
                                        if( in != null ) try { in.close(); } catch( Throwable ignored ) {}

                                        if( bmp == null ) {
                                                consolePrintf( "Avatar: could not decode the picked image" );
                                                return;
                                        }

                                        File out = new File( dir, "avatar_custom.png" );
                                        java.io.FileOutputStream fos = new java.io.FileOutputStream( out );
                                        bmp.compress( Bitmap.CompressFormat.PNG, 90, fos );
                                        fos.close();
                                        bmp.recycle();

                                        consolePrintf( "Avatar: saved " + out.getName()
                                                + " into " + dir.getName() + ", pointing cl_avatar at it" );
                                        nativeAvatarPicked();
                                } catch( Throwable t ) {
                                        Log.w( TAG, "avatar pick failed", t );
                                        consolePrintf( "Avatar: picking the image failed: " + t );
                                }
                        }
                });
                t.setDaemon( true );
                t.start();
        }

        private File avatarDir() {
                try {
                        String base = mMotdBaseDir != null ? mMotdBaseDir
                                : Environment.getExternalStorageDirectory().getAbsolutePath() + "/xash";
                        String game = mMotdGameDir != null ? mMotdGameDir : "valve";
                        File dir = new File( base, game + "/media/avatars" );
                        if( !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
                                return null;
                        return dir;
                } catch( Throwable t ) {
                        return null;
                }
        }

        /** steam photos use the av_<account>.png naming; those age out
         *  after a day so the folder does not fill up with every
         *  teammate's picture forever - a player seen again just gets
         *  the photo pulled fresh. avatar_custom.png, the starter
         *  badges and av_default never match the pattern, and the file
         *  the user chose in the customize menu (cl_avatar inside
         *  config.cfg) survives even when it is a steam picture.
         *  returns how many went. */
        private int sweepStaleSteamAvatars( File dir, File[] files ) {
                int gone = 0;
                try {
                        if( files == null ) return 0;

                        long stale = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
                        String mine = ownedAvatarStem();

                        for( File f : files ) {
                                String n = f.getName().toLowerCase( java.util.Locale.US );
                                if( !n.matches( "av_[0-9]+\\.png" )) continue;
                                if( mine != null && mine.length() > 0 &&
                                        ( n.equals( mine ) || n.equals( mine + ".png" )))
                                        continue;
                                if( f.lastModified() >= stale ) continue;
                                if( f.delete()) gone++;
                        }
                } catch( Throwable t ) {
                        Log.w( TAG, "avatar sweep failed", t );
                }
                return gone;
        }

        /** the customize picker records its choice as cl_avatar inside
         *  the game's config.cfg; that name (with or without the png
         *  ending) counts as "mine" for the cleanup pass */
        private String ownedAvatarStem() {
                try {
                        File cfg = gameDirFile( "config.cfg" );
                        if( cfg == null || !cfg.isFile()) return null;

                        byte[] data = new byte[( int )Math.min( cfg.length(), 262144 )];
                        java.io.FileInputStream in = new java.io.FileInputStream( cfg );
                        int read = 0, n;
                        while( read < data.length && ( n = in.read( data, read, data.length - read )) > 0 )
                                read += n;
                        in.close();
                        if( read <= 0 ) return null;

                        String[] lines = new String( data, 0, read, "UTF-8" ).split( "\n" );
                        for( String line : lines ) {
                                line = line.trim();
                                if( !line.startsWith( "cl_avatar " ) && !line.startsWith( "cl_avatar\t" ))
                                        continue;

                                String rest = line.substring( "cl_avatar".length() ).trim();
                                if( rest.startsWith( "\"" ) && rest.endsWith( "\"" ) && rest.length() >= 2 )
                                        rest = rest.substring( 1, rest.length() - 1 );
                                else if( rest.indexOf( ' ' ) >= 0 )
                                        rest = rest.substring( 0, rest.indexOf( ' ' ));
                                rest = rest.trim();

                                int dot = rest.lastIndexOf( '.' );
                                if( dot > 0 && ( rest.endsWith( ".png" ) || rest.endsWith( ".jpg" ) ||
                                        rest.endsWith( ".bmp" )))
                                        rest = rest.substring( 0, dot );

                                return rest.toLowerCase( java.util.Locale.US );
                        }
                } catch( Throwable t ) {
                        Log.w( TAG, "cl_avatar read failed", t );
                }
                return null;
        }

        /** first run: drop a small set of starter badges into
         *  media/avatars so the customize picker always has something
         *  to offer and cl_avatar has a real file to point at. Anything
         *  the user drops in that folder later simply joins the list. */
        private void seedDefaultAvatars() {
                Thread t = new Thread( new Runnable() {
                        @Override public void run() {
                                try {
                                        File dir = avatarDir();
                                        if( dir == null ) return;

                                        // ensure each starter badge exists;
                                        // steam avatars landing in the same
                                        // folder no longer cancel the rest
                                        File[] have = dir.listFiles();
                                        java.util.Set<String> present =
                                                new java.util.HashSet<String>();
                                        if( have != null ) {
                                                for( File f : have )
                                                        present.add( f.getName().toLowerCase(
                                                                java.util.Locale.US ));
                                        }

                                        // every teammate's steam photo lands
                                        // in this folder and would sit there
                                        // forever; before seeding, the day-old
                                        // fetched ones go, the user's own pick
                                        // survives
                                        int swept = sweepStaleSteamAvatars( dir, have );
                                        if( swept > 0 )
                                                consolePrintf( "Avatar: cleaned " + swept +
                                                        " day-old steam avatar(s) from media/avatars" );

                                        String[] labels = { "CS", "16", "VIP", "PRO", "ACE", "TOP", "GG", "ZM" };
                                        int[] colors = {
                                                0xFF3D4A2A, 0xFF7A5C1E, 0xFF274435, 0xFF5A2A2A,
                                                0xFF2A3A5A, 0xFF4A2A5A, 0xFF5A4520, 0xFF30494B
                                        };

                                        boolean wrote = false;
                                        for( int i = 0; i < labels.length; i++ ) {
                                                String fileName = "logo_" +
                                                        labels[i].toLowerCase( java.util.Locale.US ) + ".png";
                                                if( present.contains( fileName ))
                                                        continue;

                                                Bitmap bmp = Bitmap.createBitmap( 128, 128, Bitmap.Config.ARGB_8888 );
                                                android.graphics.Canvas cv = new android.graphics.Canvas( bmp );
                                                Paint p = new Paint( Paint.ANTI_ALIAS_FLAG );
                                                android.graphics.RectF box = new android.graphics.RectF( 4, 4, 124, 124 );

                                                p.setColor( colors[i] );
                                                cv.drawRoundRect( box, 22, 22, p );

                                                p.setColor( 0xFFE8E2D0 );
                                                p.setStrokeWidth( 4 );
                                                p.setStyle( Paint.Style.STROKE );
                                                cv.drawRoundRect( box, 22, 22, p );

                                                p.setStyle( Paint.Style.FILL );
                                                p.setTextAlign( Paint.Align.CENTER );
                                                p.setTextSize( 50 );
                                                p.setFakeBoldText( true );
                                                Paint.FontMetrics fm = p.getFontMetrics();
                                                cv.drawText( labels[i], 64, 64 - ( fm.ascent + fm.descent ) / 2, p );

                                                java.io.FileOutputStream fos = new java.io.FileOutputStream(
                                                        new File( dir, fileName ));
                                                bmp.compress( Bitmap.CompressFormat.PNG, 90, fos );
                                                fos.close();
                                                bmp.recycle();
                                                wrote = true;
                                        }

                                        File def = new File( dir, "av_default.png" );
                                        if( !def.isFile() || def.length() == 0 ) {
                                                // steam's own default picture (bots, players
                                                // without a sid); pulled from the cdn when it
                                                // answers, drawn as the same gray silhouette
                                                // when the network blocks it - the slot must
                                                // never end up empty
                                                Bitmap bmp = null;
                                                try {
                                                        byte[] img = httpGetBytes(
                                                                "https://avatars.akamai.steamstatic.com/fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb_full.jpg" );
                                                        if( img != null && img.length > 64 )
                                                                bmp = BitmapFactory.decodeByteArray( img, 0, img.length );
                                                } catch( Throwable t2 ) {
                                                        Log.w( TAG, "default avatar fetch failed", t2 );
                                                }
                                                if( bmp == null )
                                                        bmp = drawDefaultAvatar();

                                                if( bmp != null ) {
                                                        java.io.FileOutputStream fos = new java.io.FileOutputStream( def );
                                                        bmp.compress( Bitmap.CompressFormat.PNG, 90, fos );
                                                        fos.close();
                                                        bmp.recycle();
                                                }
                                        }

                                        if( wrote )
                                                consolePrintf( "Avatar: seeded the starter set into media/avatars" );
                                } catch( Throwable t ) {
                                        Log.w( TAG, "avatar seed failed", t );
                                }
                        }
                });
                t.setDaemon( true );
                t.start();
        }

        private String httpGetString( String url ) {
                byte[] data = httpGetBytes( url );
                if( data == null ) return null;
                try {
                        return new String( data, "UTF-8" );
                } catch( Throwable t ) {
                        return null;
                }
        }

        private byte[] httpGetBytes( String url ) {
                java.net.HttpURLConnection c = null;
                try {
                        c = ( java.net.HttpURLConnection ) new java.net.URL( url ).openConnection();
                        c.setConnectTimeout( 8000 );
                        c.setReadTimeout( 10000 );
                        c.setInstanceFollowRedirects( true );
                        c.setRequestProperty( "User-Agent", "Mozilla/5.0 (Xash3D Android)" );

                        java.io.InputStream in = c.getInputStream();
                        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[16384];
                        int n;
                        while(( n = in.read( chunk )) > 0 ) {
                                buf.write( chunk, 0, n );
                                if( buf.size() > 8 * 1024 * 1024 ) break;
                        }
                        in.close();
                        return buf.toByteArray();
                } catch( Throwable t ) {
                        Log.w( TAG, "http get failed: " + url, t );
                        return null;
                } finally {
                        if( c != null ) try { c.disconnect(); } catch( Throwable ignored ) {}
                }
        }

        /** First <tag>...</tag> value of a small xml page, CDATA stripped. */
        private static String extractXmlTag( String xml, String tag ) {
                String open = "<" + tag + ">";
                String close = "</" + tag + ">";
                int a = xml.indexOf( open );
                int b = xml.indexOf( close, a >= 0 ? a : 0 );
                if( a < 0 || b < 0 ) return null;

                String v = xml.substring( a + open.length(), b ).trim();
                if( v.startsWith( "<![CDATA[" ) && v.endsWith( "]]>" ))
                        v = v.substring( 9, v.length() - 3 ).trim();
                return v;
        }

        private Drawable makeLoadingPanelBackground() {
                GradientDrawable d = new GradientDrawable();
                d.setColor( LOADING_PANEL_BG );
                d.setStroke( Math.max( 1, dp( 1 )), LOADING_PANEL_BORDER );
                return d;
        }

        private Drawable makeLoadingButtonBackground() {
                GradientDrawable normal = new GradientDrawable();
                normal.setColor( 0xFF4A5240 );
                normal.setStroke( Math.max( 1, dp( 1 )), 0xFFB4B8BC );

                GradientDrawable pressed = new GradientDrawable();
                pressed.setColor( 0xFF2C3227 );
                pressed.setStroke( Math.max( 1, dp( 1 )), LOADING_TITLE );

                StateListDrawable sld = new StateListDrawable();
                sld.addState( new int[] { android.R.attr.state_pressed }, pressed );
                sld.addState( new int[] { -android.R.attr.state_pressed }, normal );
                return sld;
        }

        private void showLoadingOnUiThread( String serverAddr ) {
                if( mLoadingOverlay != null )
                        hideLoadingOverlay();

                mLoadingServer = serverAddr != null ? serverAddr : "";

                FrameLayout overlay = new FrameLayout( this );
                overlay.setBackgroundColor( LOADING_SHADE );

                // ---- the classic centered dialog: olive box carrying
                // the "Loading..." title, the status line, the main
                // segmented bar, the download block (second bar with
                // Cancel beside it) and the server picture, all inside
                // one frame like the GoldSrc VGUI window ----
                LinearLayout dialog = new LinearLayout( this );
                dialog.setOrientation( LinearLayout.VERTICAL );
                dialog.setBackground( makeLoadingPanelBackground() );
                int dpad = dp( 12 );
                dialog.setPadding( dpad, dpad, dpad, dpad );

                int dlgW = Math.min( dp( 340 ),
                        getResources().getDisplayMetrics().widthPixels - dp( 32 ));
                FrameLayout.LayoutParams dialogLp = new FrameLayout.LayoutParams(
                        dlgW, ViewGroup.LayoutParams.WRAP_CONTENT );
                dialogLp.gravity = Gravity.CENTER;
                overlay.addView( dialog, dialogLp );

                LinearLayout titleRow = new LinearLayout( this );
                titleRow.setGravity( Gravity.CENTER_VERTICAL );

                ImageView logo = new ImageView( this );
                logo.setImageResource( R.drawable.cs_logo );
                logo.setScaleType( ImageView.ScaleType.FIT_CENTER );
                int logoSize = dp( 18 );
                LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams( logoSize, logoSize );
                logoLp.rightMargin = dp( 8 );
                titleRow.addView( logo, logoLp );

                TextView title = new TextView( this );
                title.setText( "Loading..." );
                title.setTextColor( LOADING_TITLE );
                title.setTextSize( TypedValue.COMPLEX_UNIT_SP, 17 );
                title.setTypeface( Typeface.DEFAULT_BOLD );
                titleRow.addView( title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT ));

                dialog.addView( titleRow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT ));

                TextView status = new TextView( this );
                status.setText( mLoadingServer.isEmpty() ? "Loading..."
                        : "Connecting to " + mLoadingServer + "..." );
                status.setTextColor( LOADING_TEXT );
                status.setTextSize( TypedValue.COMPLEX_UNIT_SP, 13 );
                status.setSingleLine( false );
                LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT );
                statusLp.topMargin = dp( 8 );
                dialog.addView( status, statusLp );
                mLoadingStatus = status;

                // bar plus the numeric percent, so the fill reads as a
                // number too while it climbs
                LinearLayout barRow = new LinearLayout( this );
                barRow.setGravity( Gravity.CENTER_VERTICAL );

                LoadingBar bar = new LoadingBar( this );
                barRow.addView( bar, new LinearLayout.LayoutParams(
                        0, dp( 18 ), 1f ));

                TextView pct = new TextView( this );
                pct.setText( "" );
                pct.setTextColor( LOADING_TITLE );
                pct.setTextSize( TypedValue.COMPLEX_UNIT_SP, 12 );
                pct.setTypeface( Typeface.DEFAULT_BOLD );
                pct.setMinWidth( dp( 36 ));
                pct.setGravity( Gravity.END );
                LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT );
                pctLp.leftMargin = dp( 8 );
                barRow.addView( pct, pctLp );

                LinearLayout.LayoutParams barRowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT );
                barRowLp.topMargin = dp( 8 );
                dialog.addView( barRow, barRowLp );
                mLoadingBar = bar;
                mLoadingPct = pct;
                mLoadingBarRow = barRow;

                // Cancel lives on this row while nothing transfers,
                // exactly where the classic window keeps it
                Button cancel = new Button( this );
                cancel.setText( "Cancel" );
                cancel.setAllCaps( false );
                cancel.setTextColor( LOADING_TITLE );
                cancel.setTextSize( TypedValue.COMPLEX_UNIT_SP, 13 );
                cancel.setBackground( makeLoadingButtonBackground() );
                cancel.setStateListAnimator( null );
                cancel.setElevation( 0f );
                cancel.setMinHeight( 0 );
                cancel.setPadding( dp( 10 ), 0, dp( 10 ), 0 );
                cancel.setOnClickListener( new View.OnClickListener() {
                        @Override public void onClick( View v ) {
                                hideLoadingOverlay();
                                nativeLoadingCancelled();
                        }
                });
                LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp( 26 ));
                cancelLp.leftMargin = dp( 8 );
                barRow.addView( cancel, cancelLp );
                mLoadingCancel = cancel;

                // ---- the download block: the file being transferred,
                // its status and the second yellow bar with the Cancel
                // button beside it, then the byte counter line - the
                // rows the pc dialog showed during a download. the
                // whole block stays hidden until the engine reports
                // the first active transfer ----
                TextView dlName = new TextView( this );
                dlName.setTextColor( LOADING_TITLE );
                dlName.setTextSize( TypedValue.COMPLEX_UNIT_SP, 13 );
                dlName.setSingleLine( false );
                dlName.setVisibility( View.GONE );
                LinearLayout.LayoutParams dlNameLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT );
                dlNameLp.topMargin = dp( 8 );
                dialog.addView( dlName, dlNameLp );
                mLoadingDlName = dlName;

                TextView dlStatus = new TextView( this );
                dlStatus.setTextColor( LOADING_TEXT );
                dlStatus.setTextSize( TypedValue.COMPLEX_UNIT_SP, 12 );
                dlStatus.setSingleLine( false );
                dlStatus.setVisibility( View.GONE );
                LinearLayout.LayoutParams dlStatusLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT );
                dlStatusLp.topMargin = dp( 2 );
                dialog.addView( dlStatus, dlStatusLp );
                mLoadingDlStatus = dlStatus;

                LinearLayout dlRow = new LinearLayout( this );
                dlRow.setGravity( Gravity.CENTER_VERTICAL );
                dlRow.setVisibility( View.GONE );

                LoadingBar dlBar = new LoadingBar( this );
                dlRow.addView( dlBar, new LinearLayout.LayoutParams(
                        0, dp( 16 ), 1f ));
                mLoadingDlBar = dlBar;

                // when a transfer runs, the Cancel button moves from
                // the bar row down here, beside the transfer bar -
                // ref1 of the pc dialog shows it on the same row

                LinearLayout.LayoutParams dlRowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT );
                dlRowLp.topMargin = dp( 8 );
                dialog.addView( dlRow, dlRowLp );
                mLoadingDlRow = dlRow;

                TextView dlFooter = new TextView( this );
                dlFooter.setTextColor( LOADING_TEXT );
                dlFooter.setTextSize( TypedValue.COMPLEX_UNIT_SP, 11 );
                dlFooter.setVisibility( View.GONE );
                LinearLayout.LayoutParams dlFooterLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT );
                dlFooterLp.topMargin = dp( 4 );
                dialog.addView( dlFooter, dlFooterLp );
                mLoadingDlFooter = dlFooter;

                // server banner slot: the classic loading-banner plugins
                // drop resource/LoadingDialog.res next to the game files;
                // if it is on disk we show its picture under the bars,
                // exactly where the pc client put it. stays hidden until
                // a picture actually loads.
                ImageView banner = new ImageView( this );
                banner.setVisibility( View.GONE );
                banner.setScaleType( ImageView.ScaleType.FIT_CENTER );
                banner.setAdjustViewBounds( true );
                LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT );
                bannerLp.topMargin = dp( 8 );
                dialog.addView( banner, bannerLp );
                mLoadingBanner = banner;

                // assign before the reader thread starts, its ui callback
                // checks this exact reference before touching the view
                mLoadingOverlay = overlay;
                loadLoadingBanner( overlay, banner );

                addContentView( overlay, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT ));
        }

        private void hideLoadingOverlay() {
                if( mLoadingOverlay == null ) return;

                FrameLayout overlay = mLoadingOverlay;
                mLoadingOverlay = null;
                mLoadingBar = null;
                mLoadingStatus = null;
                mLoadingPct = null;
                mLoadingDlName = null;
                mLoadingDlStatus = null;
                mLoadingDlBar = null;
                mLoadingDlFooter = null;
                mLoadingBanner = null;
                mLoadingBarRow = null;
                mLoadingDlRow = null;
                mLoadingCancel = null;

                try {
                        ViewGroup parent = ( ViewGroup )overlay.getParent();
                        if( parent != null ) parent.removeView( overlay );
                } catch( Throwable t ) {
                        Log.w( TAG, "loading overlay remove failed", t );
                }
        }

        /** The classic server loading banner: the plugin writes the
         *  vgui template into resource/LoadingDialog.res (and friends)
         *  with motdfile/motd_write and the pc client shows the image
         *  under the progress bar. Read it the same way here. */
        private void loadLoadingBanner( final FrameLayout overlay, final ImageView view ) {
                Thread t = new Thread( new Runnable() {
                        @Override public void run() {
                                Bitmap bmp = null;
                                try {
                                        String[] names = { "resource/LoadingDialog.res",
                                                "resource/LoadingDialogNoBanner.res",
                                                "resource/LoadingDialogVAC.res" };
                                        boolean anyFile = false;
                                        for( String name : names ) {
                                                File f = gameDirFile( name );
                                                if( f == null || !f.isFile()) continue;
                                                anyFile = true;
                                                String res = readSmallFile( f );
                                                String image = res == null ? null : extractResValue( res, "image" );
                                                // say what the template carries, so a
                                                // server banner that fails to show can
                                                // be traced from the console alone
                                                consolePrintf( "Loading banner: " + name + " (" +
                                                        f.length() + " bytes)" +
                                                        ( image != null ? ", image \"" + image + "\"" :
                                                                ", no image key" ));
                                                if( image == null || image.length() == 0 ) continue;
                                                bmp = resolveLoadingBannerImage( image );
                                                if( bmp != null ) break;
                                        }

                                        // the plugins write the template while
                                        // connecting, so the first load after
                                        // joining a banner server cannot have it
                                        if( bmp == null && !anyFile )
                                                consolePrintf( "Loading banner: resource/LoadingDialog.res not written yet - reconnect and it shows" );
                                } catch( Throwable t ) {
                                        Log.w( TAG, "loading banner read failed", t );
                                }

                                final Bitmap fb = bmp;
                                if( fb == null ) return;
                                runOnUiThread( new Runnable() {
                                        @Override public void run() {
                                                try {
                                                        if( mLoadingOverlay != overlay ) return;
                                                        view.setImageBitmap( fb );
                                                        view.setVisibility( View.VISIBLE );
                                                } catch( Throwable ignored ) {}
                                        }
                                });
                        }
                });
                t.setDaemon( true );
                t.start();
        }

        /** resolve the image path from the .res against the game dir
         *  and the <game>_downloads tree (that is where the tga lands
         *  after the precache download); the pc templates carry the
         *  path without the extension */
        private Bitmap resolveLoadingBannerImage( String image ) {
                String[] exts = { "", ".tga", ".bmp", ".png", ".jpg" };
                boolean anyFile = false;
                for( String ext : exts ) {
                        String path = image + ext;
                        File f = gameDirFile( path );
                        if( f == null || !f.isFile())
                                f = downloadDirFile( path );
                        if( f != null && f.isFile()) {
                                anyFile = true;
                                Bitmap bmp = decodeImageFile( f );
                                if( bmp != null ) {
                                        consolePrintf( "Loading banner: showing " + f.getAbsolutePath() );
                                        return bmp;
                                }
                                // found but undecodable (odd tga flavour?) -
                                // keep trying the other extensions
                                consolePrintf( "Loading banner: " + f.getName() + " found but cannot be decoded" );
                        }
                }
                if( !anyFile )
                        consolePrintf( "Loading banner: picture \"" + image +
                                "\" is not under the game or the _downloads folder" );
                return null;
        }

        /** game-relative path inside the <game>_downloads tree where
         *  the engine stores files the server made us download */
        private File downloadDirFile( String relPath ) {
                try {
                        String base = mMotdBaseDir != null ? mMotdBaseDir
                                : Environment.getExternalStorageDirectory().getAbsolutePath() + "/xash";
                        String game = mMotdGameDir != null ? mMotdGameDir : "valve";
                        File f = new File( base, game + "_downloads/" + relPath );
                        return f.getCanonicalPath().startsWith( new File( base ).getCanonicalPath() ) ? f : null;
                } catch( Throwable t ) {
                        return null;
                }
        }

        private static String readSmallFile( File f ) {
                try {
                        long len = Math.min( f.length(), 8192 );
                        byte[] buf = new byte[( int )len];
                        java.io.FileInputStream in = new java.io.FileInputStream( f );
                        int read = 0, n;
                        while( read < buf.length && ( n = in.read( buf, read, buf.length - read )) > 0 )
                                read += n;
                        in.close();
                        if( read <= 0 ) return null;
                        return new String( buf, 0, read, "UTF-8" );
                } catch( Throwable t ) {
                        return null;
                }
        }

        /** one "key" "value" pair out of a one-line vgui .res */
        private static String extractResValue( String s, String key ) {
                String needle = "\"" + key + "\"";
                int i = s.indexOf( needle );
                if( i < 0 ) return null;
                i = s.indexOf( '"', i + needle.length() );
                if( i < 0 ) return null;
                int e = s.indexOf( '"', i + 1 );
                if( e < 0 ) return null;
                return s.substring( i + 1, e );
        }

        /** one plain string field out of a small json reply, with the
         *  escapes the steam cdn urls actually use */
        private static String extractJsonString( String json, String field ) {
                try {
                        String needle = "\"" + field + "\"";
                        int i = json.indexOf( needle );
                        if( i < 0 ) return null;
                        i = json.indexOf( ':', i + needle.length() );
                        if( i < 0 ) return null;
                        i = json.indexOf( '"', i + 1 );
                        if( i < 0 ) return null;
                        StringBuilder b = new StringBuilder();
                        for( int e = i + 1; e < json.length(); e++ ) {
                                char c = json.charAt( e );
                                if( c == '\\' && e + 1 < json.length() ) {
                                        char n2 = json.charAt( ++e );
                                        if( n2 == 'u' ) {
                                                b.appendCodePoint( Integer.parseInt( json.substring( e + 1, e + 5 ), 16 ));
                                                e += 4;
                                        } else if( n2 == '/' ) b.append( '/' );
                                        else b.append( n2 );
                                        continue;
                                }
                                if( c == '"' ) return b.toString();
                                b.append( c );
                        }
                } catch( Throwable ignored ) {}
                return null;
        }

        /** the steam-style gray silhouette for players the photo
         *  sources can not answer for, drawn locally so the avatar
         *  slot never ends up empty */
        private static Bitmap drawDefaultAvatar() {
                try {
                        Bitmap bmp = Bitmap.createBitmap( 184, 184, Bitmap.Config.ARGB_8888 );
                        android.graphics.Canvas cv = new android.graphics.Canvas( bmp );
                        Paint p = new Paint( Paint.ANTI_ALIAS_FLAG );

                        p.setColor( 0xFF8F989B );
                        cv.drawRect( 0, 0, 184, 184, p );

                        p.setColor( 0xFFDCDEE0 );
                        cv.drawCircle( 92, 66, 34, p );

                        android.graphics.RectF body = new android.graphics.RectF( 18, 116, 166, 260 );
                        cv.drawOval( body, p );

                        return bmp;
                } catch( Throwable t ) {
                        return null;
                }
        }

}
