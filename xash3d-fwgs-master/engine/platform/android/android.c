/*
android_nosdl.c - android backend
Copyright (C) 2016-2019 mittorn

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
*/
#include "platform/platform.h"
#include "input.h"
#include "client.h"
#include "sound.h"
#include "errno.h"
#include <pthread.h>
#include <sys/prctl.h>

#include <android/log.h>
#include <jni.h>
#if XASH_SDL
#include <SDL.h>
#endif // XASH_SDL

struct jnimethods_s
{
	JNIEnv *env;
	jobject activity;
	jclass actcls;
	jmethodID loadAndroidID;
	jmethodID getAndroidID;
	jmethodID saveAndroidID;
	jmethodID showMOTD; // cs16-amxx-android v20: sandboxed HTML MOTD dialog
} jni;

// cs16-amxx-android v24: dialog visibility state so the client dll can
// hold back the team select menu until the user presses OK (like PC CS).
static qboolean g_motd_dialog_open = false;

void Android_Init( void )
{
	memset( &jni, 0, sizeof( jni ));

#if XASH_SDL
	jni.env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jni.activity = (jobject)SDL_AndroidGetActivity();
	jni.actcls = (*jni.env)->GetObjectClass( jni.env, jni.activity );
	jni.loadAndroidID = (*jni.env)->GetMethodID( jni.env, jni.actcls, "loadAndroidID", "()Ljava/lang/String;" );
	jni.getAndroidID = (*jni.env)->GetMethodID( jni.env, jni.actcls, "getAndroidID", "()Ljava/lang/String;" );
	jni.saveAndroidID = (*jni.env)->GetMethodID( jni.env, jni.actcls, "saveAndroidID", "(Ljava/lang/String;)V" );
	// v30: (title, html) both as raw byte arrays, so a malformed
	// server string can never abort NewStringUTF
	jni.showMOTD = (*jni.env)->GetMethodID( jni.env, jni.actcls, "showMOTD", "([B[B)Z" );

	// v21: a failed lookup leaves a pending exception; clear it so
	// nothing downstream (filesystem assets, SDL) trips over it
	if( (*jni.env)->ExceptionCheck( jni.env ))
		(*jni.env)->ExceptionClear( jni.env );
#endif // !XASH_SDL
}

/*
========================
Android_GetNativeObject
========================
*/

void *Android_GetNativeObject( const char *name )
{
	if( !strcasecmp( name, "JNIEnv" ) )
	{
		return (void *)jni.env;
	}
	else if( !strcasecmp( name, "ActivityClass" ) )
	{
		return (void *)jni.actcls;
	}

	return NULL;
}

/*
========================
Android_GetAndroidID
========================
*/
const char *Android_GetAndroidID( void )
{
	static char id[32];

	if( !COM_StringEmpty( id ))
		return id;

	jstring resultJNIStr = (*jni.env)->CallObjectMethod( jni.env, jni.activity, jni.getAndroidID );
	const char *resultCStr = (*jni.env)->GetStringUTFChars( jni.env, resultJNIStr, NULL );
	Q_strncpy( id, resultCStr, sizeof( id ) );
	(*jni.env)->ReleaseStringUTFChars( jni.env, resultJNIStr, resultCStr );
	(*jni.env)->DeleteLocalRef( jni.env, resultJNIStr );

	return id;
}

/*
========================
Android_LoadID
========================
*/
const char *Android_LoadID( void )
{
	static char id[32];
	jstring resultJNIStr = (*jni.env)->CallObjectMethod( jni.env, jni.activity, jni.loadAndroidID );
	const char *resultCStr = (*jni.env)->GetStringUTFChars( jni.env, resultJNIStr, NULL );
	Q_strncpy( id, resultCStr, sizeof( id ) );
	(*jni.env)->ReleaseStringUTFChars( jni.env, resultJNIStr, resultCStr );
	(*jni.env)->DeleteLocalRef( jni.env, resultJNIStr );

	return id;
}

/*
========================
Android_ShowMOTD

cs16-amxx-android v20: render an HTML MOTD in a sandboxed WebView dialog
managed by the activity. The payload is passed as a raw byte array so a
malformed (non-modified-UTF-8) server string can't abort in NewStringUTF;
Java decodes it as UTF-8 with replacement characters.
v30: takes the window title (server name) as a second argument, exactly
like the original HL1 VGUI MOTD window (vgui_MOTDWindow.cpp).
========================
*/
qboolean Android_ShowMOTD( const char *title, const char *html )
{
	size_t len;
	jbyteArray jbytes, jtitle;
	jboolean shown;

	if( !jni.env || !jni.activity || !jni.showMOTD )
		return false;

	// v30: the window title (server name, like the original HL1
	// VGUI MOTD window) is passed as a second raw byte array
	len = Q_strlen( title );
	jtitle = (*jni.env)->NewByteArray( jni.env, (jsize)len );

	if( !jtitle )
		return false;

	(*jni.env)->SetByteArrayRegion( jni.env, jtitle, 0, (jsize)len, (const jbyte *)title );

	len = Q_strlen( html );
	jbytes = (*jni.env)->NewByteArray( jni.env, (jsize)len );

	if( !jbytes )
	{
		(*jni.env)->DeleteLocalRef( jni.env, jtitle );
		return false;
	}

	(*jni.env)->SetByteArrayRegion( jni.env, jbytes, 0, (jsize)len, (const jbyte *)html );
	shown = (*jni.env)->CallBooleanMethod( jni.env, jni.activity, jni.showMOTD, jtitle, jbytes );
	(*jni.env)->DeleteLocalRef( jni.env, jbytes );
	(*jni.env)->DeleteLocalRef( jni.env, jtitle );

	if( shown )
		g_motd_dialog_open = true; // v24

	// v21: showMOTD catches Throwable internally, but never leave a
	// pending exception behind just in case
	if( (*jni.env)->ExceptionCheck( jni.env ))
	{
		(*jni.env)->ExceptionClear( jni.env );
		return false;
	}

	return shown ? true : false;
}

qboolean Android_IsMOTDDialogOpen( void )
{
	return g_motd_dialog_open;
}

void Android_MOTDDialogClosed( void )
{
	g_motd_dialog_open = false;
}

// called by XashActivity when the MOTD dialog is dismissed (OK button,
// cancel or back button). Static native resolved by symbol lookup in
// the loaded native libraries.
JNIEXPORT void JNICALL Java_su_xash_engine_XashActivity_nativeMOTDClosed( JNIEnv *env, jclass clazz )
{
	(void)env;
	(void)clazz;
	Android_MOTDDialogClosed();
}

/*
========================
Android_SaveID
========================
*/
void Android_SaveID( const char *id )
{
	jstring JStr = (*jni.env)->NewStringUTF( jni.env, id );
	(*jni.env)->CallVoidMethod( jni.env, jni.activity, jni.saveAndroidID, JStr );
	(*jni.env)->DeleteLocalRef( jni.env, JStr );
}

/*
========================
Android_ShellExecute
========================
*/
void Platform_ShellExecute( const char *path, const char *parms )
{
#if XASH_SDL
	SDL_OpenURL( path );
#endif // XASH_SDL
}
