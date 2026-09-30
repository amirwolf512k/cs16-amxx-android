# Aggregator: builds AMX Mod X core + selected modules for Android.
# Repo version — all paths come from the ndk-build command line:
#   AMXX_SRC / XASH_FS / MM_SRC / HLSDK_SRC / GLUE_DIR
LOCAL_PATH := $(call my-dir)

AMXXROOT := $(AMXX_SRC)

include $(AMXXROOT)/amxmodx/Android.mk
include $(AMXXROOT)/modules/engine/Android.mk
include $(AMXXROOT)/modules/fun/Android.mk
include $(AMXXROOT)/modules/fakemeta/Android.mk
include $(AMXXROOT)/modules/cstrike/cstrike/Android.mk
include $(AMXXROOT)/modules/cstrike/csx/Android.mk
include $(AMXXROOT)/modules/nvault/Android.mk
include $(AMXXROOT)/modules/sockets/Android.mk

$(call import-add-path,$(AMXXROOT))
$(call import-module,third_party)
