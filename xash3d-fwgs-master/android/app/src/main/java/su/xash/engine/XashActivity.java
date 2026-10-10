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
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

                        // the retail client builds this window from
                        // resource/UI/MOTD.res (valve fallback:
                        // TextWindow.res): ClientMOTD frame, serverName
                        // label, Message panel, ok button - all laid out
                        // from the file the same way the pc gameui does
                        ResBlock motdRes = null;
                        String[] motdPaths = { "resource/UI/MOTD.res", "resource/UI/TextWindow.res" };

                        for( String mp : motdPaths ) {
                                File mf = resGameFile( mp );

                                if( mf == null ) continue;

                                String mtext = readTextFile( mf, 128 * 1024 );

                                if( mtext == null ) continue;

                                motdRes = resParse( mtext );

                                if( motdRes != null ) break;
                        }

                        LinkedHashMap<String,ResBlock> mctl = new LinkedHashMap<String,ResBlock>();
                        collectControls( motdRes, 0, mctl );
                        ResBlock frameCtl = mctl.get( "clientmotd" );
                        ResBlock nameCtl = mctl.get( "servername" );
                        ResBlock msgCtl = mctl.get( "message" );
                        ResBlock okCtl = mctl.get( "ok" );

                        // the vgui surface the dialogs are authored on
                        float resSX = screenW / 640f;
                        float resSY = screenH / 480f;

                        // 1:1 to the retail Steam CS 1.6 MOTD window when
                        // no resource ships (pixel-measured from the
                        // retail window): ~71.5% of the screen width x
                        // ~90% of the height, black at ~76% opacity (the
                        // game faintly shows through), Valve LineBorder
                        // (178,119,0) around it.
                        int panelW = frameCtl != null
                                ? Math.max( dp( 200 ), Math.round( resInt( frameCtl.str( "wide" ), 552 ) * resSX ))
                                : Math.max( dp( 200 ), Math.round( screenW * 0.715f ));
                        int panelH = frameCtl != null
                                ? Math.max( dp( 140 ), Math.round( resInt( frameCtl.str( "tall" ), 448 ) * resSY ))
                                : Math.max( dp( 140 ), Math.round( screenH * 0.90f ));
                        int panelX = frameCtl != null
                                ? resPos( frameCtl.str( "xpos" ), 44, screenW, resSX )
                                : ( screenW - panelW ) / 2;
                        int panelY = frameCtl != null
                                ? resPos( frameCtl.str( "ypos" ), 0, screenH, resSY )
                                : ( screenH - panelH ) / 2;

                        // the scheme colors every pc dialog texts itself
                        // with (cstrike amber, valve pale gray-green)
                        loadLoadingTokens();
                        loadLoadingScheme();
                        int motdText = schemeColor( "ControlText", MOTD_VGUI_TEXT );
                        int motdBorder = schemeSetting( "FgColor", MOTD_VGUI_BORDER );

                        LinearLayout panel = new LinearLayout( this );
                        panel.setOrientation( LinearLayout.VERTICAL );
                        panel.setBackground( makeMOTDWindowBackground( motdBorder ));
                        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams( panelW, panelH );
                        panelLp.gravity = Gravity.TOP | Gravity.START;
                        panelLp.leftMargin = panelX;
                        panelLp.topMargin = panelY;
                        root.addView( panel, panelLp );

                        // title row like the reference — the CS soldier
                        // logo left, "Title Font" caption (scheme
                        // ControlText color), sitting directly on the
                        // window background (no bar strip; the game shows
                        // through it), with a thin light separator line
                        // along its bottom edge. The band height is the
                        // serverName label of the resource.
                        int titleH = nameCtl != null
                                ? Math.max( dp( 28 ), Math.round( resInt( nameCtl.str( "tall" ), 48 ) * resSY ))
                                : Math.round( panelH * 0.125f );
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
                        titleView.setTextColor( motdText );
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

                        if( msgCtl != null ) {
                                // the Message panel rect of the resource,
                                // panel-relative margins
                                int msgX = resPos( msgCtl.str( "xpos" ), 0, screenW, resSX );
                                int msgY = resPos( msgCtl.str( "ypos" ), 116, screenH, resSY );
                                int msgW = Math.max( dp( 60 ), Math.round( resInt( msgCtl.str( "wide" ), 480 ) * resSX ));
                                int left = Math.max( 0, msgX - panelX );
                                int top = Math.max( 0, msgY - panelY - titleH - dp( 1 ));
                                int right = Math.max( 0, ( panelX + panelW ) - ( msgX + msgW ));
                                contentLp.setMargins( left, top, right, 0 );
                        } else {
                                contentLp.setMargins( Math.round( panelW * 0.023f ), Math.round( panelH * 0.085f ),
                                                Math.round( panelW * 0.023f ), 0 );
                        }
                        panel.addView( contentWrap, contentLp );

                        // --- the reference OK button — small, bottom-left
                        // like the resource's ok control (128x20 at the
                        // bottom of the stock file), near-black body with
                        // a thin LIGHT-GRAY frame (the VGUI CommandButton
                        // look) and the scheme text color; the empty
                        // window band below it matches the reference too.
                        int okW = okCtl != null
                                ? Math.max( dp( 48 ), Math.round( resInt( okCtl.str( "wide" ), 128 ) * resSX ))
                                : Math.max( dp( 64 ), Math.round( panelW * 0.205f ));
                        int okH = okCtl != null
                                ? Math.max( dp( 18 ), Math.round( resInt( okCtl.str( "tall" ), 20 ) * resSY ))
                                : Math.max( dp( 22 ), Math.round( panelH * 0.046f ));

                        Button ok = new Button( this );
                        String okLabel = okCtl != null ? token( okCtl.str( "labelText" )) : null;
                        ok.setText(( okLabel != null && !okLabel.isEmpty() && !okLabel.startsWith( "#" ))
                                ? okLabel : "OK" );
                        ok.setAllCaps( false );
                        ok.setTextColor( motdText );
                        ok.setTextSize( TypedValue.COMPLEX_UNIT_PX, Math.round( okH * 0.5f ));
                        ok.setBackground( makeMOTDButtonBackground( motdText ));
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

                        if( okCtl != null ) {
                                // the resource places the ok control from
                                // the bottom of the frame; the content
                                // weight above absorbs the rest, so only
                                // the bottom band matters here
                                int okYRel = Math.max( 0, resPos( okCtl.str( "ypos" ), 364, screenH, resSY ) - panelY );
                                int okHRes = Math.round( resInt( okCtl.str( "tall" ), 20 ) * resSY );
                                okLp.setMargins( Math.max( dp( 4 ), Math.max( 0, resPos( okCtl.str( "xpos" ), 0, screenW, resSX ) - panelX )),
                                        0, 0,
                                        Math.max( 0, panelH - okYRel - okHRes ));
                        } else {
                                okLp.setMargins( Math.round( panelW * 0.112f ), Math.round( panelH * 0.02f ),
                                                0, Math.round( panelH * 0.16f ));
                        }
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
         *  with the scheme FgColor line border (Valve's 1px LineBorder
         *  178,119,0 amber on cstrike). */
        private Drawable makeMOTDWindowBackground( int borderColor ) {
                GradientDrawable d = new GradientDrawable();
                d.setColor( 0xC2000000 );
                d.setStroke( Math.max( 1, dp( 1 )), borderColor );
                return d;
        }

        /** The small command button — near-black body, thin light-gray
         *  frame (the VGUI CommandButton look), scheme accent when
         *  pressed. */
        private StateListDrawable makeMOTDButtonBackground( int accent ) {
                GradientDrawable normal = new GradientDrawable();
                normal.setColor( 0xE6000000 );
                normal.setStroke( Math.max( 1, dp( 1 )), 0xFFC8C4BC );

                GradientDrawable pressed = new GradientDrawable();
                pressed.setColor( 0xFF3A2E10 );
                pressed.setStroke( Math.max( 1, dp( 1 )), accent );

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
        // CS 1.6 loading window, built from the game's own vgui resources
        //
        // the engine raises it through the loading plaque (connect,
        // changelevel) and drives both bars from the resource downloader:
        // the main bar for the whole pipeline and, only while a transfer
        // runs, the Progress2 row below it (file name, its own fill,
        // counters) with Cancel beside it. The dialog itself, its colors,
        // its texts and the tiled background come out of resource/
        // LoadingDialog.res, ClientScheme.res, the language files and
        // BackgroundLoadingLayout.txt - see the .res section below.
        // Cancel asks the engine to disconnect.
        // ------------------------------------------------------------------

        // fallbacks for when the game ships none of those resources
        private static final int LOADING_SHADE = 0xE6000000;
        private static final int LOADING_PANEL_BG = 0xFF3E4637;
        private static final int LOADING_PANEL_BORDER = 0xFF9AA391;
        private static final int LOADING_TITLE = 0xFFE6EBDD;
        private static final int LOADING_TEXT = 0xFFC9CFC0;
        private static final int LOADING_BAR_TRACK = 0xFF2C3227;
        private static final int LOADING_BAR_BORDER = 0xFF8C9484;
        private static final int LOADING_BAR_FILL = 0xFFDDBE43;

        private FrameLayout mLoadingOverlay;
        private FrameLayout mLoadingDialogView;

        // the pc dialog reloads its .res when the download phase starts
        // and ends; this tracks which variant is on screen right now
        private boolean mLoadingDual;

        private LoadingBar mLoadingBar;
        private TextView mLoadingStatus;
        private Button mLoadingCancel;

        // the Progress2 row of the dialog: the file the server is
        // sending, its own bar and the time/counter line
        private LoadingBar mLoadingDlBar;
        private TextView mLoadingDlName;
        private TextView mLoadingDlStatus;

        // the last values the engine sent, reapplied when the dialog is
        // rebuilt between the plain and the DualProgress variant
        private String mStatusText;
        private String mDlNameText;
        private String mDlStatusText;
        private String mDlFooterText;
        private float mPct = -1f;
        private float mDlPct = -1f;

        // parsed game resources (tokens, ClientScheme colors)
        private HashMap<String,String> mLoadingTokens;
        private HashMap<String,Integer> mSchemeColors;
        private ResBlock mLoadingScheme;

        /** The segmented progress bar of the loading dialog, colored from
         *  ClientScheme and carrying the numeric percent in its right end.
         *  percent < 0 means "busy, nothing measured yet" and draws it as is. */
        private static class LoadingBar extends View {
                private float mPercent = -1f;
                private String mLabel = "";
                private int mTrack = LOADING_BAR_TRACK;
                private int mBorder = LOADING_BAR_BORDER;
                private int mFill = LOADING_BAR_FILL;

                LoadingBar( Context c ) { super( c ); }

                void setColors( int track, int border, int fill ) {
                        mTrack = track;
                        mBorder = border;
                        mFill = fill;
                        invalidate();
                }

                void setPercent( float p ) {
                        if( mPercent == p ) return;
                        mPercent = p;
                        invalidate();
                }

                void setLabel( String l ) {
                        String n = l != null ? l : "";
                        if( n.equals( mLabel )) return;
                        mLabel = n;
                        invalidate();
                }

                @Override
                protected void onDraw( Canvas canvas ) {
                        float w = getWidth(), h = getHeight();
                        Paint p = new Paint();

                        p.setColor( mTrack );
                        canvas.drawRect( 0, 0, w, h, p );

                        final int segments = 18;
                        float gap = Math.max( 2, w * 0.012f );
                        float segW = ( w - gap * ( segments + 1 )) / segments;
                        float insetY = Math.max( 2, h * 0.16f );

                        int filled = 0;
                        if( mPercent >= 0 )
                                filled = Math.round( segments * Math.max( 0f, Math.min( 1f, mPercent / 100f )));

                        p.setColor( mFill );
                        for( int i = 0; i < filled; i++ ) {
                                float x = gap + i * ( segW + gap );
                                canvas.drawRect( x, insetY, x + segW, h - insetY, p );
                        }

                        p.setColor( mBorder );
                        p.setStrokeWidth( Math.max( 1, getResources().getDisplayMetrics().density ));
                        p.setStyle( Paint.Style.STROKE );
                        canvas.drawRect( 0, 0, w, h, p );

                        // the numeric percent, on a dark chip so it reads
                        // over the yellow segments too
                        if( mLabel.length() > 0 && mPercent >= 0 ) {
                                p.setStyle( Paint.Style.FILL );
                                p.setTextSize( h * 0.58f );
                                p.setTextAlign( Paint.Align.RIGHT );
                                float tw = p.measureText( mLabel );
                                float tx = w - h * 0.14f;
                                p.setColor( 0x96000000 );
                                canvas.drawRect( tx - tw - h * 0.22f, h * 0.12f, w - h * 0.06f, h * 0.88f, p );
                                p.setColor( 0xFFFFFFFF );
                                float ty = h * 0.5f - ( p.descent() + p.ascent() ) * 0.5f;
                                canvas.drawText( mLabel, tx, ty, p );
                        }
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
                                        if( text != null && text.length() > 0 ) mStatusText = text;
                                        if( mLoadingStatus != null && mStatusText != null &&
                                                !mStatusText.contentEquals( mLoadingStatus.getText() ))
                                                mLoadingStatus.setText( mStatusText );
                                        if( mLoadingBar != null ) {
                                                // percent < 0 means "busy, nothing measured":
                                                // the bar keeps whatever it already shows
                                                if( percent >= 0f ) {
                                                        mPct = percent;
                                                        mLoadingBar.setPercent( mPct );
                                                        mLoadingBar.setLabel( Math.round( mPct ) + "%" );
                                                } else mLoadingBar.setLabel( "" );
                                        }
                                } catch( Throwable t ) {
                                        Log.w( TAG, "loadingStatus failed", t );
                                }
                        }
                });
        }

        /** the Progress2 row of the dialog: the engine calls it while a
         *  transfer runs with the file name, that file's fill (0..100)
         *  and the counter lines around it. a null file folds the block
         *  away - on the pc the dialog reloads its plain .res then and
         *  the Cancel button sits beside the main bar again. */
        public void loadingDownload( final String file, final float percent,
                final String status, final String footer ) {
                runOnUiThread( new Runnable() {
                        @Override public void run() {
                                if( mLoadingOverlay == null ) return;
                                try {
                                        boolean active = file != null && file.length() > 0;

                                        if( active ) {
                                                mDlNameText = file;
                                                if( status != null && status.length() > 0 ) mDlStatusText = status;
                                                mDlFooterText = footer != null && footer.length() > 0 ? footer : null;
                                                if( percent >= 0f ) mDlPct = percent;
                                        }

                                        // the pc client reloads the dialog .res when
                                        // the download phase starts and when it ends
                                        if( mLoadingDual != active )
                                                rebuildLoadingDialog( active );

                                        if( !active ) {
                                                mDlNameText = null;
                                                mDlStatusText = null;
                                                mDlFooterText = null;
                                                mDlPct = -1f;
                                                return;
                                        }

                                        if( mLoadingDlName != null && mDlNameText != null &&
                                                !mDlNameText.contentEquals( mLoadingDlName.getText() ))
                                                mLoadingDlName.setText( mDlNameText );

                                        if( mLoadingDlStatus != null ) {
                                                String t = mDlStatusText;
                                                if( mDlFooterText != null )
                                                        t = ( t != null ? t + "  " : "" ) + mDlFooterText;
                                                if( t != null && !t.contentEquals( mLoadingDlStatus.getText() ))
                                                        mLoadingDlStatus.setText( t );
                                        }

                                        if( mLoadingDlBar != null && percent >= 0f ) {
                                                mLoadingDlBar.setPercent( mDlPct );
                                                mLoadingDlBar.setLabel( Math.round( mDlPct ) + "%" );
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

        private Drawable makeLoadingPanelBackground( int bg, int border ) {
                GradientDrawable d = new GradientDrawable();
                d.setColor( bg );
                d.setStroke( Math.max( 1, dp( 1 )), border );
                return d;
        }

        private Drawable makeLoadingButtonBackground( int bg, int border, int borderBright ) {
                GradientDrawable normal = new GradientDrawable();
                normal.setColor( solidify( bg, 0xFF4A5240 ));
                normal.setStroke( Math.max( 1, dp( 1 )), border );

                GradientDrawable pressed = new GradientDrawable();
                pressed.setColor( 0xFF2C3227 );
                pressed.setStroke( Math.max( 1, dp( 1 )), borderBright );

                StateListDrawable sld = new StateListDrawable();
                sld.addState( new int[] { android.R.attr.state_pressed }, pressed );
                sld.addState( new int[] { -android.R.attr.state_pressed }, normal );
                return sld;
        }

        /** flatten a translucent scheme color over the dark dialog
         *  background so buttons and bars do not look hollow */
        private static int solidify( int c, int fallback ) {
                int a = Color.alpha( c );
                if( a >= 250 ) return c;
                if( a < 8 ) return fallback;
                int r = ( Color.red( c ) * a + 26 * ( 255 - a )) / 255;
                int g = ( Color.green( c ) * a + 29 * ( 255 - a )) / 255;
                int b = ( Color.blue( c ) * a + 23 * ( 255 - a )) / 255;
                return Color.argb( 255, r, g, b );
        }

        // =====================================================================
        // the vgui resources the loading window is built from
        //
        // the pc gameui reads resource/LoadingDialog.res (and
        // resource/LoadingDialogDualProgress.res while something downloads)
        // out of the mod dir with valve/ as the fallback, colors from
        // resource/ClientScheme.res, texts from the <mod>_english.txt
        // language files and the tiled background from
        // resource/BackgroundLoadingLayout.txt. all of that is read here
        // the same way, so mods and servers can skin the window exactly
        // like they can on the pc - the loading-banner plugins overwrite
        // the dialog .res with their image and it shows up here too.
        // =====================================================================

        /** one vgui keyvalues block: string leaves or nested blocks */
        private static final class ResBlock {
                final LinkedHashMap<String,Object> entries = new LinkedHashMap<String,Object>();

                String str( String key ) {
                        Object o = entries.get( key );
                        return o instanceof String ? ( String )o : null;
                }

                ResBlock block( String key ) {
                        Object o = entries.get( key );
                        return o instanceof ResBlock ? ( ResBlock )o : null;
                }
        }

        private static ResBlock resParse( String s ) {
                try {
                        int[] pos = { 0 };
                        ResBlock root = new ResBlock();
                        resParseBlock( s, pos, root );
                        return root.entries.isEmpty() ? null : root;
                } catch( Throwable t ) {
                        return null;
                }
        }

        private static void resParseBlock( String s, int[] pos, ResBlock into ) {
                for( ;; ) {
                        String key = resToken( s, pos );
                        if( key == null || key.equals( "}" )) return;
                        String val = resToken( s, pos );
                        if( val == null ) {
                                into.entries.put( key, key );
                                return;
                        }
                        if( val.equals( "{" )) {
                                ResBlock child = new ResBlock();
                                resParseBlock( s, pos, child );
                                into.entries.put( key, child );
                        } else if( val.equals( "}" )) {
                                into.entries.put( key, key );
                                return;
                        } else {
                                into.entries.put( key, val );
                        }
                }
        }

        /** one vgui token: quoted (with \x escapes) or bare, comments
         *  skipped, { and } are tokens of their own - this also eats the
         *  whitespace-free one-liners the server plugins motd_write */
        private static String resToken( String s, int[] pos ) {
                int n = s.length(), i = pos[0];
                for( ;; ) {
                        while( i < n && Character.isWhitespace( s.charAt( i ))) i++;
                        if( i >= n ) {
                                pos[0] = i;
                                return null;
                        }
                        char c = s.charAt( i );
                        if( c == '/' && i + 1 < n && s.charAt( i + 1 ) == '/' ) {
                                while( i < n && s.charAt( i ) != '\n' ) i++;
                                continue;
                        }
                        if( c == '/' && i + 1 < n && s.charAt( i + 1 ) == '*' ) {
                                i += 2;
                                while( i + 1 < n && !( s.charAt( i ) == '*' && s.charAt( i + 1 ) == '/' )) i++;
                                i = Math.min( n, i + 2 );
                                continue;
                        }
                        if( c == '{' || c == '}' ) {
                                pos[0] = i + 1;
                                return String.valueOf( c );
                        }
                        if( c == '"' ) {
                                i++;
                                StringBuilder b = new StringBuilder();
                                while( i < n ) {
                                        char d = s.charAt( i );
                                        if( d == '\\' && i + 1 < n ) {
                                                b.append( s.charAt( i + 1 ));
                                                i += 2;
                                                continue;
                                        }
                                        if( d == '"' ) {
                                                i++;
                                                break;
                                        }
                                        b.append( d );
                                        i++;
                                }
                                pos[0] = i;
                                return b.toString();
                        }
                        int st = i;
                        while( i < n && !Character.isWhitespace( s.charAt( i )) && s.charAt( i ) != '"'
                                && s.charAt( i ) != '{' && s.charAt( i ) != '}' ) i++;
                        pos[0] = i;
                        return s.substring( st, i );
                }
        }

        /** text file with the vgui encodings: the language files are
         *  utf-16 with a bom, the .res files are plain utf-8 */
        private static String readTextFile( File f, int cap ) {
                try {
                        long len = Math.min( f.length(), cap );
                        byte[] buf = new byte[( int )len];
                        FileInputStream in = new FileInputStream( f );
                        int read = 0, n;
                        while( read < buf.length && ( n = in.read( buf, read, buf.length - read )) > 0 )
                                read += n;
                        in.close();
                        if( read <= 2 ) return null;
                        if(( buf[0] & 0xFF ) == 0xFF && ( buf[1] & 0xFF ) == 0xFE )
                                return new String( buf, 2, ( read - 2 ) & ~1, "UTF-16LE" );
                        if(( buf[0] & 0xFF ) == 0xFE && ( buf[1] & 0xFF ) == 0xFF )
                                return new String( buf, 2, ( read - 2 ) & ~1, "UTF-16BE" );
                        return new String( buf, 0, read, "UTF-8" );
                } catch( Throwable t ) {
                        return null;
                }
        }

        /** resolve a game-relative resource against the mod dir with the
         *  valve/ fallback, the way the engine filesystem does it */
        private File resGameFile( String relPath ) {
                File f = gameDirFile( relPath );
                if( f != null && f.isFile()) return f;
                f = valveDirFile( relPath );
                return f != null && f.isFile() ? f : null;
        }

        private File valveDirFile( String relPath ) {
                try {
                        String base = mMotdBaseDir != null ? mMotdBaseDir
                                : Environment.getExternalStorageDirectory().getAbsolutePath() + "/xash";
                        File f = new File( base, "valve/" + relPath );
                        return f.getCanonicalPath().startsWith( new File( base, "valve" ).getCanonicalPath() ) ? f : null;
                } catch( Throwable t ) {
                        return null;
                }
        }

        private static String firstOf( String a, String b ) {
                return a != null ? a : b;
        }

        private static int resInt( String v, int def ) {
                try {
                        return Integer.parseInt( v.trim() );
                } catch( Throwable t ) {
                        return def;
                }
        }

        /** vgui control position: plain pixels ("288") or anchored
         *  ("c-190", "r0", "b10"), scaled like everything else */
        private static int resPos( String v, int def, int span, float s ) {
                if( v == null || v.length() == 0 ) return Math.round( def * s );
                v = v.trim();
                char a = Character.toLowerCase( v.charAt( 0 ));
                if( a == 'c' || a == 'r' || a == 'b' ) {
                        int off = Math.round( resInt( v.substring( 1 ), 0 ) * s );
                        if( a == 'c' ) return Math.max( 0, span / 2 + off );
                        return Math.max( 0, span + off );
                }
                return Math.round( resInt( v, def ) * s );
        }

        // the tokens the dialog labels use when the language files ship
        // none of them
        private static final String[][] LOADING_FALLBACK_TOKENS = {
                { "GameUI_Loading", "Loading..." },
                { "GameUI_Cancel", "Cancel" },
                { "GameUI_Close", "Close" },
                { "GameUI_ParseBaseline", "Parsing game info..." },
                { "GameUI_VerifyingAndDownloading", "Verifying and downloading resources..." },
                { "GameUI_PrecachingResources", "Precaching resources..." },
                { "GameUI_Disconnected", "Disconnected" }
        };

        /** language tokens from <mod>_english.txt with gameui_english.txt
         *  as the fallback - both are utf-16 "lang" files */
        private void loadLoadingTokens() {
                HashMap<String,String> out = new HashMap<String,String>();
                HashSet<String> real = new HashSet<String>();
                for( String[] t : LOADING_FALLBACK_TOKENS )
                        out.put( t[0], t[1] );

                String mod = mMotdGameDir != null ? mMotdGameDir : "valve";
                String[] files = { "resource/" + mod + "_english.txt", "resource/gameui_english.txt" };
                for( String name : files ) {
                        File f = resGameFile( name );
                        if( f == null ) continue;
                        String text = readTextFile( f, 768 * 1024 );
                        ResBlock root = text == null ? null : resParse( text );
                        ResBlock lang = root == null ? null : root.block( "lang" );
                        ResBlock tokens = lang == null ? null : lang.block( "Tokens" );
                        if( tokens == null ) continue;
                        for( Map.Entry<String,Object> e : tokens.entries.entrySet() ) {
                                if( !( e.getValue() instanceof String ) || real.contains( e.getKey() ))
                                        continue;
                                real.add( e.getKey() );
                                out.put( e.getKey(), ( String )e.getValue());
                        }
                }
                mLoadingTokens = out;
        }

        /** "#Token" through the language files, plain text unchanged */
        private String token( String key ) {
                if( key == null || key.length() < 2 || key.charAt( 0 ) != '#' ) return key;
                String v = mLoadingTokens != null ? mLoadingTokens.get( key.substring( 1 )) : null;
                return v != null ? v : key;
        }

        /** palette from resource/ClientScheme.res (mod first, valve
         *  fallback): a Colors block of name -> "r g b a" strings */
        private void loadLoadingScheme() {
                mSchemeColors = new HashMap<String,Integer>();
                mLoadingScheme = null;

                File f = resGameFile( "resource/ClientScheme.res" );
                if( f == null ) return;
                String text = readTextFile( f, 256 * 1024 );
                ResBlock root = text == null ? null : resParse( text );
                mLoadingScheme = root;
                ResBlock scheme = root == null ? null : root.block( "Scheme" );
                ResBlock colors = scheme == null ? null : scheme.block( "Colors" );
                if( colors == null ) return;
                for( Map.Entry<String,Object> e : colors.entries.entrySet() ) {
                        if( !( e.getValue() instanceof String )) continue;
                        int c = parseResColor(( String )e.getValue(), 0 );
                        if( c != 0 ) mSchemeColors.put( e.getKey().toLowerCase( Locale.US ), Integer.valueOf( c ));
                }
        }

        private static int parseResColor( String v, int fallback ) {
                try {
                        String[] p = v.trim().split( "\\s+" );
                        int r = resInt( p[0], 0 );
                        int g = resInt( p[1], 0 );
                        int b = p.length > 2 ? resInt( p[2], 255 ) : 255;
                        int a = p.length > 3 ? resInt( p[3], 255 ) : 255;
                        return Color.argb( a, r, g, b );
                } catch( Throwable t ) {
                        return fallback;
                }
        }

        /** a color straight from the scheme: either a literal "r g b a"
         *  or a named palette entry */
        private int schemeColor( String ref, int fallback ) {
                if( ref == null || ref.length() == 0 ) return fallback;
                int lit = parseResColor( ref, 0 );
                if( lit != 0 ) return lit;
                Integer c = mSchemeColors != null ? mSchemeColors.get( ref.toLowerCase( Locale.US ).trim()) : null;
                return c != null ? c.intValue() : fallback;
        }

        /** one BaseSettings entry of the scheme ("FgColor",
         *  "TitleBarFgColor", ...), which names a palette color */
        private int schemeSetting( String key, int fallback ) {
                ResBlock scheme = mLoadingScheme != null ? mLoadingScheme.block( "Scheme" ) : null;
                ResBlock bs = scheme == null ? null : scheme.block( "BaseSettings" );
                return schemeColor( bs != null ? bs.str( key ) : null, fallback );
        }

        private int titleColor() {
                int c = schemeSetting( "TitleBarFgColor", LOADING_TITLE );
                return Color.alpha( c ) < 160 ? schemeColor( "ControlText", LOADING_TITLE ) : c;
        }

        // ------------------------------------------------------------------
        // the tiled loading background: resource/BackgroundLoadingLayout.txt
        // lists systems of tiles ("resolution W H" then one
        // "path [scaled] x y" line per tile). cstrike and czero ship the
        // tiles without the layout file, so when it is missing the well
        // known 800x600 system of 256px tiles is used - the same default
        // the pc gameui falls back to.
        // ------------------------------------------------------------------

        private static final class ResTile {
                String path;
                int x, y;
                Bitmap bmp;
        }

        private static final class ResBgSystem {
                int w = 800, h = 600;
                ArrayList<ResTile> tiles = new ArrayList<ResTile>();
        }

        private ResBgSystem loadLoadingBackground() {
                ArrayList<ResBgSystem> systems = new ArrayList<ResBgSystem>();
                File f = resGameFile( "resource/BackgroundLoadingLayout.txt" );
                if( f != null ) {
                        String text = readTextFile( f, 64 * 1024 );
                        if( text != null ) {
                                ResBgSystem cur = null;
                                for( String line : text.split( "\n" )) {
                                        ArrayList<String> toks = resLineTokens( line );
                                        if( toks.isEmpty()) continue;
                                        if( toks.get( 0 ).equalsIgnoreCase( "resolution" ) && toks.size() >= 3 ) {
                                                cur = new ResBgSystem();
                                                cur.w = resInt( toks.get( 1 ), 800 );
                                                cur.h = resInt( toks.get( 2 ), 600 );
                                                systems.add( cur );
                                                continue;
                                        }
                                        if( cur == null || !toks.get( 0 ).contains( "/" )) continue;
                                        ResTile t = new ResTile();
                                        t.path = toks.get( 0 );
                                        t.x = resInt( toks.get( toks.size() - 2 ), 0 );
                                        t.y = resInt( toks.get( toks.size() - 1 ), 0 );
                                        cur.tiles.add( t );
                                }
                        }
                }

                if( systems.isEmpty()) {
                        // the default the pc client falls back to when a mod
                        // ships the tiles without the layout file
                        ResBgSystem sys = new ResBgSystem();
                        for( int row = 0; row < 3; row++ )
                                for( int col = 0; col < 4; col++ ) {
                                        ResTile t = new ResTile();
                                        t.path = "resource/background/800_" + ( col + 1 ) + "_"
                                                + ( char )( 'a' + row ) + "_loading.tga";
                                        t.x = col * 256;
                                        t.y = row * 256;
                                        sys.tiles.add( t );
                                }
                        systems.add( sys );
                }

                // the system closest to our screen shape, with its tiles decoded
                DisplayMetrics dm = getResources().getDisplayMetrics();
                float aspect = ( float )dm.widthPixels / ( float )Math.max( 1, dm.heightPixels );
                ResBgSystem best = null;
                float bestD = Float.MAX_VALUE;
                for( ResBgSystem s : systems ) {
                        float d = Math.abs(( float )s.w / ( float )Math.max( 1, s.h ) - aspect );
                        if( d < bestD ) {
                                bestD = d;
                                best = s;
                        }
                }
                boolean any = false;
                for( ResTile t : best.tiles ) {
                        File tf = resGameFile( t.path );
                        if( tf != null ) t.bmp = decodeImageFile( tf );
                        if( t.bmp != null ) any = true;
                }
                return any ? best : null;
        }

        private static ArrayList<String> resLineTokens( String line ) {
                ArrayList<String> out = new ArrayList<String>();
                int[] pos = { 0 };
                for( ;; ) {
                        String t = resToken( line, pos );
                        if( t == null ) break;
                        out.add( t );
                }
                return out;
        }

        /** the tiled background of the loading screen, stretched to the
         *  screen the way the pc client stretches the chosen system */
        private static final class LoadingBackgroundView extends View {
                private final ResBgSystem mSystem;

                LoadingBackgroundView( Context c, ResBgSystem sys ) {
                        super( c );
                        mSystem = sys;
                }

                @Override
                protected void onDraw( Canvas canvas ) {
                        canvas.drawColor( 0xFF000000 );
                        if( mSystem == null ) return;
                        float w = getWidth(), h = getHeight();
                        if( w <= 0 || h <= 0 ) return;
                        float sx = w / mSystem.w, sy = h / mSystem.h;
                        Paint p = new Paint();
                        p.setFilterBitmap( true );
                        for( ResTile t : mSystem.tiles ) {
                                if( t.bmp == null ) continue;
                                canvas.drawBitmap( t.bmp, null,
                                        new RectF( t.x * sx, t.y * sy,
                                                ( t.x + t.bmp.getWidth()) * sx, ( t.y + t.bmp.getHeight()) * sy ), p );
                        }
                }
        }

        // ------------------------------------------------------------------
        // the dialog itself: every control of the .res becomes a widget at
        // its own .res position, the ones the engine feeds get remembered
        // (Progress, InfoLabel, Progress2, SecondaryProgressLabel,
        // TimeRemainingLabel, CancelButton), everything else renders
        // statically like the pc gameui renders it
        // ------------------------------------------------------------------

        // the stock valve dialog, used for every control the shipped .res
        // does not define (server banner plugins overwrite the file with
        // only their ImagePanel in it - the rest keeps the stock layout)
        private static final String STOCK_LOADING_DIALOG =
                "\"LoadingDialog\" { \"ControlName\" \"Frame\" \"fieldName\" \"LoadingDialog\" \"wide\" \"380\" \"tall\" \"112\" \"title\" \"#GameUI_Loading\" }" +
                "\"InfoLabel\" { \"ControlName\" \"Label\" \"fieldName\" \"InfoLabel\" \"xpos\" \"20\" \"ypos\" \"34\" \"wide\" \"340\" \"tall\" \"24\" \"labelText\" \"#GameUI_ParseBaseline\" \"textAlignment\" \"west\" \"dulltext\" \"1\" }" +
                "\"progress\" { \"ControlName\" \"ProgressBar\" \"fieldName\" \"Progress\" \"xpos\" \"20\" \"ypos\" \"64\" \"wide\" \"260\" \"tall\" \"24\" }" +
                "\"CancelButton\" { \"ControlName\" \"Button\" \"fieldName\" \"CancelButton\" \"xpos\" \"288\" \"ypos\" \"64\" \"wide\" \"72\" \"tall\" \"24\" \"labelText\" \"#GameUI_Cancel\" \"textAlignment\" \"west\" \"command\" \"Cancel\" }" +
                "\"SecondaryProgressLabel\" { \"ControlName\" \"Label\" \"fieldName\" \"SecondaryProgressLabel\" \"xpos\" \"20\" \"ypos\" \"90\" \"wide\" \"260\" \"tall\" \"24\" \"labelText\" \" \" \"textAlignment\" \"west\" \"dulltext\" \"1\" \"visible\" \"0\" }" +
                "\"Progress2\" { \"ControlName\" \"ProgressBar\" \"fieldName\" \"Progress2\" \"xpos\" \"20\" \"ypos\" \"114\" \"wide\" \"260\" \"tall\" \"24\" \"visible\" \"0\" }" +
                "\"TimeRemainingLabel\" { \"ControlName\" \"Label\" \"fieldName\" \"TimeRemainingLabel\" \"xpos\" \"20\" \"ypos\" \"108\" \"wide\" \"260\" \"tall\" \"24\" \"textAlignment\" \"west\" \"dulltext\" \"1\" \"visible\" \"0\" }";

        private static final String STOCK_LOADING_DUAL =
                "\"LoadingDialog\" { \"ControlName\" \"Frame\" \"fieldName\" \"LoadingDialog\" \"wide\" \"380\" \"tall\" \"176\" \"settitlebarvisible\" \"1\" \"title\" \"#GameUI_Loading\" }" +
                "\"InfoLabel\" { \"ControlName\" \"Label\" \"fieldName\" \"InfoLabel\" \"xpos\" \"20\" \"ypos\" \"34\" \"wide\" \"340\" \"tall\" \"24\" \"labelText\" \"#GameUI_VerifyingAndDownloading\" \"textAlignment\" \"west\" \"dulltext\" \"1\" }" +
                "\"progress\" { \"ControlName\" \"ProgressBar\" \"fieldName\" \"Progress\" \"xpos\" \"20\" \"ypos\" \"58\" \"wide\" \"260\" \"tall\" \"24\" }" +
                "\"SecondaryProgressLabel\" { \"ControlName\" \"Label\" \"fieldName\" \"SecondaryProgressLabel\" \"xpos\" \"20\" \"ypos\" \"90\" \"wide\" \"340\" \"tall\" \"24\" \"labelText\" \" \" \"textAlignment\" \"west\" \"dulltext\" \"1\" }" +
                "\"TimeRemainingLabel\" { \"ControlName\" \"Label\" \"fieldName\" \"TimeRemainingLabel\" \"xpos\" \"20\" \"ypos\" \"108\" \"wide\" \"260\" \"tall\" \"24\" \"textAlignment\" \"west\" \"dulltext\" \"1\" }" +
                "\"Progress2\" { \"ControlName\" \"ProgressBar\" \"fieldName\" \"Progress2\" \"xpos\" \"20\" \"ypos\" \"132\" \"wide\" \"260\" \"tall\" \"24\" }" +
                "\"CancelButton\" { \"ControlName\" \"Button\" \"fieldName\" \"CancelButton\" \"xpos\" \"288\" \"ypos\" \"132\" \"wide\" \"72\" \"tall\" \"24\" \"labelText\" \"#GameUI_Cancel\" \"textAlignment\" \"west\" \"command\" \"Cancel\" \"Default\" \"1\" }";

        /** all control blocks of a dialog .res keyed by fieldName, found
         *  through the wrapping file block too */
        private static void collectControls( ResBlock b, int depth, LinkedHashMap<String,ResBlock> out ) {
                if( b == null || depth > 3 ) return;
                for( Object o : b.entries.values() ) {
                        if( !( o instanceof ResBlock )) continue;
                        ResBlock c = ( ResBlock )o;
                        if( c.str( "ControlName" ) != null ) {
                                String fn = c.str( "fieldName" );
                                if( fn != null && !out.containsKey( fn.toLowerCase( Locale.US )))
                                        out.put( fn.toLowerCase( Locale.US ), c );
                        }
                        collectControls( c, depth + 1, out );
                }
        }

        /** one of the LoadingDialog*.res through the mod -> valve
         *  fallback, parsed; null when neither ships */
        private ResBlock resLoadDialog( String rel ) {
                File f = resGameFile( rel );
                if( f == null ) return null;
                String text = readTextFile( f, 128 * 1024 );
                return text == null ? null : resParse( text );
        }

        /** the .res dialog is authored for a 800x600 vgui screen; on a
         *  phone it grows with the resolution instead of staying tiny */
        private float loadingScale() {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                float s = Math.min( dm.widthPixels / 800f, dm.heightPixels / 600f );
                return Math.max( 1f, Math.min( 2.4f, s ));
        }

        private void showLoadingOnUiThread( String serverAddr ) {
                if( mLoadingOverlay != null )
                        hideLoadingOverlay();

                mStatusText = null;
                mDlNameText = null;
                mDlStatusText = null;
                mDlFooterText = null;
                mPct = -1f;
                mDlPct = -1f;
                mLoadingDual = false;

                // the game resources this dialog is built from
                loadLoadingTokens();
                loadLoadingScheme();

                FrameLayout overlay = new FrameLayout( this );

                ResBgSystem bg = loadLoadingBackground();
                if( bg != null ) {
                        overlay.addView( new LoadingBackgroundView( this, bg ),
                                new FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT ));
                } else {
                        overlay.setBackgroundColor( LOADING_SHADE );
                }

                mLoadingOverlay = overlay;
                buildLoadingDialogInto( overlay, false );

                addContentView( overlay, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT ));
        }

        /** build the dialog for the given variant out of the .res and put
         *  it in the overlay, replacing the previous one - this is the pc
         *  LoadControlSettings of the loading window, called when the
         *  download phase starts and when it ends */
        private void rebuildLoadingDialog( boolean dual ) {
                if( mLoadingOverlay == null ) return;
                buildLoadingDialogInto( mLoadingOverlay, dual );
        }

        private void buildLoadingDialogInto( FrameLayout overlay, boolean dual ) {
                if( mLoadingDialogView != null ) {
                        overlay.removeView( mLoadingDialogView );
                        mLoadingDialogView = null;
                }
                mLoadingBar = null;
                mLoadingStatus = null;
                mLoadingDlBar = null;
                mLoadingDlName = null;
                mLoadingDlStatus = null;
                mLoadingCancel = null;
                mLoadingDual = dual;

                ResBlock file = resLoadDialog( dual ? "resource/LoadingDialogDualProgress.res" : "resource/LoadingDialog.res" );
                LinkedHashMap<String,ResBlock> ctl = new LinkedHashMap<String,ResBlock>();
                collectControls( resParse( dual ? STOCK_LOADING_DUAL : STOCK_LOADING_DIALOG ), 0, ctl );
                collectControls( file, 0, ctl );    // whatever the game ships wins

                float s = loadingScale();
                ResBlock frame = ctl.get( "loadingdialog" );
                int fw = frame != null ? resInt( frame.str( "wide" ), 380 ) : 380;
                int fh = frame != null ? resInt( frame.str( "tall" ), dual ? 176 : 112 ) : ( dual ? 176 : 112 );
                boolean titleBar = frame == null || !"0".equals( frame.str( "settitlebarvisible" ));

                int dialogBg = schemeSetting( "BgColor", 0 );
                if( Color.alpha( dialogBg ) < 8 ) dialogBg = schemeColor( "WindowBG", 0 );
                if( Color.alpha( dialogBg ) < 8 ) dialogBg = LOADING_PANEL_BG;
                int dialogBorder = schemeSetting( "FgColor", LOADING_PANEL_BORDER );

                FrameLayout dlg = new FrameLayout( this );
                dlg.setBackground( makeLoadingPanelBackground( dialogBg, dialogBorder ));
                mLoadingDialogView = dlg;

                // the vgui frame draws its title bar inside the frame, the
                // control positions start under it
                int titleH = 0;
                if( titleBar ) {
                        LinearLayout tr = new LinearLayout( this );
                        tr.setGravity( Gravity.CENTER_VERTICAL );
                        tr.setPadding( Math.round( 6 * s ), 0, Math.round( 6 * s ), 0 );

                        ImageView logo = new ImageView( this );
                        logo.setImageResource( R.drawable.cs_logo );
                        logo.setScaleType( ImageView.ScaleType.FIT_CENTER );
                        int ls = Math.round( 13 * s );
                        tr.addView( logo, new LinearLayout.LayoutParams( ls, ls ));

                        TextView tt = new TextView( this );
                        tt.setText( token( frame != null && frame.str( "title" ) != null
                                ? frame.str( "title" ) : "#GameUI_Loading" ));
                        tt.setTextColor( titleColor());
                        tt.setTextSize( TypedValue.COMPLEX_UNIT_PX, Math.max( 11, Math.round( 14 * s )));
                        tt.setTypeface( Typeface.DEFAULT_BOLD );
                        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT );
                        tlp.leftMargin = Math.round( 6 * s );
                        tr.addView( tt, tlp );

                        dlg.addView( tr, new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, Math.round( 20 * s )));
                        titleH = Math.round( 20 * s );
                }

                for( ResBlock c : ctl.values() ) {
                        if( c == frame ) continue;

                        String cls = c.str( "ControlName" );
                        if( cls == null ) continue;
                        String fn = c.str( "fieldName" );
                        String lname = fn != null ? fn.toLowerCase( Locale.US ) : "";

                        // label geometry is the default, the other classes
                        // sit where the stock file puts them
                        int defX = 20, defY = 34, defW = 340, defH = 24;
                        if( cls.equalsIgnoreCase( "ProgressBar" )) { defY = 64; defW = 260; }
                        else if( cls.equalsIgnoreCase( "Button" )) { defX = 288; defY = 64; defW = 72; }
                        else if( cls.equalsIgnoreCase( "ImagePanel" )) { defY = 40; defH = 56; }

                        int wpx = Math.max( 1, Math.round( resInt( firstOf( c.str( "wide" ), c.str( "w" )), defW ) * s ));
                        int hpx = Math.max( 1, Math.round( resInt( firstOf( c.str( "tall" ), c.str( "h" )), defH ) * s ));
                        int xpx = resPos( firstOf( c.str( "xpos" ), c.str( "x" )), defX, Math.round( fw * s ), s );
                        int ypx = titleH + resPos( firstOf( c.str( "ypos" ), c.str( "y" )), defY, Math.round( fh * s ), s );
                        boolean show = !"0".equals( c.str( "visible" ));

                        View cv = null;

                        if( cls.equalsIgnoreCase( "Label" )) {
                                TextView tv = new TextView( this );
                                String lt = c.str( "labelText" );
                                tv.setText( lt != null ? token( lt ) : "" );
                                boolean dull = "1".equals( c.str( "dulltext" ));
                                boolean bright = "1".equals( c.str( "brighttext" ));
                                tv.setTextColor( dull ? schemeColor( "LabelDimText", LOADING_TEXT )
                                        : bright ? schemeColor( "BrightControlText", LOADING_TITLE )
                                        : schemeSetting( "FgColor", LOADING_TITLE ));
                                tv.setTextSize( TypedValue.COMPLEX_UNIT_PX, Math.max( 10, Math.round( hpx * 0.52f )));
                                tv.setSingleLine( false );
                                String ta = firstOf( c.str( "textAlignment" ), c.str( "alignment" ));
                                tv.setGravity( ta != null && ta.contains( "center" ) ? Gravity.CENTER
                                        : ta != null && ta.contains( "east" ) ? Gravity.END : Gravity.START );
                                cv = tv;

                                if( lname.equals( "infolabel" )) mLoadingStatus = tv;
                                else if( lname.equals( "secondaryprogresslabel" )) mLoadingDlName = tv;
                                else if( lname.equals( "timeremaininglabel" )) mLoadingDlStatus = tv;
                        } else if( cls.equalsIgnoreCase( "ProgressBar" )) {
                                LoadingBar bar = new LoadingBar( this );
                                bar.setColors( solidify( schemeColor( "WindowBG", 0 ), LOADING_BAR_TRACK ),
                                        dialogBorder, schemeColor( "BrightControlText", LOADING_BAR_FILL ));
                                cv = bar;

                                if( lname.equals( "progress" )) mLoadingBar = bar;
                                else if( lname.equals( "progress2" )) mLoadingDlBar = bar;
                        } else if( cls.equalsIgnoreCase( "Button" )) {
                                Button btn = new Button( this );
                                btn.setAllCaps( false );
                                String lt = c.str( "labelText" );
                                btn.setText( lt != null ? token( lt ) : "" );
                                btn.setTextColor( schemeSetting( "FgColor", LOADING_TITLE ));
                                btn.setTextSize( TypedValue.COMPLEX_UNIT_PX, Math.max( 10, Math.round( hpx * 0.48f )));
                                btn.setBackground( makeLoadingButtonBackground( dialogBg, dialogBorder,
                                        schemeColor( "BrightControlText", LOADING_TITLE )));
                                btn.setStateListAnimator( null );
                                btn.setElevation( 0f );
                                btn.setMinHeight( 0 );
                                btn.setMinWidth( 0 );
                                btn.setPadding( Math.round( 6 * s ), 0, Math.round( 6 * s ), 0 );
                                btn.setOnClickListener( new View.OnClickListener() {
                                        @Override public void onClick( View v ) {
                                                hideLoadingOverlay();
                                                nativeLoadingCancelled();
                                        }
                                });
                                cv = btn;

                                if( lname.equals( "cancelbutton" )) mLoadingCancel = btn;
                        } else if( cls.equalsIgnoreCase( "ImagePanel" )) {
                                ImageView iv = new ImageView( this );
                                iv.setScaleType( ImageView.ScaleType.FIT_CENTER );
                                iv.setVisibility( View.GONE );  // until a picture decodes
                                String img = c.str( "image" );
                                if( img != null && img.length() > 0 ) loadResImage( iv, img );
                                cv = iv;
                        }

                        // everything else (Divider, Menu, ...) is frame
                        // chrome the pc window draws itself, skip it here
                        if( cv == null ) continue;
                        if( !show ) cv.setVisibility( View.GONE );

                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams( wpx, hpx );
                        lp.leftMargin = xpx;
                        lp.topMargin = ypx;
                        dlg.addView( cv, lp );
                }

                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                        Math.max( 1, Math.round( fw * s )), Math.max( 1, Math.round( fh * s )));
                dlp.gravity = Gravity.CENTER;
                overlay.addView( dlg, dlp );

                // reapply everything the engine already told us
                if( mLoadingStatus != null && mStatusText != null &&
                        !mStatusText.contentEquals( mLoadingStatus.getText() ))
                        mLoadingStatus.setText( mStatusText );
                if( mLoadingBar != null && mPct >= 0f ) {
                        mLoadingBar.setPercent( mPct );
                        mLoadingBar.setLabel( Math.round( mPct ) + "%" );
                }
                if( mLoadingDlName != null && mDlNameText != null )
                        mLoadingDlName.setText( mDlNameText );
                if( mLoadingDlStatus != null && ( mDlStatusText != null || mDlFooterText != null )) {
                        String t = mDlStatusText;
                        if( mDlFooterText != null ) t = ( t != null ? t + "  " : "" ) + mDlFooterText;
                        mLoadingDlStatus.setText( t != null ? t : "" );
                }
                if( mLoadingDlBar != null && mDlPct >= 0f ) {
                        mLoadingDlBar.setPercent( mDlPct );
                        mLoadingDlBar.setLabel( Math.round( mDlPct ) + "%" );
                }
        }

        private void hideLoadingOverlay() {
                if( mLoadingOverlay == null ) return;

                FrameLayout overlay = mLoadingOverlay;
                mLoadingOverlay = null;
                mLoadingDialogView = null;
                mLoadingBar = null;
                mLoadingStatus = null;
                mLoadingDlBar = null;
                mLoadingDlName = null;
                mLoadingDlStatus = null;
                mLoadingCancel = null;

                try {
                        ViewGroup parent = ( ViewGroup )overlay.getParent();
                        if( parent != null ) parent.removeView( overlay );
                } catch( Throwable t ) {
                        Log.w( TAG, "loading overlay remove failed", t );
                }
        }

        /** decode an image a dialog .res points at (the loading-banner
         *  plugins ship a tga through precache_generic; it lands in the
         *  game dir or the <game>_downloads tree) and drop it into the
         *  view once it is there. touching a detached view is harmless. */
        private void loadResImage( final ImageView view, final String image ) {
                Thread t = new Thread( new Runnable() {
                        @Override public void run() {
                                Bitmap bmp = resolveLoadingBannerImage( image );
                                if( bmp == null ) return;
                                runOnUiThread( new Runnable() {
                                        @Override public void run() {
                                                try {
                                                        view.setImageBitmap( bmp );
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
                for( String ext : exts ) {
                        String path = image + ext;
                        File f = gameDirFile( path );
                        if( f == null || !f.isFile())
                                f = downloadDirFile( path );
                        if( f != null && f.isFile()) {
                                Bitmap bmp = decodeImageFile( f );
                                if( bmp != null ) return bmp;
                        }
                }
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
