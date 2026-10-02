package su.xash.cs16client;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
        private static final String TAG = "CS16Client";

        // bump this when assets/addons.zip is updated so the bundle
        // gets re-extracted into the game directory
        private static final String ADDONS_VERSION = "amxx-v27-repo";

        private boolean mPermissionAsked = false;

        @Override
        protected void onCreate( Bundle savedInstanceState ) {
                super.onCreate( savedInstanceState );

                mPermissionAsked = savedInstanceState != null
                        && savedInstanceState.getBoolean( "permission_asked", false );

                // v23 fix: ask at most once per install - remembering the
                // flag across launches stops the per-launch dialog nagging
                if( !mPermissionAsked ) {
                        mPermissionAsked = getPreferences( MODE_PRIVATE )
                                .getBoolean( "permission_asked", false );
                }

                if( !hasStorageAccess() ) {
                        if( !mPermissionAsked ) {
                                mPermissionAsked = true;
                                requestStorageAccess();
                        }
                        return;
                }

                proceed();
        }

        @Override
        protected void onSaveInstanceState( Bundle outState ) {
                super.onSaveInstanceState( outState );
                outState.putBoolean( "permission_asked", mPermissionAsked );
        }

        @Override
        protected void onResume() {
                super.onResume();

                // continue after the user granted storage access in system settings
                if( mPermissionAsked ) {
                        if( hasStorageAccess() ) {
                                mPermissionAsked = false;
                                proceed();
                        } else {
                                Toast.makeText( this,
                                        "برای نصب افزونه‌ها و اجرای بازی، دسترسی حافظه را فعال کنید",
                                        Toast.LENGTH_LONG ).show();
                        }
                }
        }

        private void proceed() {
                try {
                        extractAddonsIfNeeded();
                } catch( Throwable t ) {
                        Log.w( TAG, "addons extraction failed", t );
                        Toast.makeText( this, "خطا در نصب افزونه‌ها: " + t.getMessage(), Toast.LENGTH_LONG ).show();
                        logInstallError( "cstrike addons (cs16client)", t );
                }

                launchEngine();
        }

        // ------------------------------------------------------------------
        // v9: append install problems to <xash>/amxx-install-log.txt so we can
        // see the exact reason (EACCES, missing asset, ...) when the user
        // reports a generic "it gave an error"
        // ------------------------------------------------------------------

        private void logInstallError( String source, Throwable t ) {
                try {
                        File dir = getXashDir();
                        if( !dir.isDirectory() )
                                dir.mkdirs();

                        java.io.PrintWriter pw = new java.io.PrintWriter(
                                new java.io.FileWriter( new File( dir, "amxx-install-log.txt" ), true ));
                        pw.println( "--- " + new java.util.Date() + " ---" );
                        pw.println( "source: " + source );
                        pw.println( t.getClass().getName() + ": " + t.getMessage() );
                        StackTraceElement[] st = t.getStackTrace();
                        for( int i = 0; i < st.length && i < 8; i++ )
                                pw.println( "    at " + st[i] );
                        if( t.getCause() != null )
                                pw.println( "cause: " + t.getCause().getClass().getName() + ": " + t.getCause().getMessage() );
                        pw.println();
                        pw.close();
                } catch( Throwable ignored ) {
                        // logging must never crash anything
                }
        }

        // ------------------------------------------------------------------
        // storage access (the game directory lives on shared storage, next to
        // the engine's base dir /storage/emulated/0/xash)
        // ------------------------------------------------------------------

        private boolean hasStorageAccess() {
                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R )
                        return Environment.isExternalStorageManager();

                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M )
                        return checkSelfPermission( android.Manifest.permission.WRITE_EXTERNAL_STORAGE )
                                == PackageManager.PERMISSION_GRANTED;

                return true;
        }

        private void requestStorageAccess() {
                getPreferences( MODE_PRIVATE ).edit().putBoolean( "permission_asked", true ).apply();

                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {
                        try {
                                Intent intent = new Intent( Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse( "package:" + getPackageName() ));
                                startActivity( intent );
                        } catch( Exception e ) {
                                try {
                                        startActivity( new Intent( Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION ));
                                } catch( Exception ex ) {
                                        Log.w( TAG, "no all-files-access settings page", ex );
                                        proceed(); // nothing we can do, try anyway
                                        return;
                                }
                        }
                        Toast.makeText( this,
                                "برای نصب افزونه‌های AMX Mod X، دسترسی کامل حافظه را فعال کنید",
                                Toast.LENGTH_LONG ).show();
                } else if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
                        requestPermissions( new String[] { android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1 );
                } else {
                        mPermissionAsked = false;
                        proceed();
                }
        }

        @Override
        public void onRequestPermissionsResult( int requestCode, String[] permissions, int[] grantResults ) {
                super.onRequestPermissionsResult( requestCode, permissions, grantResults );

                if( requestCode == 1 ) {
                        if( grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED ) {
                                mPermissionAsked = false;
                                proceed();
                        } else {
                                Toast.makeText( this,
                                        "بدون دسترسی حافظه نمی‌توان افزونه‌ها را نصب کرد",
                                        Toast.LENGTH_LONG ).show();
                        }
                }
        }

        // ------------------------------------------------------------------
        // AMX Mod X addons auto-install
        //
        // assets/addons.zip mirrors the game directory layout:
        //   cstrike/addons/metamod/...
        //   cstrike/addons/amxmodx/...
        // It is extracted into /storage/emulated/0/xash so that the patched
        // engine (su.xash.engine.test) finds the metamod library when the
        // server gamelib is loaded and relays plugin loading to AMX Mod X.
        // ------------------------------------------------------------------

        private File getXashDir() {
                return new File( Environment.getExternalStorageDirectory(), "xash" );
        }

        private void extractAddonsIfNeeded() throws Exception {
                // cstrike addons (AMX Mod X for Counter-Strike) -- this app
                // installs ONLY the counter-strike addon pack. The valve
                // (Half-Life) AMX addons are handled by the engine app itself
                // (Xash3D FWGS (AMXX)) at valve game launch since v9.
                extractZipIfNeeded( "addons.zip", "addons_version",
                        "cstrike/addons/metamod/dlls/libmetamod_android_arm64.so" );

                // v12: keep plugins.ini in sync with this app's nativeLibraryDir
                // (YaPB as metamod plugin) — must run on every launch in case the
                // engine/game ABI layout changed, and it is cheap anyway.
                patchPluginsIni();
        }

        private void extractZipIfNeeded( String assetName, String prefKey,
                String markerRelPath ) throws Exception {
                SharedPreferences prefs = getPreferences( MODE_PRIVATE );
                String installed = prefs.getString( prefKey, null );

                File markerLib = new File( getXashDir(), markerRelPath );

                if( ADDONS_VERSION.equals( installed ) && markerLib.isFile() ) {
                        Log.i( TAG, "AMXX addons up to date (" + assetName + ": " + ADDONS_VERSION + ")" );
                        return;
                }

                File dest = getXashDir();
                String destCanon = dest.getCanonicalPath();
                byte[] buffer = new byte[65536];
                int count = 0;

                InputStream in = getAssets().open( assetName );
                ZipInputStream zis = new ZipInputStream( in );

                ZipEntry entry;
                while(( entry = zis.getNextEntry() ) != null ) {
                        if( entry.isDirectory() )
                                continue;

                        File outFile = new File( dest, entry.getName() );

                        // zip-slip guard
                        if( !outFile.getCanonicalPath().startsWith( destCanon + File.separator )) {
                                Log.w( TAG, "skipping suspicious zip entry: " + entry.getName() );
                                continue;
                        }

                        File parent = outFile.getParentFile();
                        if( parent != null && !parent.isDirectory() )
                                parent.mkdirs();

                        FileOutputStream fos = new FileOutputStream( outFile );
                        int n;
                        while(( n = zis.read( buffer )) > 0 )
                                fos.write( buffer, 0, n );
                        fos.close();

                        // native libraries may need the exec bit depending on the filesystem
                        if( outFile.getName().endsWith( ".so" ))
                                outFile.setExecutable( true, false );

                        count++;
                        zis.closeEntry();
                }
                zis.close();

                prefs.edit().putString( prefKey, ADDONS_VERSION ).apply();
                Log.i( TAG, "AMXX addons extracted (" + assetName + "): " + count
                        + " files -> " + dest.getAbsolutePath() );
                Toast.makeText( this, "افزونه‌های AMX Mod X نصب شد (" + assetName + ": "
                        + count + " فایل)", Toast.LENGTH_SHORT ).show();
        }

        /**
         * v12: rewrite metamod plugins.ini after the addons extraction.
         *
         * - The AMX Mod X core plugin keeps its relative path (it lives inside
         *   the extracted addons tree).
         * - YaPB is loaded as a METAMOD PLUGIN now (v12 removed -dll @yapb:
         *   the yapb wrapper does not re-export entity spawn symbols, so the
         *   engine failed every map entity with "No spawn function"). YaPB
         *   needs an absolute path to its library inside this app's
         *   nativeLibraryDir, which is only known at runtime — so we write it
         *   here, right after the extraction.
         */
        private void patchPluginsIni() {
                try {
                        File ini = new File( getXashDir(), "cstrike/addons/metamod/plugins.ini" );
                        File dir = ini.getParentFile();
                        if( dir == null )
                                return;
                        dir.mkdirs();

                        StringBuilder sb = new StringBuilder();
                        sb.append( ";;;\n" );
                        sb.append( "; Metamod plugin list for Android (xash3d-fwgs)\n" );
                        sb.append( "; AMX Mod X core:\n" );
                        sb.append( ";;;\n" );
                        sb.append( "linux addons/amxmodx/dlls/libmm_amxmodx.so\n" );

                        // YaPB bot plugin (absolute path, ABI-dependent)
                        String nativeDir = getApplicationInfo().nativeLibraryDir;
                        String[] abis = android.os.Build.SUPPORTED_ABIS;
                        String yapbName = null;
                        if( abis != null && abis.length > 0 && abis[0].contains( "arm64" ) )
                                yapbName = "libyapb_android_arm64.so";
                        else if( abis != null && abis.length > 0 )
                                yapbName = "libyapb_android_armv7l.so";

                        if( yapbName != null && nativeDir != null ) {
                                File yapb = new File( nativeDir, yapbName );
                                if( yapb.isFile() ) {
                                        sb.append( ";\n; YaPB bot (as metamod plugin):\n" );
                                        sb.append( "linux " ).append( yapb.getAbsolutePath() ).append( "\n" );
                                }
                        }

                        java.io.PrintWriter pw = new java.io.PrintWriter( new java.io.FileWriter( ini, false ) );
                        pw.print( sb.toString() );
                        pw.close();
                        Log.i( TAG, "plugins.ini patched: " + ini.getAbsolutePath() );
                } catch( Throwable t ) {
                        Log.w( TAG, "plugins.ini patch failed", t );
                }
        }

        // ------------------------------------------------------------------
        // engine launch
        // ------------------------------------------------------------------

        private void launchEngine() {
                String pkg = "su.xash.engine.test";

                try {
                        getPackageManager().getPackageInfo( pkg, 0 );
                } catch( PackageManager.NameNotFoundException e ) {
                        try {
                                pkg = "su.xash.engine";
                                getPackageManager().getPackageInfo( pkg, 0 );
                        } catch( PackageManager.NameNotFoundException ex ) {
                                startActivity( new Intent( Intent.ACTION_VIEW, Uri.parse( "https://github.com/FWGS/xash3d-fwgs/releases/tag/continuous" ) ).setFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK ) );
                                finish();
                                return;
                        }
                }

                startActivity( new Intent().setComponent( new ComponentName( pkg, "su.xash.engine.XashActivity" ) )
                        .setFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK )
                        .putExtra( "gamedir", "cstrike" )
                        .putExtra( "gamelibdir", getApplicationInfo().nativeLibraryDir )
                        .putExtra( "argv", "-dev 2 -log" )  // v13: no -dll @yapb -- YaPB loads via metamod plugins.ini
                        .putExtra( "package", getPackageName() ));
                finish();
        }
}
