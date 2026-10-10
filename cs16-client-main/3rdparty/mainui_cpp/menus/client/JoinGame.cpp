/*
JoinGame.cpp - Team selection menu
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

#include "BaseMenu.h"
#include "ClientWindow.h"
#include "ScrollView.h"

static class CClientJoinGame : public CClientWindow
{
public:
	typedef CClientWindow BaseClass;
	CClientJoinGame() : BaseClass( "CClientJoinGame" ) {}

	void _Init();
	void VidInit();
	void Reload();

	bool hasSpectator;
	bool hasVIP;
	bool hasCancel;
	CMenuAction *spectate;
	CMenuAction *vipbutton;
	CMenuAction *btnTer;
	CMenuAction *btnCT;
	CMenuAction *btnAuto;
	CMenuAction *cancel;
	CMenuAction text;
	CMenuScrollView scroll;

	CEventCallback MakeCb( const char *cmd )
	{
		return CEventCallback( MenuCb( &CClientJoinGame::teamPressedCb ), (void *)cmd );
	}

private:
	const char *command;

	void ConfirmSelection()
	{
		Hide();
		EngFuncs::ClientCmd( false, command );
	}

	void teamPressedCb( void *pExtra )
	{
		command = (const char *)pExtra;
		ConfirmSelection();
	}

} uiJoinGame;

void CClientJoinGame::_Init()
{
	CMenuAction *btn;
	btn = btnTer = AddButton( '1', L( "Cstrike_Terrorist_Forces" ),
	                 Point( 100, 180 ), MakeCb( "jointeam 1" ) );

	btn = btnCT = AddButton( '2', L( "Cstrike_CT_Forces" ),
	                 Point( 100, 230 ), MakeCb( "jointeam 2" ) );

	vipbutton = AddButton( '3', L( "Cstrike_VIP_Team" ),
	                       Point( 100, 280 ), MakeCb( "jointeam 3" ) );

	btn = btnAuto = AddButton( '5', L( "Cstrike_Team_AutoAssign" ),
	                 Point( 100, 380 ), MakeCb( "jointeam 5" ) );

	spectate = AddButton( '6', L( "Cstrike_Menu_Spectate" ),
	                      Point( 100, 430 ), MakeCb( "jointeam 6" ) );

	cancel = AddButton( '0', L( "Cstrike_Cancel" ),
	                    Point( 100, 530 ), CEventCallback( VoidCb( &CMenuBaseWindow::Hide ) ) );

	scroll.SetRect( 400, 180, 400, 200 );
	scroll.bDrawStroke = true;
	scroll.colorStroke = uiInputTextColor;
	scroll.iStrokeWidth = 1;

	text.SetRect( 0, 0, 0, 0 );
	text.SetBackground( 0U );
	text.SetInactive( true );
	text.SetCharSize( QM_SMALLERFONT );
	text.m_bLimitBySize = false;

	scroll.AddItem( text );
	AddItem( scroll );

	szName = L("Cstrike_Join_Team");
}

void CClientJoinGame::VidInit()
{
	BaseClass::VidInit();

	// the retail team select dialog is resource/UI/TeamMenu.res (some
	// HLDS installs land it as Teammenu.res, try both); when the game
	// ships it, the window and every button sit where the resource
	// says - the way server plugins reskin this dialog on the pc by
	// rewriting the file
	static const char *paths[] =
	{
		"resource/UI/TeamMenu.res",
		"resource/UI/Teammenu.res",
	};

	CMenuResDialog res;

	if( !LoadResLayout( res, paths, 2 ))
		return;

	if( ResApplyFrame( res, "TeamMenu", 76, 0, 552, 448 ))
	{
		ResApplyTitle( res, "joinTeam" );
		ResPlaceControl( res, "terbutton", btnTer );
		ResPlaceControl( res, "ctbutton", btnCT );
		ResPlaceControl( res, "vipbutton", vipbutton );
		ResPlaceControl( res, "autobutton", btnAuto );
		ResPlaceControl( res, "specbutton", spectate );
		ResPlaceControl( res, "CancelButton", cancel );
		// the map briefing panel, the pc dialog renders the map
		// description html there
		ResPlaceControl( res, "MapInfo", &scroll );
	}
}

void CClientJoinGame::Reload()
{
	if( hasSpectator )
	{
		spectate->Show();
		keys[6] = spectate->onPressed;
	}
	else
	{
		keys[6].Reset();
		spectate->Hide();
	}

	if( hasVIP )
	{
		keys[3] = vipbutton->onPressed;
		vipbutton->Show();
	}
	else
	{
		keys[3].Reset();
		vipbutton->Hide();
	}

	if( hasCancel )
	{
		keys[0] = cancel->onPressed;
		cancel->Show();
	}
	else
	{
		keys[0].Reset();
		cancel->Hide();
	}

	const char *mapname = g_pClient->GetLevelName();

	if( mapname && mapname[0] )
	{
		char buf[256];
		snprintf( buf, 256, "maps/%s.txt", mapname );

		char *txt = (char*)EngFuncs::COM_LoadFile( buf );

		if( txt )
		{
			text.SetText( txt );

			EngFuncs::COM_FreeFile( txt );
		}
		else
		{
			text.SetText( L( "Cstrike_TitlesTXT_Map_Description_not_available" ) );
		}
	}
	else
	{
		text.SetText( L( "Cstrike_TitlesTXT_Map_Description_not_available" ) );
	}

	text.size.h = 0; // recalc
	text.size.w = 0; // recalc
	scroll.VidInit();
}
void UI_JoinGame_Show( int param1, int param2 )
{
	uiJoinGame.hasSpectator = param1 & 1;
	uiJoinGame.hasVIP		= param1 & 2;
	uiJoinGame.hasCancel    = param1 & 4;

	uiJoinGame.Show();
}
