#include <amxmodx>

// v36 cvar suite -- proves the CORE handles missing cvars GENERICALLY.
// There is no cvar_compat.amxx anymore: the package ships stock AMXX and
// users install any mod they like (ZP 4.3 / ZP 5.0 / BaseBuilder / CSDM /
// user-modified plugins). The core itself now:
//   a) get_cvar_pointer() auto-creates cvars the ported gamedll never
//      registered (bot_quota, amx_show_activity, anything) with default "0";
//   b) when the real owner register_cvar()s it later, the owner's default
//      value is applied over the placeholder;
//   c) every pcvar native degrades a 0 handle to a silent neutral no-op
//      instead of aborting the plugin with "run time error 10".
// This suite exercises all three behaviours WITHOUT any helper plugin.

new pc_a, pc_b, pc_c, pc_dup, pc_quota, pc_showact, pc_timelimit, pc_hooked
new pc_auto2, pc_auto2b
new g_hookHit

public plugin_init()
{
        register_plugin("CVAR Suite", "2.0", "cs16-amxx-android")

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

        // 3) CORE AUTO-CREATE: nothing pre-registers bot_quota any more; the
        //    ported gamedll lacks it. The core must materialize it (value 0)
        //    and hand out a valid handle.
        pc_quota = get_cvar_pointer("bot_quota")
        server_print("[CVAR] 3 auto bot_quota ptr=%d (0=FAIL core auto-create)", pc_quota)
        server_print("[CVAR] 3 auto bot_quota val=%d (want 0)", get_pcvar_num(pc_quota))

        // same cvar through a second handle: set via one, read via the other
        pc_auto2 = get_cvar_pointer("bot_quota")
        set_pcvar_num(pc_auto2, 5)
        server_print("[CVAR] 3 shared set=5 read=%d (want 5)", get_pcvar_num(pc_quota))
        set_pcvar_num(pc_auto2, 0)

        // a cvar no real mod ever registers must behave identically
        pc_auto2b = get_cvar_pointer("tst_totally_missing_xyz")
        server_print("[CVAR] 3 auto random ptr=%d val=%d (nonzero/0=OK)",
                        pc_auto2b, get_pcvar_num(pc_auto2b))

        // amx_show_activity does NOT exist yet -- admincmd.amxx loads later
        // (suite is first in plugins.ini). Placeholder handle + value 0.
        pc_showact = get_cvar_pointer("amx_show_activity")
        server_print("[CVAR] 3 early amx_show_activity ptr=%d val=%d (nonzero/0=placeholder)",
                        pc_showact, get_pcvar_num(pc_showact))

        // 4) engine cvar lookup (real, engine-owned cvar)
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

        // 6) name-based legacy API + DETECTION SEMANTICS:
        //    cvar_exists() must NOT auto-create -- unknown stays unknown,
        //    but bot_quota exists once get_cvar_pointer() materialized it
        set_cvar_num("tst_a", 88)
        server_print("[CVAR] 6 set_cvar_num/get_cvar_num: %d (want 88)", get_cvar_num("tst_a"))
        set_cvar_num("tst_a", 11)
        new sbuf2[16]
        get_cvar_string("tst_a", sbuf2, charsmax(sbuf2))
        server_print("[CVAR] 6 get_cvar_string: '%s' (want 11)", sbuf2)
        server_print("[CVAR] 6 cvar_exists tst_a=%d tst_nope=%d bot_quota=%d (want 1/0/1)",
                        cvar_exists("tst_a"), cvar_exists("tst_nope"), cvar_exists("bot_quota"))

        // 7) hook_cvar_change fires on set_pcvar_num (informational: the detour
        //    needs the Cvar_DirectSet signature; host gamedata may lack it)
        pc_hooked = register_cvar("tst_hook", "5")
        new cb = hook_cvar_change(pc_hooked, "on_hook_cvar")
        server_print("[CVAR] 7 hook handle=%d (0=no detour on this build, informational)", cb)
        set_pcvar_num(pc_hooked, 42)
        server_print("[CVAR] 7 hook fired=%d newval=%d (info)", g_hookHit, get_pcvar_num(pc_hooked))

        // 8) NULL-HANDLE SEMANTICS: every pcvar native with handle 0 must be a
        //    silent neutral no-op (used to abort with run time error 10)
        set_task(1.5, "test_null_handles")
        // 9) delayed re-check AFTER every stock plugin finished loading:
        //    the owner of amx_show_activity must have applied its default
        set_task(2.5, "late_check")
}

public on_hook_cvar(handle, oldval[], newval[])
{
        g_hookHit = 1
        server_print("[CVAR] 7 callback: old=%s new=%s", oldval, newval)
}

public test_null_handles()
{
        new fbuf[16], bindv
        new Float:boundv
        new r_num  = get_pcvar_num(0)
        new Float:r_flt = get_pcvar_float(0)
        new r_str  = get_pcvar_string(0, fbuf, charsmax(fbuf))
        new r_sn   = set_pcvar_num(0, 5)
        new r_sf   = set_pcvar_float(0, 1.5)
        new r_ss   = set_pcvar_string(0, "x")
        new r_gf   = get_pcvar_flags(0)
        new r_sfl  = set_pcvar_flags(0, 0)
        new r_gb   = get_pcvar_bounds(0, CvarBound_Lower, boundv)
        new r_sb   = set_pcvar_bounds(0, CvarBound_Lower, false)
        new r_bn   = bind_pcvar_num(0, bindv)
        new r_hc   = _:hook_cvar_change(0, "on_hook_cvar")
        new r_eh   = enable_cvar_hook(cvarhook:0)
        new r_dh   = disable_cvar_hook(cvarhook:0)

        // printing anything here at all means NO runtime error aborted the task
        server_print("[CVAR] 8 null handles ret: num=%d flt=%.1f str=%d set=%d/%d/%d flags=%d/%d bounds=%d/%d bind=%d hook=%d/%d/%d",
                        r_num, r_flt, r_str, r_sn, r_sf, r_ss, r_gf, r_sfl, r_gb, r_sb, r_bn, r_hc, r_eh, r_dh)
        server_print("[CVAR] 8 null str buf='%s' (want empty)", fbuf)

        if (r_num == 0 && r_sn == 0 && r_bn == 0 && r_hc == 0 && r_eh == 0 && r_dh == 0)
                server_print("[CVAR] 8 null-handle semantics OK")
        else
                server_print("[CVAR] 8 null-handle semantics FAIL")
}

public late_check()
{
        // admincmd.amxx did register_cvar("amx_show_activity", "2") long after
        // our placeholder existed: the owner default must have been applied
        server_print("[CVAR] 9 late amx_show_activity val=%d (want 2: owner default applied)",
                        pc_showact ? get_pcvar_num(pc_showact) : -1)
        new sbuf[16]
        get_cvar_string("amx_show_activity", sbuf, charsmax(sbuf))
        server_print("[CVAR] 9 late by-name '%s' (want 2)", sbuf)
}
