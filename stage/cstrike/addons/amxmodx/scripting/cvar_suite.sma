#include <amxmodx>

// v35 cvar diagnostics: full coverage of the AMXX cvar surface on this port.
// Context: device-side ZP report "Invalid CVAR pointer (handle 0) used in
// native get_pcvar_num". This suite proves the natives themselves and the
// cvar_compat pre-registration (bot_quota / amx_show_activity / zp_* set).

new pc_a, pc_b, pc_c, pc_dup, pc_quota, pc_showact, pc_timelimit, pc_hooked
new g_hookHit

public plugin_init()
{
	register_plugin("CVAR Suite", "1.1", "cs16-amxx-android")

	// 1) register_cvar + get_pcvar_num on self-registered cvars
	pc_a = register_cvar("tst_a", "11")
	pc_b = register_cvar("tst_b", "22")
	pc_c = register_cvar("tst_c", "33")
	server_print("[CVAR] 1 handles a=%d b=%d c=%d (0=FAIL)", pc_a, pc_b, pc_c)
	server_print("[CVAR] 1 values  a=%d b=%d c=%d (want 11/22/33)",
			get_pcvar_num(pc_a), get_pcvar_num(pc_b), get_pcvar_num(pc_c))

	// 2) duplicate register_cvar of the same name (two-plugin scenario)
	pc_dup = register_cvar("tst_a", "99")
	server_print("[CVAR] 2 dup handle=%d val=%d (0=FAIL, val want 11)", pc_dup, get_pcvar_num(pc_dup))

	// 3) compat pre-registered cvars (cvar_compat.amxx loads first)
	pc_quota    = get_cvar_pointer("bot_quota")
	pc_showact  = get_cvar_pointer("amx_show_activity")
	server_print("[CVAR] 3 compat ptrs: bot_quota=%d amx_show_activity=%d (0=COMPAT BROKEN)",
			pc_quota, pc_showact)
	if (pc_quota)
		server_print("[CVAR] 3 bot_quota val=%d amx_show_activity val=%d",
				get_pcvar_num(pc_quota), get_pcvar_num(pc_showact))

	// 4) engine cvar lookup
	pc_timelimit = get_cvar_pointer("mp_timelimit")
	server_print("[CVAR] 4 mp_timelimit ptr=%d (0=FAIL engine lookup)", pc_timelimit)
	if (pc_timelimit)
		server_print("[CVAR] 4 mp_timelimit val=%d", get_pcvar_num(pc_timelimit))

	// 5) set/get pcvar roundtrips
	new old = get_pcvar_num(pc_a)
	set_pcvar_num(pc_a, 77)
	server_print("[CVAR] 5 set_pcvar_num: a=%d (want 77)", get_pcvar_num(pc_a))
	set_pcvar_num(pc_a, old)

	set_pcvar_float(pc_b, 2.5)
	server_print("[CVAR] 5 float: %.2f (want 2.50)", get_pcvar_float(pc_b))
	set_pcvar_num(pc_b, 22)

	set_pcvar_string(pc_c, "hello")
	new sbuf[32]
	get_pcvar_string(pc_c, sbuf, charsmax(sbuf))
	server_print("[CVAR] 5 string: '%s' (want hello)", sbuf)
	set_pcvar_string(pc_c, "33")

	// 6) name-based legacy API
	set_cvar_num("tst_a", 88)
	server_print("[CVAR] 6 set_cvar_num/get_cvar_num: %d (want 88)", get_cvar_num("tst_a"))
	set_cvar_num("tst_a", 11)
	new sbuf2[16]
	get_cvar_string("tst_a", sbuf2, charsmax(sbuf2))
	server_print("[CVAR] 6 get_cvar_string: '%s' (want 11)", sbuf2)
	server_print("[CVAR] 6 cvar_exists tst_a=%d tst_nope=%d (want 1/0)",
			cvar_exists("tst_a"), cvar_exists("tst_nope"))

	// 7) hook_cvar_change fires on set_pcvar_num
	pc_hooked = register_cvar("tst_hook", "5")
	new cb = hook_cvar_change(pc_hooked, "on_hook_cvar")
	server_print("[CVAR] 7 hook handle=%d (0=FAIL)", cb)
	set_pcvar_num(pc_hooked, 42)
	server_print("[CVAR] 7 hook fired=%d newval=%d (want 1/42)", g_hookHit, get_pcvar_num(pc_hooked))

	// 8) error path: get_pcvar_num(0) must abort THIS task function only
	set_task(1.5, "test_null_handle")
	// 9) delayed re-check after every stock plugin finished loading
	set_task(2.5, "late_check")
}

public on_hook_cvar(handle, oldval[], newval[])
{
	g_hookHit = 1
	server_print("[CVAR] 7 callback: old=%s new=%s", oldval, newval)
}

public test_null_handle()
{
	server_print("[CVAR] 8 BEFORE null get_pcvar_num")
	new v = get_pcvar_num(0)
	server_print("[CVAR] 8 AFTER null v=%d (printed = NO error was raised)", v)
}

public late_check()
{
	server_print("[CVAR] 9 late: amx_show_activity=%d val=%d (admin.amxx loaded by now)",
			pc_showact, pc_showact ? get_pcvar_num(pc_showact) : -1)
}
