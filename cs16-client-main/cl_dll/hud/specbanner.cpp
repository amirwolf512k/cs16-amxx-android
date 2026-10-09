/***
*
*	Copyright (c) 1999, Valve LLC. All rights reserved.
*
*	This product contains software technology licensed from Id
*	Software, Inc. ("Id Technology")  Id Technology (c) 1996 Id Software, Inc.
*   All Rights Reserved.
*
*   Use, distribution, and modification of this source code and/or resulting
*   object code is restricted to non-commercial enhancements to products from
*   Valve LLC.  All other use, distribution, or modification is prohibited
*   without written permission of Valve LLC.
*
****/
//
// specbanner.cpp
//
// the standard DRC_CMD_BANNER picture: servers ship a tga as a generic
// resource (precache_generic) and name it through svc_director; the PC
// client hung it in the spectator panel top-left, we draw it in the same
// corner while you are dead or spectating. The Android loading window
// shows the same file in its footer while the map loads.
//
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "hud.h"
#include "cl_util.h"
#include "draw_util.h"
#include "triangleapi.h"

void CHudSpecBanner::SetBannerFile( const char *file )
{
	if( !file )
	{
		m_szBannerFile[0] = 0;
		return;
	}

	strncpy( m_szBannerFile, file, sizeof( m_szBannerFile ) - 1 );
	m_szBannerFile[sizeof( m_szBannerFile ) - 1] = 0;
}

int CHudSpecBanner::Init( void )
{
	gHUD.AddHudElem( this );

	m_pBanner = NULL;
	m_flNextProbe = 0.0f;
	m_szBannerFile[0] = 0;
	m_szLoadedFrom[0] = 0;

	return 1;
}

int CHudSpecBanner::VidInit( void )
{
	// probe timer rides on hud time
	m_flNextProbe = 0.0f;

	return 1;
}

// tga header: 18 bytes, width at 12, height at 14 (LE u16)
static bool Banner_TgaSize( const char *path, int *w, int *h )
{
	byte *file = ( byte * )gEngfuncs.COM_LoadFile( ( char * )path, 5, NULL );

	if( !file )
		return false;

	bool ok = false;

	if( file[1] == 0 && ( file[2] == 2 || file[2] == 10 ) && ( file[16] == 24 || file[16] == 32 ))
	{
		int iw = file[12] | ( file[13] << 8 );
		int ih = file[14] | ( file[15] << 8 );

		if( iw > 0 && ih > 0 )
		{
			*w = iw;
			*h = ih;
			ok = true;
		}
	}

	gEngfuncs.COM_FreeFile( file );
	return ok;
}

int CHudSpecBanner::Draw( float flTime )
{
	// waiting players only: dead or on the spectator queue
	if( !g_iUser1 && ( gHUD.m_Scoreboard.m_iPlayerNum <= 0 || !g_PlayerExtraInfo[gHUD.m_Scoreboard.m_iPlayerNum].dead ))
		return 0;

	if( !m_szBannerFile[0] )
		return 0;

	if( !m_pBanner || strcmp( m_szLoadedFrom, m_szBannerFile ))
	{
		if( flTime < m_flNextProbe )
			return 0;

		m_flNextProbe = flTime + 2.0f;

		// the tga comes through the resource download, so the first
		// frames after join may not have it yet
		m_pBanner = gEngfuncs.LoadMapSprite( m_szBannerFile );

		if( m_pBanner )
			strncpy( m_szLoadedFrom, m_szBannerFile, sizeof( m_szLoadedFrom ) - 1 );
		m_szLoadedFrom[sizeof( m_szLoadedFrom ) - 1] = 0;
	}

	if( !m_pBanner )
		return 0;

	// fit the top left corner, keep the tga aspect
	float maxW = ScreenWidth * 0.28f;
	float maxH = ScreenHeight * 0.20f;
	float w = maxW, h = maxW / 4.0f;
	int iw, ih;

	if( Banner_TgaSize( m_szBannerFile, &iw, &ih ))
	{
		w = maxW;
		h = w * ih / iw;

		if( h > maxH )
		{
			h = maxH;
			w = h * iw / ih;
		}
	}

	float x0 = 8.0f;
	float y0 = 8.0f;

	gEngfuncs.pTriAPI->RenderMode( kRenderTransTexture );
	gEngfuncs.pTriAPI->CullFace( TRI_NONE );
	gEngfuncs.pTriAPI->Color4f( 1.0f, 1.0f, 1.0f, 1.0f );
	gEngfuncs.pTriAPI->SpriteTexture( m_pBanner, 0 );
	DrawUtils::Draw2DQuad( x0, y0, x0 + w, y0 + h );

	return 1;
}
