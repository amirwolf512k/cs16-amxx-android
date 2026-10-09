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
// the server banner shown while you are dead or spectating (top left
// corner), the way the popular russian/turkish servers greet waiting
// players. The server points clients at an image with
//   client_cmd( id, "cl_spec_banner http://host/banner.png" )
// the engine pulls http urls down into media/spec_banner.png (with a
// "<w> <h>" companion file so the aspect survives), local game paths
// load as-is. Nothing is drawn while you are alive.
//
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "hud.h"
#include "cl_util.h"
#include "draw_util.h"
#include "triangleapi.h"

int CHudSpecBanner::Init( void )
{
	gHUD.AddHudElem( this );

	m_pBanner = NULL;
	m_flNextProbe = 0.0f;
	m_szLoadedFrom[0] = 0;

	cl_spec_banner = CVAR_CREATE( "cl_spec_banner", "", FCVAR_ARCHIVE );

	return 1;
}

int CHudSpecBanner::VidInit( void )
{
	// the probe timer rides on hud time, restart it
	m_flNextProbe = 0.0f;

	return 1;
}

int CHudSpecBanner::Draw( float flTime )
{
	const char *src;

	if( !cl_spec_banner )
		return 0;

	src = cl_spec_banner->string;
	if( !src[0] )
		return 0;

	// waiting players only: dead or on the spectator queue
	if( !g_iUser1 && ( gHUD.m_iPlayerNum <= 0 || !g_PlayerExtraInfo[gHUD.m_iPlayerNum].dead ))
		return 0;

	if( !m_pBanner || strcmp( m_szLoadedFrom, src ))
	{
		if( flTime < m_flNextProbe )
			return 0;

		m_flNextProbe = flTime + 2.0f;

		// http urls arrive pre-downloaded by the engine; anything else
		// is treated as a game path
		if( !strnicmp( src, "http://", 7 ) || !strnicmp( src, "https://", 8 ))
			m_pBanner = gEngfuncs.LoadMapSprite( "media/spec_banner.png" );
		else
			m_pBanner = gEngfuncs.LoadMapSprite( src );

		if( m_pBanner )
			strncpy( m_szLoadedFrom, src, sizeof( m_szLoadedFrom ) - 1 );
		m_szLoadedFrom[sizeof( m_szLoadedFrom ) - 1] = 0;
	}

	if( !m_pBanner )
		return 0;

	// fit into the top left corner, keep the aspect when it is known
	float maxW = ScreenWidth * 0.28f;
	float maxH = ScreenHeight * 0.20f;
	float w = maxW, h = maxW / 4.0f; // classic banner ratio as fallback

	char *meta = ( char * )gEngfuncs.COM_LoadFile( "media/spec_banner.txt", 5, NULL );
	if( meta )
	{
		int iw = 0, ih = 0;

		if( sscanf( meta, "%d %d", &iw, &ih ) == 2 && iw > 0 && ih > 0 )
		{
			w = maxW;
			h = w * ih / iw;

			if( h > maxH )
			{
				h = maxH;
				w = h * iw / ih;
			}
		}

		gEngfuncs.COM_FreeFile( meta );
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
