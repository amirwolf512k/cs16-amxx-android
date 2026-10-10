/*
ResLayout.cpp - vgui .res resource loader for the in-game client windows

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

#include "ResLayout.h"
#include "BaseMenu.h"
#include "Utils.h"
#include "enginecallback_menu.h"

#include <string.h>
#include <stdlib.h>
#include <ctype.h>

static bool resStricmp( const char *a, const char *b )
	{
	if( !a || !b )
		return a == b;

	while( *a && *b )
		{
		int ca = tolower( (unsigned char)*a++ );
		int cb = tolower( (unsigned char)*b++ );
		if( ca != cb )
			return false;
	}

	return *a == *b;
}

const char *CMenuResBlock::Str( const char *key ) const
{
	for( CMenuResEntry *e = entries; e; e = e->next )
		{
		if( resStricmp( e->key, key ))
			return e->val;
	}

	return NULL;
}

int CMenuResBlock::Int( const char *key, int def ) const
{
	const char *v = Str( key );

	if( !v || !v[0] )
		return def;

	return atoi( v );
}

int CMenuResBlock::Num( const char *key, int def ) const
{
	return (int)( Int( key, def ) * YScale());
}

// vgui position: plain pixels stretch, "c"/"r"/"b" prefixed numbers
// anchor to the center/right/bottom of the given span; span and def are
// in .res units, scale turns the result into virtual px
static int ResPos( const char *v, int def, int span, float scale )
	{
	if( !v || !v[0] )
		return (int)( def * scale );

	int off = (int)( atoi( v + 1 ) * scale );

	if( v[0] == 'c' || v[0] == 'C' )
		off += (int)( span / 2.0f * scale );
	else if( v[0] == 'r' || v[0] == 'R' || v[0] == 'b' || v[0] == 'B' )
		off += (int)( span * scale );

	return off < 0 ? 0 : off;
}

int CMenuResBlock::PosX( const char *key, int def, int span ) const
{
	return ResPos( Str( key ), def, span, XScale());
}

int CMenuResBlock::PosY( const char *key, int def, int span ) const
{
	return ResPos( Str( key ), def, span, YScale());
}

bool CMenuResBlock::RectSelf( int spanW, int spanH, int &x, int &y, int &w, int &h, int inset ) const
{
	// the frame border offsets the client origin, it does not shrink
	// the controls the resource authors sized to fit inside it
	x = PosX( "xpos", 0, spanW ) + ScaleX( inset );
	y = PosY( "ypos", 0, spanH ) + ScaleY( inset );
	w = ScaleX( Int( "wide", 0 ));
	h = ScaleY( Int( "tall", 0 ));

	if( w <= 0 )
		w = ScaleX( 148 );
	if( h <= 0 )
		h = ScaleY( 20 );

	return ( Int( "visible", 1 ) != 0 );
}

bool CMenuResBlock::Rect( const char *fieldName, int spanW, int spanH, int &x, int &y, int &w, int &h, int inset ) const
{
	const CMenuResBlock *c = FindControl( fieldName );

	if( c )
		return c->RectSelf( spanW, spanH, x, y, w, h, inset );

	return false;
}

const CMenuResBlock *CMenuResBlock::FindControl( const char *fieldName ) const
{
	for( CMenuResBlock *c = firstChild; c; c = c->next )
		{
		if( c->IsControl() && resStricmp( c->FieldName(), fieldName ))
			return c;
	}

	// not a direct child: walk deeper (a wrapped file nests controls
	// one level down)
		for( CMenuResBlock *c = firstChild; c; c = c->next )
		{
		const CMenuResBlock *deep = c->FindControl( fieldName );

		if( deep )
			return deep;
	}

	return NULL;
}

const CMenuResBlock *CMenuResBlock::FindByCommand( const char *command ) const
{
	if( !command || !command[0] )
		return NULL;

	for( CMenuResBlock *c = firstChild; c; c = c->next )
		{
		if( c->IsControl() && resStricmp( c->Str( "command" ), command ))
			return c;
	}

	for( CMenuResBlock *c = firstChild; c; c = c->next )
		{
		const CMenuResBlock *deep = c->FindByCommand( command );

		if( deep )
			return deep;
	}

	return NULL;
}

const CMenuResBlock *CMenuResBlock::FindFrame( void ) const
{
	for( CMenuResBlock *c = firstChild; c; c = c->next )
		{
		const char *cn = c->Str( "ControlName" );

		if( cn && ( resStricmp( cn, "Frame" ) || resStricmp( cn, "WizardPanel" ) ||
		resStricmp( cn, "WizardSubPanel" ) || resStricmp( cn, "PropertyDialog" )))
			return c;
	}

	return NULL;
}

int CMenuResBlock::ScaleX( int v )
	{
	return (int)( v * XScale());
}

int CMenuResBlock::ScaleY( int v )
	{
	return (int)( v * YScale());
}

float CMenuResBlock::XScale( void )
	{
	// the menu virtual space stretches x with the screen, so follow it
	if( uiStatic.width > 0 )
		return uiStatic.width / 640.0f;

	return 1024.0f / 640.0f;
}

float CMenuResBlock::YScale( void )
	{
	return 768.0f / 480.0f;
}

// ---------------------------------------------------------------- loader

void CMenuResDialog::SkipSpace( const char *&p )
	{
	for( ;; )
		{
		while( *p && isspace( (unsigned char)*p ))
			p++;

		if( p[0] == '/' && p[1] == '/' )
			{
			while( *p && *p != '\n' )
				p++;
			continue;
		}

		if( p[0] == '/' && p[1] == '*' )
			{
			p += 2;

			while( *p && !( p[0] == '*' && p[1] == '/' ))
				p++;

			if( *p )
				p += 2;
			continue;
		}

		return;
	}
}

bool CMenuResDialog::ReadToken( const char *&p, char *out, size_t size, bool &quoted )
	{
	SkipSpace( p );

	if( !*p )
		return false;

	if( *p == '{' || *p == '}' )
		{
		out[0] = *p++;
		out[1] = '\0';
		quoted = false;
		return true;
	}

	if( *p == '"' )
		{
		p++;
		size_t o = 0;
		quoted = true;

		while( *p )
			{
			if( *p == '\\' && p[1] )
				{
				if( o + 1 < size )
					out[o++] = p[1];
				p += 2;
				continue;
			}

			if( *p == '"' )
				{
				p++;
				break;
			}

			if( o + 1 < size )
				out[o++] = *p;
			p++;
		}

		out[o] = '\0';
		return true;
	}

	size_t o = 0;
	quoted = false;

	while( *p && !isspace( (unsigned char)*p ) && *p != '"' && *p != '{' && *p != '}' )
		{
		if( o + 1 < size )
			out[o++] = *p;
		p++;
	}

	out[o] = '\0';
	return o > 0;
}

char *CMenuResDialog::Dup( const char *s )
	{
	size_t n = strlen( s ) + 1;
	char *d = new char[n];
	memcpy( d, s, n );
	return d;
}

bool CMenuResDialog::ParseBlock( const char *&p, CMenuResBlock *into, int depth )
	{
	char tok[512];
	bool quoted;

	while( ReadToken( p, tok, sizeof( tok ), quoted ))
		{
		if( tok[0] == '}' && !quoted )
		return true;

	if( tok[0] == '{' && !quoted ) // stray opener: skip its body
		{
			if( depth > 7 )
				return false;

			CMenuResBlock anon;
			memset( &anon, 0, sizeof( anon ));
			if( !ParseBlock( p, &anon, depth + 1 ))
				return false;
			FreeBlock( &anon );
			continue;
		}

		char key[128];
		Q_strncpy( key, tok, sizeof( key ));

		if( !ReadToken( p, tok, sizeof( tok ), quoted ))
			break;

		if( tok[0] == '{' && !quoted )
			{
				if( depth > 7 )
					return false;

				CMenuResBlock *child = new CMenuResBlock;
				memset( child, 0, sizeof( *child ));
				Q_strncpy( child->name, key, sizeof( child->name ));

				if( !ParseBlock( p, child, depth + 1 ))
					{
					FreeBlock( child );
					delete child;
					return false;
				}

				// append keeps the file order, which is the tab order
				// the retail dialogs read their buttons in
				child->next = NULL;

				if( !into->firstChild )
					{
					into->firstChild = child;
				}
				else
					{
					CMenuResBlock *last = into->firstChild;
					while( last->next )
						last = last->next;
					last->next = child;
				}
			}
			else if( tok[0] == '}' && !quoted )
			{
			// "key" alone on a line: vgui keeps it as its own value
			CMenuResEntry *e = new CMenuResEntry;
			e->key = Dup( key );
			e->val = Dup( key );
			e->next = NULL;

			if( !into->entries )
				{
				into->entries = e;
			}
			else
				{
				CMenuResEntry *last = into->entries;
				while( last->next )
					last = last->next;
				last->next = e;
			}

			return true;
		}
		else
			{
			CMenuResEntry *e = new CMenuResEntry;
			e->key = Dup( key );
			e->val = Dup( tok );
			e->next = NULL;

			if( !into->entries )
				{
				into->entries = e;
			}
			else
				{
				CMenuResEntry *last = into->entries;
				while( last->next )
					last = last->next;
				last->next = e;
			}
		}
	}

	return true;
}

void CMenuResDialog::FreeBlock( CMenuResBlock *b )
	{
	CMenuResEntry *e = b->entries;

	while( e )
		{
		CMenuResEntry *nx = e->next;
		delete[] e->key;
		delete[] e->val;
		delete e;
		e = nx;
	}
	b->entries = NULL;

	CMenuResBlock *c = b->firstChild;

	while( c )
		{
		CMenuResBlock *nx = c->next;
		FreeBlock( c );
		delete c;
		c = nx;
	}
	b->firstChild = NULL;
}

bool CMenuResDialog::Load( const char *const *paths, int count )
	{
	Free();

	for( int i = 0; i < count && !m_root; i++ )
		{
		char *text = (char *)EngFuncs::COM_LoadFile( paths[i] );

		if( !text )
			continue;

		// a utf-16 file (the language .txt kind) is not a .res; the
		// parser would read garbage out of it, skip those early
		if( ((unsigned char)text[0] == 0xFF && (unsigned char)text[1] == 0xFE) ||
		((unsigned char)text[0] == 0xFE && (unsigned char)text[1] == 0xFF) )
			{
			EngFuncs::COM_FreeFile( text );
			continue;
		}

		const char *p = text;
		char tok[512];
		bool quoted;

		if( ReadToken( p, tok, sizeof( tok ), quoted ) && tok[0] != '{' )
			{
				if( ReadToken( p, tok, sizeof( tok ), quoted ) && tok[0] == '{' && !quoted )
					{
						CMenuResBlock *root = new CMenuResBlock;
						memset( root, 0, sizeof( *root ));

						// keep the wrapper name ("Resource/UI/X.res") out of
						// the way, the controls are what matters
						if( !ParseBlock( p, root, 0 ))
							{
							FreeBlock( root );
							delete root;
							root = NULL;
						}

						// every string was duplicated out of the file, the
						// file buffer itself is not kept
						if( root )
							m_root = root;
					}
				}

				EngFuncs::COM_FreeFile( text );
			}

			return m_root != NULL;
		}

		void CMenuResDialog::Free( void )
			{
			if( m_root )
				{
				FreeBlock( m_root );
				delete m_root;
				m_root = NULL;
			}

			if( m_text )
				{
				EngFuncs::COM_FreeFile( m_text );
				m_text = NULL;
			}
		}

		const CMenuResBlock *CMenuResDialog::FindControl( const char *fieldName ) const
		{
			if( !m_root )
				return NULL;

			return m_root->FindControl( fieldName );
		}

		const CMenuResBlock *CMenuResDialog::FindByCommand( const char *command ) const
		{
			if( !m_root )
				return NULL;

			return m_root->FindByCommand( command );
		}

		const CMenuResBlock *CMenuResDialog::FindFrame( void ) const
		{
			if( !m_root )
				return NULL;

			return m_root->FindFrame();
		}
