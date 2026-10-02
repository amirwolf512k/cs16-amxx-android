package su.xash.engine;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
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
import android.text.TextUtils;
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

        // cs16-amxx-android v20: sandboxed HTML MOTD (set in getArguments())
        private String mMotdBaseDir;
        private String mMotdGameDir;
        private Dialog mMotdDialog;

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

                // v21: never let a NullPointerException reach the JNI caller
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

                // v20: remember the real game dirs for the MOTD WebView sandbox
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
        // cs16-amxx-android v20: sandboxed HTML MOTD rendering ("Message of the
        // Day" like real CS 1.6). The engine hands us the raw "MOTD" user
        // message payload as bytes; we render it in a WebView that can only
        // read files inside the current game dir (valve/ or cstrike/), can
        // never reach addons/ (metamod/AMXX data, top15 stats) and has no
        // JavaScript, DOM storage, content:// or network access at all.
        // =====================================================================

        // cs16-amxx-android v24: implemented in the engine (libxash.so); tells
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

        /** Called from native (JNI) when a client MOTD contains HTML.
         *  v22: runs the dialog creation synchronously on the UI thread and
         *  returns whether the dialog is actually on screen, so the client dll
         *  can fall back to the classic HUD text renderer when the WebView
         *  dialog cannot be shown. */
        public boolean showMOTD( final byte[] htmlBytes ) {
                final boolean[] shown = new boolean[1];
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch( 1 );

                runOnUiThread( new Runnable() {
                        @Override
                        public void run() {
                                try {
                                        shown[0] = showMOTDOnUiThread( htmlBytes );
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
                        if ( !latch.await( 5, java.util.concurrent.TimeUnit.SECONDS ) )
                                return false;
                } catch ( InterruptedException e ) {
                        return false;
                }

                return shown[0];
        }

        // v27: orange-gold sampled from the PC CS 1.6 MOTD reference
        // (soldier logo, title text, divider, OK button all share it)
        private static final int MOTD_GOLD = 0xFFF2A81D;

        private boolean showMOTDOnUiThread( byte[] htmlBytes ) {
                try {
                        if ( mMotdDialog != null ) {
                                mMotdDialog.dismiss();
                                mMotdDialog = null;
                        }

                        String raw = new String( htmlBytes, "UTF-8" );
                        String base = mMotdBaseDir != null ? mMotdBaseDir
                                : Environment.getExternalStorageDirectory().getAbsolutePath() + "/xash";
                        String game = mMotdGameDir != null ? mMotdGameDir : "valve";
                        final File gameDir = new File( base, game );

                        final Dialog dialog = new Dialog( this, android.R.style.Theme_Black_NoTitleBar );
                        Window w = dialog.getWindow();
                        w.setBackgroundDrawable( new ColorDrawable( 0x00000000 ) );
                        w.setLayout( ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT );

                        // full-screen root that dims the game behind the window
                        FrameLayout root = new FrameLayout( this );
                        root.setBackgroundColor( 0x44000000 );

                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int screenW = dm.widthPixels;
                        int screenH = dm.heightPixels;

                        // v26: window geometry comes from the game's own
                        // resource/UI/MOTD.res (PC CS 1.6 VGUI layout, 640x480
                        // coordinate space), so a custom server UI keeps the
                        // exact window it designed for PC. Scaled by screen
                        // height like CS does for widescreen; falls back to
                        // the PC default window when the .res is absent.
                        MotdLayout lay = parseMotdRes( gameDir );

                        // v29: phones are widescreen; scaling the 4:3 PC
                        // window by height alone left huge empty margins on
                        // the sides. Keep the .res proportions but guarantee
                        // a wide panel (74%..94% of the screen width) and
                        // cap the height at 86% of the screen.
                        float scale = screenH / 480f;
                        int panelW = Math.round( lay.frameW * scale );
                        int minW = Math.round( screenW * 0.74f );
                        int maxW = Math.round( screenW * 0.94f );

                        if ( panelW < minW )
                        {
                                scale = ( float ) minW / lay.frameW;
                                panelW = minW;
                        }

                        if ( panelW > maxW )
                        {
                                scale = ( float ) maxW / lay.frameW;
                                panelW = maxW;
                        }

                        int panelH = Math.round( lay.frameH * scale );

                        if ( panelH > Math.round( screenH * 0.86f ))
                        {
                                panelH = Math.round( screenH * 0.86f );
                        }

                        // --- v27: PC CS 1.6 window — rounded near-black panel,
                        // solid black title bar with the orange CS soldier logo
                        // top-left, glowing gold divider, dark body, wide
                        // bottom-left OK (matches the PC reference shots) ---
                        LinearLayout panel = new LinearLayout( this );
                        panel.setOrientation( LinearLayout.VERTICAL );
                        panel.setBackground( makeMOTDWindowBackground() );
                        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams( panelW, panelH );
                        panelLp.gravity = Gravity.CENTER;
                        root.addView( panel, panelLp );

                        // --- title bar: black band, soldier logo in the left corner ---
                        int headerH = Math.max( dp( 56 ), Math.round( lay.contentY * scale ));

                        ImageView logo = new ImageView( this );
                        logo.setImageResource( R.drawable.cs_logo );
                        logo.setScaleType( ImageView.ScaleType.FIT_CENTER );
                        int logoSize = dp( 48 );

                        TextView title = new TextView( this );
                        title.setText( buildMOTDTitle( raw ));
                        title.setTextColor( MOTD_GOLD );
                        title.setTextSize( TypedValue.COMPLEX_UNIT_SP, 15 );
                        title.setTypeface( Typeface.DEFAULT_BOLD );
                        title.setLetterSpacing( 0.02f );
                        title.setSingleLine( true );
                        title.setEllipsize( TextUtils.TruncateAt.END );
                        title.setGravity( Gravity.CENTER_VERTICAL );

                        LinearLayout header = new LinearLayout( this );
                        header.setOrientation( LinearLayout.HORIZONTAL );
                        header.setGravity( Gravity.CENTER_VERTICAL | Gravity.START );
                        header.setBackground( makeMOTDTitleBarBackground() );
                        header.setPadding( dp( 14 ), dp( 4 ), dp( 12 ), dp( 4 ));
                        header.addView( logo, new LinearLayout.LayoutParams( logoSize, logoSize ));

                        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT );
                        titleLp.setMargins( dp( 10 ), 0, 0, 0 );
                        header.addView( title, titleLp );
                        panel.addView( header, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, headerH ));

                        // --- glowing divider under the title bar (fades both ends) ---
                        View separator = new View( this );
                        separator.setBackground( makeMOTDDivider() );
                        panel.addView( separator, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max( 2, dp( 2 )) ));

                        // --- body column: the content inset and the OK button
                        // share the same left/right edges, exactly like the
                        // PC CS 1.6 MOTD window ---
                        int bodyW = Math.round( lay.contentW * scale );
                        if ( bodyW > panelW - dp( 16 ))
                                bodyW = panelW - dp( 16 );

                        LinearLayout body = new LinearLayout( this );
                        body.setOrientation( LinearLayout.VERTICAL );
                        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                                        bodyW, ViewGroup.LayoutParams.MATCH_PARENT );
                        bodyLp.gravity = Gravity.CENTER_HORIZONTAL;
                        panel.addView( body, bodyLp );

                        // --- content: sandboxed WebView straight on the dark
                        // body, like the PC window (pages keep their own bg) ---
                        WebView wv = createMOTDWebView( gameDir );
                        wv.setBackgroundColor( 0x00000000 );
                        wv.loadDataWithBaseURL( "https://motd.local/", buildMOTDDocument( raw ),
                                        "text/html", "utf-8", null );

                        FrameLayout content = new FrameLayout( this );
                        content.addView( wv, new FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT ));

                        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f );
                        contentLp.setMargins( dp( 14 ), dp( 12 ), dp( 14 ), 0 );
                        body.addView( content, contentLp );

                        // --- footer: wide flat OK button in the bottom-left ---
                        Button ok = new Button( this );
                        ok.setText(( lay.okLabel != null && !lay.okLabel.isEmpty()
                                        && !lay.okLabel.startsWith( "#" )) ? lay.okLabel : "OK" );
                        ok.setAllCaps( false );
                        ok.setTextColor( MOTD_GOLD );
                        ok.setTextSize( TypedValue.COMPLEX_UNIT_SP, 14 );
                        ok.setTypeface( Typeface.DEFAULT_BOLD );
                        ok.setBackground( makeMOTDButtonBackground() );
                        ok.setStateListAnimator( null );
                        ok.setElevation( 0f );
                        ok.setPadding( dp( 16 ), 0, dp( 16 ), 0 );
                        ok.setOnClickListener( new View.OnClickListener() {
                                        @Override
                                        public void onClick( View v ) {
                                                dialog.dismiss();
                                        }
                        } );

                        // PC proportions from MOTD.res, but at least 42% of the
                        // content width (>=190dp) and a generous touch height —
                        // the user asked for a visibly wider, taller OK
                        // v29: compact OK per user feedback (>=26% of the
                        // content width, 110dp floor, 40dp touch height)
                        int okW = Math.max( Math.round( lay.okW * scale ),
                                Math.max( dp( 110 ), Math.round( bodyW * 0.26f )));
                        int okH = Math.max( dp( 40 ), Math.round( lay.okH * scale ));

                        LinearLayout footer = new LinearLayout( this );
                        footer.setOrientation( LinearLayout.HORIZONTAL );
                        footer.setGravity( Gravity.START | Gravity.CENTER_VERTICAL );
                        footer.setPadding( dp( 14 ), dp( 12 ), dp( 14 ), dp( 16 ));
                        footer.addView( ok, new LinearLayout.LayoutParams( okW, okH ));
                        body.addView( footer, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT ));

                        dialog.setContentView( root );
                        dialog.setOnDismissListener( new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss( DialogInterface d ) {
                                        mMotdDialog = null;
                                        notifyMOTDClosed();
                                }
                        } );

                        mMotdDialog = dialog;
                        dialog.show();
                        return true;
                } catch ( Throwable t ) {
                        Log.w( TAG, "showMOTD failed", t );
                        return false;
                }
        }

        /** v29: the header shows the MOTD's own first line (HTML tags
         *  stripped, whitespace collapsed; the TextView ellipsises). */
        private static String buildMOTDTitle( String raw ) {
                if ( raw == null ) {
                        return "Message of the Day";
                }

                String s = raw.replaceAll( "(?is)<[^>]*>", " " );
                s = s.replace( "&nbsp;", " " ).replace( "&amp;", "&" );
                s = s.replace( "&lt;", "<" ).replace( "&gt;", ">" );
                s = s.replace( "&quot;", "\"" ).replace( "&#39;", "'" );
                s = s.replaceAll( "\\s+", " " ).trim();

                if ( s.length() > 48 ) {
                        s = s.substring( 0, 48 ).trim();
                }

                if ( s.isEmpty() ) {
                        return "Message of the Day";
                }

                return s;
        }

        /** v25: build the document loaded into the MOTD WebView. HTML pages
         *  render as-is (PC parity); plain-text MOTDs are wrapped into a
         *  game-styled document (black background, gold monospace text). */
        private static String buildMOTDDocument( String raw ) {
                String trimmed = raw == null ? "" : raw.trim();
                String lower = trimmed.toLowerCase( Locale.US );

                boolean looksHtml = lower.contains( "<html" ) || lower.contains( "<body" )
                        || lower.contains( "<br" ) || lower.contains( "<p>" ) || lower.contains( "<p " )
                        || lower.contains( "<table" ) || lower.contains( "<div" ) || lower.contains( "<font" )
                        || lower.contains( "<img" ) || lower.contains( "<!doctype" );

                if ( looksHtml )
                        return trimmed;

                StringBuilder sb = new StringBuilder();
                sb.append( "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" );
                sb.append( "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" );
                sb.append( "<style>html,body{margin:0;padding:0;background:#000;height:100%;}" );
                sb.append( "pre{margin:0;padding:12px;font-family:monospace;" );
                sb.append( "font-size:14px;line-height:1.5;color:#ffb400;" );
                sb.append( "white-space:pre-wrap;word-wrap:break-word;}</style></head><body><pre>" );
                sb.append( escapeMOTDHtml( trimmed ) );
                sb.append( "</pre></body></html>" );
                return sb.toString();
        }

        private static String escapeMOTDHtml( String s ) {
                return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
        }

        // =====================================================================
        // v26: resource/UI/MOTD.res parsing (PC CS 1.6 VGUI layout). The file
        // holds "block" { "key" "value" ... } pairs in a 640x480 coordinate
        // space; we only take the geometry, colors stay in the game theme.
        // =====================================================================
        private static class MotdLayout {
                int frameW = 512, frameH = 384;   // "MOTD" frame block
                int contentY = 48;                // "MessageOfTheDay" ypos
                int contentW = 480, contentH = 296;
                int okW = 96, okH = 24;           // "OK" button block
                String okLabel = null;
        }

        /** Locate resource/UI/MOTD.res in the game dir (case-insensitive,
         *  Android filesystems are not). Returns null when absent. */
        private static File findMotdRes( File gameDir ) {
                try {
                        File resDir = new File( gameDir, "resource" );
                        if ( !resDir.isDirectory())
                                return null;

                        File uiDir = null;
                        File[] children = resDir.listFiles();
                        if ( children != null ) {
                                for ( File c : children ) {
                                        if ( c.isDirectory() && c.getName().equalsIgnoreCase( "UI" )) {
                                                uiDir = c;
                                                break;
                                        }
                                }
                        }

                        if ( uiDir == null || !uiDir.isDirectory())
                                return null;

                        File[] files = uiDir.listFiles();
                        if ( files == null )
                                return null;

                        for ( File f : files ) {
                                if ( f.isFile() && f.getName().equalsIgnoreCase( "MOTD.res" ))
                                        return f;
                        }
                } catch ( Throwable t ) {
                        // fall through
                }
                return null;
        }

        private static MotdLayout parseMotdRes( File gameDir ) {
                MotdLayout lay = new MotdLayout();
                File res = findMotdRes( gameDir );

                try {
                        if ( res == null )
                                return lay;

                        byte[] bytes = readMotdResFile( res );
                        if ( bytes == null )
                                return lay;

                        String text = stripResComments( new String( bytes, "ISO-8859-1" ));

                        int i = 0, n = text.length();
                        String block = null;
                        String pending = null;

                        while ( i < n ) {
                                char c = text.charAt( i );

                                if ( Character.isWhitespace( c )) {
                                        i++;
                                        continue;
                                }

                                if ( c == '{' ) {
                                        block = pending; // pending quoted token names this block
                                        pending = null;
                                        i++;
                                        continue;
                                }

                                if ( c == '}' ) {
                                        block = null;
                                        pending = null;
                                        i++;
                                        continue;
                                }

                                if ( c == '"' ) {
                                        int close = text.indexOf( '"', i + 1 );
                                        if ( close < 0 )
                                                break;

                                        String tok = text.substring( i + 1, close );
                                        i = close + 1;

                                        if ( pending != null ) {
                                                applyMotdKey( lay, block, pending, tok );
                                                pending = null;
                                        } else {
                                                pending = tok;
                                        }
                                        continue;
                                }

                                // unquoted token: skip to the next separator
                                int j = i;
                                while ( j < n && !Character.isWhitespace( text.charAt( j ))
                                        && text.charAt( j ) != '{' && text.charAt( j ) != '}' )
                                        j++;
                                i = ( j == i ) ? i + 1 : j;
                        }
                } catch ( Throwable t ) {
                        Log.w( TAG, "parseMotdRes failed", t );
                }

                return lay;
        }

        private static void applyMotdKey( MotdLayout lay, String block, String key, String value ) {
                if ( block == null )
                        return;

                String b = block.toLowerCase( Locale.US );
                String k = key.toLowerCase( Locale.US );

                int v;
                try {
                        v = Integer.parseInt( value.trim());
                } catch ( NumberFormatException e ) {
                        if ( k.equals( "labeltext" ) && b.equals( "ok" ))
                                lay.okLabel = value;
                        return;
                }

                if ( b.equals( "motd" )) {
                        if ( k.equals( "wide" )) lay.frameW = v;
                        else if ( k.equals( "tall" )) lay.frameH = v;
                } else if ( b.equals( "messageoftheday" )) {
                        if ( k.equals( "ypos" )) lay.contentY = v;
                        else if ( k.equals( "wide" )) lay.contentW = v;
                        else if ( k.equals( "tall" )) lay.contentH = v;
                } else if ( b.equals( "ok" )) {
                        if ( k.equals( "wide" )) lay.okW = v;
                        else if ( k.equals( "tall" )) lay.okH = v;
                }
        }

        private static String stripResComments( String s ) {
                StringBuilder out = new StringBuilder( s.length());
                int i = 0, n = s.length();

                while ( i < n ) {
                        if ( i + 1 < n && s.charAt( i ) == '/' && s.charAt( i + 1 ) == '/' ) {
                                while ( i < n && s.charAt( i ) != '\n' ) i++;
                        } else if ( i + 1 < n && s.charAt( i ) == '/' && s.charAt( i + 1 ) == '*' ) {
                                i += 2;
                                while ( i + 1 < n && !( s.charAt( i ) == '*' && s.charAt( i + 1 ) == '/' )) i++;
                                i += 2;
                        } else {
                                out.append( s.charAt( i ));
                                i++;
                        }
                }

                return out.toString();
        }

        private static byte[] readMotdResFile( File f ) {
                FileInputStream in = null;
                try {
                        long len = f.length();
                        if ( len <= 0 || len > 262144 )
                                return null;

                        in = new FileInputStream( f );
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int r;
                        while (( r = in.read( buf )) > 0 )
                                bos.write( buf, 0, r );
                        return bos.toByteArray();
                } catch ( Throwable t ) {
                        return null;
                } finally {
                        if ( in != null ) {
                                try { in.close(); } catch ( Throwable ignored ) {}
                        }
                }
        }

        /** v27: window body: rounded near-black panel (PC CS 1.6 VGUI window). */
        private Drawable makeMOTDWindowBackground() {
                GradientDrawable d = new GradientDrawable();
                d.setColor( 0xF0050505 );
                d.setCornerRadius( dp( 10 ) );
                return d;
        }

        /** v27: solid black title bar, rounded top corners only. */
        private Drawable makeMOTDTitleBarBackground() {
                float r = dp( 10 );
                GradientDrawable d = new GradientDrawable();
                d.setColor( 0xFF0A0A0A );
                d.setCornerRadii( new float[] { r, r, r, r, 0f, 0f, 0f, 0f } );
                return d;
        }

        /** v27: glowing gold divider (transparent -> gold -> transparent). */
        private Drawable makeMOTDDivider() {
                GradientDrawable d = new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[] { 0x00F2A81D, 0xFFF2A81D, 0x00F2A81D } );
                return d;
        }

        /** v27: VGUI-style OK button: dark body, gold border, rounded corners. */
        private StateListDrawable makeMOTDButtonBackground() {
                GradientDrawable normal = new GradientDrawable();
                normal.setColor( 0x51000000 );
                normal.setStroke( Math.max( 1, dp( 1 )), MOTD_GOLD );
                normal.setCornerRadius( dp( 4 ) );

                GradientDrawable pressed = new GradientDrawable();
                pressed.setColor( 0x66F2A81D );
                pressed.setStroke( Math.max( 1, dp( 1 )), MOTD_GOLD );
                pressed.setCornerRadius( dp( 4 ) );

                StateListDrawable sld = new StateListDrawable();
                sld.addState( new int[] { android.R.attr.state_pressed }, pressed );
                sld.addState( new int[] { -android.R.attr.state_pressed }, normal );
                return sld;
        }


        @SuppressLint("SetJavaScriptEnabled")
        private WebView createMOTDWebView( final File gameDir ) {
                WebView wv = new WebView( this );
                WebSettings s = wv.getSettings();

                // --- sandbox defaults: render HTML/CSS/images only ---------
                s.setJavaScriptEnabled( false );
                s.setAllowFileAccess( false );
                s.setAllowContentAccess( false );
                s.setAllowFileAccessFromFileURLs( false );
                s.setAllowUniversalAccessFromFileURLs( false );
                s.setBlockNetworkLoads( true );
                s.setBlockNetworkImage( true );
                s.setSavePassword( false );
                s.setDomStorageEnabled( false );
                s.setCacheMode( WebSettings.LOAD_NO_CACHE );
                s.setMediaPlaybackRequiresUserGesture( true );

                // v25: server MOTD pages are designed for the ~640px-wide PC
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
                                if ( scheme.equals( "https" ) && "motd.local".equals( url.getHost() ) ) {
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

                                // network, file://, content:// - everything else is blocked
                                return emptyResponse();
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading( WebView view, WebResourceRequest request ) {
                                // block navigation away from the rendered MOTD
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
}
