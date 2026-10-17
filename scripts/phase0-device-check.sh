#!/usr/bin/env bash
# Phase 0 device survey for the Xiaomi Pad 7 + Focus Pen.
# Run with the tablet plugged in and USB debugging on. Read-only: nothing is changed.
set -u
say() { printf '\n== %s\n' "$*"; }
adb get-state >/dev/null 2>&1 || { echo "no device: enable USB debugging and run 'adb devices'"; exit 1; }

say "Build"
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release        # expect 16
adb shell getprop ro.build.version.sdk            # expect 36
adb shell getprop ro.mi.os.version.name 2>/dev/null || adb shell getprop ro.miui.ui.version.name

say "Stylus handwriting framework switch (Android 14+; absent on some OEM builds)"
adb shell settings get secure stylus_handwriting_enabled 2>/dev/null || echo "setting not present"

say "Installed keyboards and whether they advertise stylus handwriting"
adb shell ime list -a -s
adb shell dumpsys input_method 2>/dev/null | grep -iE "supportsStylusHandwriting|mStylusHandwriting|handwriting" | head -20 || true

say "Gboard present? (its 'Write in text fields' screen is the cheapest framework test)"
adb shell pm list packages | grep -q com.google.android.inputmethod.latin && echo "Gboard installed" || echo "Gboard not installed"

say "Input devices: look for a stylus/pen device with ABS_PRESSURE"
adb shell getevent -il 2>/dev/null | grep -iE "add device|name:|ABS_PRESSURE|ABS_MT_TOOL_TYPE|BTN_TOOL_PEN" | head -40

say "Stylus-with-touch simulation flag (for testing on devices without a pen; leave 0 on the Pad 7)"
adb shell getprop debug.input.simulate_stylus_with_touch

say "Done. Paste this output into docs/PHASE0.md under 'Device survey results'."
