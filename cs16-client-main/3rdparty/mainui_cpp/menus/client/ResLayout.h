/*
ResLayout.h - vgui .res resource loader for the in-game client windows

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

#pragma once

/*
The retail client builds every in-game dialog (team select, class select,
buy menus, scoreboard, ...) from resource/UI/*.res keyvalues through
vgui2 LoadControlSettings. This loader reads the very same files the same
way, so mods and servers that ship or rewrite those .res files reskin the
Android client exactly like they reskin the PC one.

Coordinates: the dialogs are authored against a 640x480 vgui surface.
Like the retail client at widescreen resolutions, X stretches with the
screen width and Y with the screen height, mapped into the menu system's
virtual space (uiStatic.width x 768).
*/

#include <stddef.h>

// one leaf key/value pair of a block
struct CMenuResEntry
{
	CMenuResEntry *next;
	char *key;
	char *val;
};

// one keyvalues block: a name, its leaves, its child blocks
struct CMenuResBlock
{
	CMenuResBlock *next;   // sibling
	CMenuResBlock *firstChild;
	CMenuResEntry *entries; // leaves of this block, own storage
	char name[64];

	const char *Name( void ) const { return name; }

	// leaf value or NULL
	const char *Str( const char *key ) const;
	int         Int( const char *key, int def ) const;

	// the ControlName/fieldName pair every dialog control carries
	bool        IsControl( void ) const { return Str( "ControlName" ) && Str( "fieldName" ); }
	const char *FieldName( void ) const { return Str( "fieldName" ); }

	// .res geometry into menu virtual space; "c-260"/"r233"/"b10"
	// anchors resolve against the given span, plain numbers stretch
	int PosX( const char *key, int def, int span ) const;
	int PosY( const char *key, int def, int span ) const;
	int Num( const char *key, int def ) const; // plain scaled number

	// the control's own rect, inset applied, visible flag returned
	bool RectSelf( int spanW, int spanH, int &x, int &y, int &w, int &h, int inset ) const;

	// the rect of a direct child control by fieldName
	bool Rect( const char *fieldName, int spanW, int spanH,
	int &x, int &y, int &w, int &h, int inset ) const;

	// child control by fieldName (direct children first)
		const CMenuResBlock *FindControl( const char *fieldName ) const;

	// child control whose "command" leaf matches (the buy dialogs
	// bind their buttons through the command: glock, vest, jointeam 1...)
		const CMenuResBlock *FindByCommand( const char *command ) const;

	// the dialog frame block: first Frame/WizardPanel/WizardSubPanel
	// control in the file (the retail files disagree with their own
	// fieldNames, the ControlName is the reliable part)
		const CMenuResBlock *FindFrame( void ) const;

	// .res unit -> menu virtual space conversions
	static int   ScaleX( int v );
	static int   ScaleY( int v );
	static float XScale(); // menu virtual width / 640
	static float YScale(); // 768 / 480
};

class CMenuResDialog
{
public:
	CMenuResDialog() : m_root( NULL ), m_text( NULL ), m_loadedPath( NULL ) {}
	~CMenuResDialog() { Free(); }

	// try the given resource paths in order (the engine filesystem
	// already falls back from the mod dir to valve/), stop at first hit
	bool Load( const char *const *paths, int count );
	void Free( void );

	bool IsValid( void ) const { return m_root != NULL; }
	const CMenuResBlock *Root( void ) const { return m_root; }

	// the path that hit, for the load log
	const char *LoadedPath( void ) const { return m_loadedPath; }

	// a control anywhere in the file by its fieldName, the way vgui2
	// collects the controls of a dialog resource
	const CMenuResBlock *FindControl( const char *fieldName ) const;

	// a control anywhere in the file by its command leaf
	const CMenuResBlock *FindByCommand( const char *command ) const;

	// the dialog frame block of the file
	const CMenuResBlock *FindFrame( void ) const;

private:
	static void  SkipSpace( const char *&p );
	static bool  ReadToken( const char *&p, char *out, size_t size, bool &quoted );
	static char *Dup( const char *s );
	static bool  ParseBlock( const char *&p, CMenuResBlock *into, int depth );
	static void  FreeBlock( CMenuResBlock *b );

	CMenuResBlock *m_root;
	char *m_text;
	const char *m_loadedPath;
};
