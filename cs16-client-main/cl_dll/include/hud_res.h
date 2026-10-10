/*
hud_res.h - minimal vgui keyvalues (.res) reader for the in-game HUD
dialogs, header-only so both link units can use it

Copyright (C) 2026 Z.ai

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

#ifndef HUD_RES_H
#define HUD_RES_H

/*
The retail client builds its HUD dialogs (scoreboard, spectator bars)
from resource/UI/*.res through vgui2 LoadControlSettings. This reader
parses the same files so mods and servers can reskin the Android HUD
exactly like they reskin the PC one. The dialogs are authored against
a 640x480 vgui surface; like the retail client on widescreen, X
stretches with the screen width and Y with the screen height.
*/

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#if defined( _WIN32 )
	#define hud_stricmp _stricmp
#else
	#include <strings.h>
#define hud_stricmp strcasecmp
#endif

#define HUD_RES_MAX_BLOCKS 96
#define HUD_RES_MAX_ENTRIES 640
#define HUD_RES_MAX_DEPTH 8

typedef struct
{
	int owner;                    // index of the owning block, -1 = root
	char key[48];
	char val[192];
} hud_res_entry_t;

typedef struct
{
	char name[48];
	int parent;                   // index of the parent block, -1 = none
} hud_res_block_t;

typedef struct
{
	hud_res_block_t blocks[HUD_RES_MAX_BLOCKS];
	int nblocks;
	hud_res_entry_t *entries;
	int nentries;
} hud_res_t;

static void hud_res_skip( const char **p )
	{
	for( ;; )
		{
		while( **p && isspace( (unsigned char)**p ))
			(*p)++;

		if( (*p)[0] == '/' && (*p)[1] == '/' )
			{
			while( **p && **p != '\n' )
				(*p)++;
			continue;
		}

		if( (*p)[0] == '/' && (*p)[1] == '*' )
			{
			*p += 2;

			while( **p && !( (*p)[0] == '*' && (*p)[1] == '/' ))
				(*p)++;

			if( **p )
				*p += 2;
			continue;
		}

		return;
	}
}

static int hud_res_token( const char **p, char *out, size_t size )
	{
	hud_res_skip( p );

	if( !**p )
		return 0;

	if( **p == '{' || **p == '}' )
		{
		out[0] = **p;
		out[1] = '\0';
		(*p)++;
		return 1;
	}

	if( **p == '"' )
		{
		size_t o = 0;
		(*p)++;

		while( **p )
			{
			if( **p == '\\' && (*p)[1] )
				{
				if( o + 1 < size )
					out[o++] = (*p)[1];
				*p += 2;
				continue;
			}

			if( **p == '"' )
				{
				(*p)++;
				break;
			}

			if( o + 1 < size )
				out[o++] = **p;
			(*p)++;
		}

		out[o] = '\0';
		return 1;
	}

	size_t o = 0;

	while( **p && !isspace( (unsigned char)**p ) && **p != '"' && **p != '{' && **p != '}' )
		{
		if( o + 1 < size )
			out[o++] = **p;
		(*p)++;
	}

	out[o] = '\0';
	return o > 0;
}

// recursive-descent parse into the block/entry tables; entries record
// their owning block so parent and child leaves never alias
static int hud_res_parse( const char **p, hud_res_t *res, int parent, int depth )
	{
	char tok[256];

	while( hud_res_token( p, tok, sizeof( tok )))
		{
		if( tok[0] == '}' )
		return 1;

	if( tok[0] == '{' ) // stray opener
		{
			if( depth > HUD_RES_MAX_DEPTH || !hud_res_parse( p, res, parent, depth + 1 ))
				return 0;
			continue;
		}

		char key[sizeof(((hud_res_entry_t *)0)->key)];
		strncpy( key, tok, sizeof( key ) - 1 );
		key[sizeof( key ) - 1] = '\0';

		if( !hud_res_token( p, tok, sizeof( tok )))
			break;

		if( tok[0] == '{' )
			{
				if( depth > HUD_RES_MAX_DEPTH )
					return 0;

				if( res->nblocks >= HUD_RES_MAX_BLOCKS )
					{
					// block table full: swallow the body, keep parsing
					if( !hud_res_parse( p, res, parent, depth + 1 ))
						return 0;
					continue;
				}

				hud_res_block_t *b = &res->blocks[res->nblocks];
				strncpy( b->name, key, sizeof( b->name ) - 1 );
				b->name[sizeof( b->name ) - 1] = '\0';
				b->parent = parent;
				res->nblocks++;

				if( !hud_res_parse( p, res, res->nblocks - 1, depth + 1 ))
					return 0;
			}
			else
				{
				if( res->nentries < HUD_RES_MAX_ENTRIES )
					{
					hud_res_entry_t *e = &res->entries[res->nentries++];
					e->owner = parent;
					strncpy( e->key, key, sizeof( e->key ) - 1 );
					e->key[sizeof( e->key ) - 1] = '\0';

					if( tok[0] == '}' )
					{
					// "key" alone: vgui keeps it as its own value
					strncpy( e->val, key, sizeof( e->val ) - 1 );
				}
				else
					{
					strncpy( e->val, tok, sizeof( e->val ) - 1 );
				}
				e->val[sizeof( e->val ) - 1] = '\0';
			}

			if( tok[0] == '}' )
			return 1;
	}
}

return 1;
}

// try the paths in order through the engine filesystem (mod dir with
// the valve/ fallback), stop at the first one that parses
static hud_res_t *HUD_ResLoad( const char *const *paths, int count )
	{
	for( int i = 0; i < count; i++ )
		{
		byte *file = gEngfuncs.COM_LoadFile( paths[i], 5, NULL );

		if( !file )
			continue;

		// utf-16 files (the language .txt kind) are not keyvalues
		if(( file[0] == 0xFF && file[1] == 0xFE ) || ( file[0] == 0xFE && file[1] == 0xFF ))
			{
			gEngfuncs.COM_FreeFile( file );
			continue;
		}

		hud_res_t *res = (hud_res_t *)malloc( sizeof( hud_res_t ));

		if( !res )
			{
			gEngfuncs.COM_FreeFile( file );
			continue;
		}

		memset( res, 0, sizeof( hud_res_t ));
		res->entries = (hud_res_entry_t *)malloc( sizeof( hud_res_entry_t ) * HUD_RES_MAX_ENTRIES );

		if( !res->entries )
			{
			free( res );
			gEngfuncs.COM_FreeFile( file );
			continue;
		}
		const char *p = (const char *)file;
		char tok[256];

		if( hud_res_token( &p, tok, sizeof( tok )) && tok[0] != '{' )
			{
				if( hud_res_token( &p, tok, sizeof( tok )) && tok[0] == '{' )
					{
						if( !hud_res_parse( &p, res, -1, 0 ))
							{
							free( res->entries );
							free( res );
							gEngfuncs.COM_FreeFile( file );
							continue;
						}
					}
				}

				gEngfuncs.COM_FreeFile( file );

				if( res->nblocks == 0 && res->nentries == 0 )
					{
					free( res->entries );
					free( res );
					continue;
				}

				return res;
			}

			return NULL;
		}

		static void HUD_ResFree( hud_res_t *res )
			{
			if( !res )
				return;

			free( res->entries );
			free( res );
		}

		// a control by fieldName, searched through the whole tree the way
		// vgui2 collects the controls of a dialog resource
		static const hud_res_block_t *HUD_ResFind( const hud_res_t *res, const char *fieldName )
			{
			if( !res )
				return NULL;

			for( int b = 0; b < res->nblocks; b++ )
				{
				const char *cn = NULL, *fn = NULL;

				for( int e = 0; e < res->nentries; e++ )
					{
					if( res->entries[e].owner != b )
						continue;

					if( !hud_stricmp( res->entries[e].key, "ControlName" ))
						cn = res->entries[e].val;
					else if( !hud_stricmp( res->entries[e].key, "fieldName" ))
						fn = res->entries[e].val;
				}

				if( cn && fn && !hud_stricmp( fn, fieldName ))
					return &res->blocks[b];
			}

			return NULL;
		}

		static const char *HUD_ResStr( const hud_res_t *res, const hud_res_block_t *b, const char *key )
			{
			if( !res || !b )
				return NULL;

			int idx = (int)( b - res->blocks );

			for( int e = 0; e < res->nentries; e++ )
				{
				if( res->entries[e].owner == idx && !hud_stricmp( res->entries[e].key, key ))
					return res->entries[e].val;
			}

			return NULL;
		}

		static int HUD_ResInt( const hud_res_t *res, const hud_res_block_t *b, const char *key, int def )
			{
			const char *v = HUD_ResStr( res, b, key );

			if( !v || !v[0] )
				return def;

			return atoi( v );
		}

		// vgui position into pixels: plain numbers stretch by scale,
		// "c"/"r"/"b" prefixed anchor to the center/right/bottom of spanPx
		static int HUD_ResPos( const char *v, int def, int spanPx, float scale )
			{
			if( !v || !v[0] )
				return (int)( def * scale );

			int off = (int)( atoi( v + 1 ) * scale );

			if( *v == 'c' || *v == 'C' )
				off += (int)( spanPx / 2.0f );
			else if( *v == 'r' || *v == 'R' || *v == 'b' || *v == 'B' )
				off += spanPx;

			return off < 0 ? 0 : off;
		}

		static int HUD_ResPosX( const hud_res_t *res, const hud_res_block_t *b, const char *key, int def, int spanPx, float scale )
			{
			return HUD_ResPos( HUD_ResStr( res, b, key ), def, spanPx, scale );
		}

		#endif // HUD_RES_H
