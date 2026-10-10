/*
ClientWindow.h - base client menu window
Copyright (C) 2018 a1batross
Copyright (C) 2026 $_Vladislav

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
*/

#pragma once

#include "BaseClientWindow.h"
#include "Action.h"
#include "ResLayout.h"

// the vgui surface the dialogs are authored against
#define RES_SURFACE_W	640
#define RES_SURFACE_H	480
// the vgui Frame keeps a thin client border inside itself, the control
// positions start after it
#define RES_CLIENT_INSET	6

class CClientWindow : public CMenuBaseClientWindow
{
public:
	typedef CMenuBaseClientWindow BaseClass;
	CClientWindow( const char *name = "CClientWindow" ) : BaseClass( name )
	{
		// SetCharSize( QM_DEFAULTFONT );
		m_iNumBtns = 0;
		for ( int i = 0; i < 256; i++ )
		{
			keys[i].Reset();
			keyButtons[i] = NULL;
		}
	}
	~CClientWindow()
	{
		for ( int i = 0; i < m_iNumBtns; i++ )
		{
			delete buttons[i];
		}
	}

	void Show() override
	{
		EngFuncs::KEY_SetDest( KEY_MENU );
		EngFuncs::ClientCmd( true, "touch_setclientonly 1" );
		BaseClass::Show();
	}
	void Hide() override
	{
		BaseClass::Hide();
		if ( m_pStack->Count() <= 1 )
		{
			EngFuncs::KEY_ClearStates();
			EngFuncs::KEY_SetDest( KEY_GAME );
			EngFuncs::ClientCmd( false, "touch_setclientonly 0" );
		}
	}

	CEventCallback ExecAndHide( const char *szCmd )
	{
		return CEventCallback( []( CMenuBaseItem *pSelf, void *pExtra )
		                       {
			UI_CloseClientMenu();
			EngFuncs::ClientCmd( false, (const char*)pExtra ); }, (void *)szCmd );
	}

	CMenuAction *AddButton( int key, const char *name, Point pos, CEventCallback callback );

	// ---- .res-driven layout (resource/UI/*.res like the pc client) ----
	// load the dialog resource; the engine filesystem falls back from
	// the mod dir to valve/ itself
	bool LoadResLayout( CMenuResDialog &res, const char *const *paths, int count )
	{
		return res.Load( paths, count );
	}

	// the window frame from the .res dialog block; stores the raw res
	// rect so the control placement anchors to the same frame
	bool ResApplyFrame( const CMenuResDialog &res, const char *fieldName,
				int defX, int defY, int defW, int defH )
	{
		return ResApplyFrameBlock( res.FindControl( fieldName ), defX, defY, defW, defH );
	}

	// the window frame from the file's own dialog block
	bool ResApplyFileFrame( const CMenuResDialog &res, int defX, int defY, int defW, int defH )
	{
		return ResApplyFrameBlock( res.FindFrame(), defX, defY, defW, defH );
	}

	// one control rect from the .res onto an item (window-relative);
	// a control set to visible 0 hides the item, a missing control
	// leaves the item untouched
	bool ResPlaceControl( const CMenuResDialog &res, const char *fieldName,
				CMenuBaseItem *item, bool resize = true )
	{
		return ResPlaceBlock( res.FindControl( fieldName ), item, resize );
	}

	// the same for buttons/labels: the resource labelText wins when it
	// carries one, exactly what the pc ApplySchemeSettings reads
	bool ResPlaceControl( const CMenuResDialog &res, const char *fieldName,
				CMenuAction *item, bool resize = true )
	{
		const CMenuResBlock *b = res.FindControl( fieldName );

		if( !ResPlaceBlock( b, item, resize ))
			return false;

		const char *lbl = b ? b->Str( "labelText" ) : NULL;

		if( lbl && lbl[0] && lbl[0] != ' ' )
			item->SetText( L( lbl[0] == '#' ? lbl + 1 : lbl ));

		return true;
	}

	// the same, found through the button command (the buy dialogs bind
	// their buttons through commands: glock, vest, autobuy...)
	bool ResPlaceByCommand( const CMenuResDialog &res, const char *command,
				CMenuBaseItem *item, bool resize = true )
	{
		return ResPlaceBlock( res.FindByCommand( command ), item, resize );
	}

	// the window title from a .res label (#Token through the language
	// files, plain text as-is)
	void ResApplyTitle( const CMenuResDialog &res, const char *fieldName )
	{
		const CMenuResBlock *b = res.FindControl( fieldName );

		if( !b ) return;

		const char *lbl = b->Str( "labelText" );

		if( !lbl || !lbl[0] || lbl[0] == ' ' ) return;

		szName = L( lbl[0] == '#' ? lbl + 1 : lbl );
	}

	bool KeyUp( int key ) override;
	bool KeyDown( int key ) override;
	void VidInit() override;
	void Draw() override;
	CEventCallback keys[256];

protected:
	// raw .res frame rect of the window, the anchor span for controls
	int m_resFrameW = RES_SURFACE_W;
	int m_resFrameH = RES_SURFACE_H;

	bool ResApplyFrameBlock( const CMenuResBlock *b, int defX, int defY, int defW, int defH )
	{
		if( !b ) return false;

		m_resFrameW = b->Num( "wide", defW );
		m_resFrameH = b->Num( "tall", defH );
		pos.x = b->PosX( "xpos", defX, RES_SURFACE_W );
		pos.y = b->PosY( "ypos", defY, RES_SURFACE_H ) + uiStatic.yOffset;
		size.w = m_resFrameW;
		size.h = m_resFrameH;
		return true;
	}

	bool ResPlaceBlock( const CMenuResBlock *b, CMenuBaseItem *item, bool resize )
	{
		if( !b || !item ) return false;

		int x, y, w, h;
		bool visible = b->RectSelf( m_resFrameW, m_resFrameH, x, y, w, h, RES_CLIENT_INSET );

		item->pos = Point( x, y );

		if( resize )
			item->size = Size( w, h );

		if( !visible )
			item->Hide();

		return true;
	}

protected:
	CMenuAction *buttons[256];
	CMenuBaseItem *keyButtons[256];
	int m_iNumBtns;

private:
	Size roundCornerSize;
	int iTitleHeight;
	int iGap;
};
