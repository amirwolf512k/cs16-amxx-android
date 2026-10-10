/*
JoinClass.cpp - Character class selection menu
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
#include "PlayerModelView.h"

class CClientJoinClass : public CClientWindow
{
public:
	typedef CClientWindow BaseClass;
	CClientJoinClass() : BaseClass( "CClientJoinClass" ) {}

	CEventCallback MakeCb( const char *cmd )
	{
		return CEventCallback( MenuCb( &CClientJoinClass::classPressedCb ), (void*)cmd );
	}

	CEventCallback MakeFocusCb( const char *cmd )
	{
		return CEventCallback( MenuCb( &CClientJoinClass::classFocusCb ), (void*)cmd );
	}

	void _Init();
	void VidInit();
	void Reload();

	CMenuAction *AddButton( int key, const char *name, const char *modelname, Point pos );

	bool hasCzero;
	CMenuPlayerModelView player;
	CMenuAction text;

	// the class buttons in creation order, with the resource/UI/
	// Classmenu_*.res fieldNames that place them
	CMenuAction *m_classBtns[6];
	const char *m_classFields[6];
	int m_numClassBtns;
	CMenuAction *cancelBtn;
	const char *m_resPaths[2];
	int m_numResPaths;

	void ConfirmSelection()
	{
		UI_CloseClientMenu();
		EngFuncs::ClientCmd( false, command );
	}

protected:
	const char *m_szDefaultClass;

private:
	CUtlString command;

	void classFocusCb( void *pExtra )
	{
		player.Show();
		text.Show();
		const char *sz = (const char *)pExtra;
		const char *loctext = NULL;
		CUtlString model;
		
		struct ClassEntry {
			const char *modelT;
			const char *modelCT;
			const char *labelT;
			const char *labelCT;
			int joinClass;
		};

		static constexpr int JOINCLASS_AUTO_CS = 5;
		static constexpr int JOINCLASS_AUTO_CZ = 6;

		static const ClassEntry classTable[] =
		{
			{ "terror",   "urban",    "Cstrike_Terror_Label", "Cstrike_Urban_Label", 1 },
			{ "leet",     "gsg9",     "Cstrike_Leet_Label", "Cstrike_GSG9_Label", 2 },
			{ "arctic",   "sas",      "Cstrike_Arctic_Label", "Cstrike_SAS_Label", 3 },
			{ "guerilla", "gign",     "Cstrike_Guerilla_Label", "Cstrike_GIGN_Label", 4 },
		};

		static const ClassEntry czeroClass = { "militia",  "spetsnaz", "Cstrike_Militia_Label", "Cstrike_Spetsnaz_Label", 5 };

		static constexpr int TOTAL_CLASSES = (int)(sizeof(classTable) / sizeof(classTable[0]));

		bool showModel = true;

		if( !strcmp( sz, "t_random" ) || !strcmp( sz, "ct_random" ) )
		{
			int joinClass = hasCzero ? JOINCLASS_AUTO_CZ : JOINCLASS_AUTO_CS;
			command.Format( "joinclass %d", joinClass );
			loctext = L( "Cstrike_AutoSelect_Label" );
			showModel = false;
		}
		else if( hasCzero && !strcmp( sz, czeroClass.modelT ) )
		{
			command.Format( "joinclass %d", czeroClass.joinClass );
			loctext = L( czeroClass.labelT );
		}
		else if( hasCzero && !strcmp( sz, czeroClass.modelCT ) )
		{
			command.Format( "joinclass %d", czeroClass.joinClass );
			loctext = L( czeroClass.labelCT );
		}
		else
		{
			int idx;
			for( idx = 0; idx < TOTAL_CLASSES; idx++ )
			{
				const ClassEntry &entry = classTable[idx];
				if( !strcmp( sz, entry.modelT ) )
				{
					command.Format( "joinclass %d", entry.joinClass );
					loctext = L( entry.labelT );
					break;
				}

				if( !strcmp( sz, entry.modelCT ) )
				{
					command.Format( "joinclass %d", entry.joinClass );
					loctext = L( entry.labelCT );
					break;
				}
			}

			if( idx >= TOTAL_CLASSES )
				showModel = false;
		}

		if( !loctext )
			loctext = "";
		text.SetText( loctext );

		if( showModel && ui_showclassmodels && ui_showclassmodels->value ) // try to load model
		{
			player.ent = EngFuncs::GetPlayerModel();
			if( player.ent )
			{
				model.Format( "models/player/%s/%s.mdl", sz, sz );
				player.hPlayerImage = 0;
				player.eOverrideMode = CMenuPlayerModelView::PMV_SHOWMODEL;
				EngFuncs::SetModel( player.ent, model );
				if( !player.ent->model )
				{
					showModel = false;
				}
			}
			else
			{
				showModel = false;
			}
		}
		else
		{
			showModel = false;
		}

		if( !showModel )
		{
			model.Format( "gfx/vgui/%s.tga", sz );

			player.hPlayerImage = EngFuncs::PIC_Load( model );
			player.eOverrideMode = CMenuPlayerModelView::PMV_SHOWIMAGE;
		}
	}

	void classPressedCb( void *pExtra )
	{
		classFocusCb( pExtra );
		ConfirmSelection();
	}
};

static class CClientJoinClassT: public CClientJoinClass
{
	typedef CClientJoinClass BaseClass;
public:
	void _Init();
} uiJoinClassT;

static class CClientJoinClassCT: public CClientJoinClass
{
	typedef CClientJoinClass BaseClass;
public:
	void _Init();
} uiJoinClassCT;

void CClientJoinClass::_Init()
{

	m_numClassBtns = 0;
	m_numResPaths = 0;
	m_resPaths[0] = m_resPaths[1] = NULL;

	player.SetRect( 400, 180, 400, 284 );
	player.backgroundColor = uiColorBlack;
	player.colorStroke = uiPromptTextColor;
	player.iStrokeWidth = 1;
	player.eFocusAnimation = QM_NOFOCUSANIMATION;
	player.bDrawAsPlayer = false;
	player.iFlags |= QMF_MOUSEONLY;
	player.m_bCanCaptureFocus = false;

	text.SetRect( 400, 500, 500, 200 );
	text.SetBackground( 0U );
	text.iFlags |= QMF_INACTIVE;
	text.SetCharSize( QM_TINYFONT );

	// the pc class dialog carries a Cancel button (resource/UI/
	// Classmenu_*.res); hidden until the resource places it
	cancelBtn = CClientWindow::AddButton( '0', L( "Cstrike_Cancel" ), Point( 100, 580 ),
		CEventCallback( VoidCb( &CMenuBaseWindow::Hide )) );
	cancelBtn->Hide();

	szName = L( "Cstrike_Join_Class" );
	AddItem( player );
	AddItem( text );
}

void CClientJoinClass::VidInit()
{
	BaseClass::VidInit();

	if( !m_numResPaths )
		return;

	// the class select dialog of the retail client is built from
	// resource/UI/Classmenu_TER.res / Classmenu_CT.res
	CMenuResDialog res;

	if( !LoadResLayout( res, m_resPaths, m_numResPaths ))
		return;

	if( !ResApplyFrame( res, "ClassMenu", 76, 0, 552, 448 ))
		return;

	ResApplyTitle( res, "joinClass" );

	for( int i = 0; i < m_numClassBtns; i++ )
		ResPlaceControl( res, m_classFields[i], m_classBtns[i] );

	// the model preview lives in the ClassInfo panel area, clamped
	// into the frame (the stock file lets the panel run past it)
	const CMenuResBlock *info = res.FindControl( "ClassInfo" );

	if( info )
	{
		int cx, cy, cw, ch;
		info->RectSelf( m_resFrameW, m_resFrameH, cx, cy, cw, ch, RES_CLIENT_INSET );

		if( cy + ch > m_resFrameH - 8 )
			ch = m_resFrameH - cy - 8;

		if( ch > 0 )
			player.SetRect( cx, cy, cw, ch );

		// the class name line sits where the stock file keeps
		// classInfoLabel (above the panel, right column)
		const CMenuResBlock *lbl = res.FindControl( "classInfoLabel" );

		if( lbl )
		{
			int lx, ly, lw, lh;
			lbl->RectSelf( m_resFrameW, m_resFrameH, lx, ly, lw, lh, RES_CLIENT_INSET );
			text.SetRect( lx, ly, m_resFrameW - lx - RES_CLIENT_INSET * 2, lh + 8 );
		}
	}

	if( cancelBtn && ResPlaceControl( res, "CancelButton", cancelBtn ))
		cancelBtn->Show();
}

void CClientJoinClass::Reload()
{
	if( cancelBtn && !( cancelBtn->iFlags & QMF_HIDDEN ))
		keys[0] = cancelBtn->onPressed;
	else
		keys[0].Reset();

	text.SetText( "" );
	player.eOverrideMode = CMenuPlayerModelView::PMV_SHOWIMAGE;
	player.hPlayerImage = 0;
	player.ent = EngFuncs::GetPlayerModel();
	if( player.ent )
	{
		player.ent->angles[1] += 15;
	}

	if( m_szDefaultClass )
		classFocusCb( (void*)m_szDefaultClass );
}


CMenuAction *CClientJoinClass::AddButton( int key, const char *name, const char *modelname, Point pos )
{
	CMenuAction *act = CClientWindow::AddButton( key, name, pos, MakeCb( modelname ) );
	act->onGotFocus = MakeFocusCb( modelname );
	return act;
}

void CClientJoinClassT::_Init()
{
	m_szDefaultClass = "terror";
	m_numResPaths = 1;
	m_resPaths[0] = "resource/UI/Classmenu_TER.res";
	m_classBtns[m_numClassBtns] = AddButton( '1', L( "Cstrike_Terror" ), "terror",
		Point( 100, 180 ));
	m_classFields[m_numClassBtns++] = "terror";
	m_classBtns[m_numClassBtns] = AddButton( '2', L( "Cstrike_L337_Krew" ), "leet",
		Point( 100, 230 ));
	m_classFields[m_numClassBtns++] = "leet";
	m_classBtns[m_numClassBtns] = AddButton( '3', L( "Cstrike_Arctic" ), "arctic",
		Point( 100, 280 ));
	m_classFields[m_numClassBtns++] = "arctic";
	m_classBtns[m_numClassBtns] = AddButton( '4', L( "Cstrike_Guerilla" ), "guerilla",
		Point( 100, 330 ));
	m_classFields[m_numClassBtns++] = "guerilla";
	if( hasCzero )
	{
		m_classBtns[m_numClassBtns] = AddButton( '5', L( "Cstrike_Militia" ), "militia",
			Point( 100, 380 ));
		m_classFields[m_numClassBtns++] = "militia";
		m_classBtns[m_numClassBtns] = AddButton( '6', L( "Cstrike_Auto_Select" ), "t_random",
			Point( 100, 430 ));
		m_classFields[m_numClassBtns++] = "autoselect_t";
	}
	else
	{
		m_classBtns[m_numClassBtns] = AddButton( '5', L( "Cstrike_Auto_Select" ), "t_random",
			Point( 100, 430 ));
		m_classFields[m_numClassBtns++] = "autoselect_t";
	}

	BaseClass::_Init();
}

void CClientJoinClassCT::_Init()
{
	m_szDefaultClass = "urban";
	m_numResPaths = 1;
	m_resPaths[0] = "resource/UI/Classmenu_CT.res";
	m_classBtns[m_numClassBtns] = AddButton( '1', L( "Cstrike_Urban" ), "urban",
		Point( 100, 180 ));
	m_classFields[m_numClassBtns++] = "urban";
	m_classBtns[m_numClassBtns] = AddButton( '2', L( "Cstrike_GSG9" ), "gsg9",
		Point( 100, 230 ));
	m_classFields[m_numClassBtns++] = "gsg9";
	m_classBtns[m_numClassBtns] = AddButton( '3', L( "Cstrike_SAS" ), "sas",
		Point( 100, 280 ));
	m_classFields[m_numClassBtns++] = "sas";
	m_classBtns[m_numClassBtns] = AddButton( '4', L( "Cstrike_GIGN" ), "gign",
		Point( 100, 330 ));
	m_classFields[m_numClassBtns++] = "gign";
	if( hasCzero )
	{
		m_classBtns[m_numClassBtns] = AddButton( '5', L( "Cstrike_Spetsnaz" ), "spetsnaz",
			Point( 100, 380 ));
		m_classFields[m_numClassBtns++] = "spetsnaz";
		m_classBtns[m_numClassBtns] = AddButton( '6', L( "Cstrike_Auto_Select" ), "ct_random",
			Point( 100, 430 ));
		m_classFields[m_numClassBtns++] = "autoselect_ct";
	}
	else
	{
		m_classBtns[m_numClassBtns] = AddButton( '5', L( "Cstrike_Auto_Select" ), "ct_random",
			Point( 100, 430 ));
		m_classFields[m_numClassBtns++] = "autoselect_ct";
	}

	BaseClass::_Init();
}

void UI_JoinClassT_Show( int param1, int param2 )
{
	uiJoinClassT.hasCzero = param1;
	EngFuncs::KEY_SetDest( KEY_MENU );
	uiJoinClassT.Show();
}

void UI_JoinClassCT_Show( int param1, int param2 )
{
	uiJoinClassCT.hasCzero = param1;
	EngFuncs::KEY_SetDest( KEY_MENU );
	uiJoinClassCT.Show();
}
