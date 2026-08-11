package com.adbhide;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

/**
 * AdbHide — GLOBAL adb / developer-mode hider (LSPosed, Java-only).
 *
 * Scope this module to "Android System" (system_server) ONLY. It hooks the
 * SettingsProvider inside system_server and, for the dev/adb keys, returns "0" to
 * ANY app caller (uid >= 10000) while leaving the REAL value for system/shell/root
 * (uid < 10000). Net effect:
 *   - Every app that reads adb_enabled / development_settings_enabled sees 0.
 *   - adbd, the Settings app, and the system keep the real value, so USB
 *     debugging / adb / frida-over-adb keep working normally.
 *
 * Because the spoof lives in system_server, NOTHING is injected into the detecting
 * apps — no in-process hooks, no /proc or native footprint in them. A native
 * self-CRC / integrity scan in the target can't see it. (The old ShadowHide did
 * this scoped to specific packages; this is the standalone, global version — all
 * other hiding (frida injection, maps, sockets) is handled elsewhere.)
 */
public class AdbHideHook implements IXposedHookLoadPackage {

    private static final String TAG = "AdbHide";

    // Keys that reveal developer mode / adb — always report 0 to apps.
    private static final String[] DEV_KEYS = {
        "adb_enabled",
        "development_settings_enabled",
        "adb_wifi_enabled"
    };

    // App uids start here; anything below (system=1000, shell=2000, ...) is left
    // untouched so adb/system keep the real value.
    private static final int FIRST_APP_UID = 10000;

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static boolean isDevKey(Object key) {
        if (!(key instanceof String)) return false;
        for (String dk : DEV_KEYS) if (dk.equals(key)) return true;
        return false;
    }

    // Carries the binder calling uid from before -> after the provider call
    // (both run on the same binder thread).
    private static final ThreadLocal<Integer> sCallingUid = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Only act inside system_server ("android"). If you accidentally scope this
        // to an app, do nothing (keeps the module inert/safe).
        if (!"android".equals(lpparam.packageName)) return;

        log("loaded into system_server — installing SettingsProvider adb spoof");
        hookSettingsProvider(lpparam.classLoader);
    }

    private void hookSettingsProvider(ClassLoader cl) {
        // SettingsProvider runs in the system process but its class lives in the
        // provider APK's own child classloader (not system_server's base loader),
        // so it can't be loadClass'd directly. Hook the framework base
        // ContentProvider.attachInfo (which IS in the base loader); when the
        // SettingsProvider instance attaches, hook call() on its real class.
        try {
            Class<?> cp = cl.loadClass("android.content.ContentProvider");
            Method attach = cp.getDeclaredMethod("attachInfo",
                android.content.Context.class,
                android.content.pm.ProviderInfo.class);
            attach.setAccessible(true);
            XposedBridge.hookMethod(attach, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object provider = param.thisObject;
                        if (provider == null) return;
                        if (!"com.android.providers.settings.SettingsProvider"
                                .equals(provider.getClass().getName())) return;
                        hookCallOn(provider.getClass());
                    } catch (Throwable ignored) {}
                }
            });
            log("hooked ContentProvider.attachInfo (awaiting SettingsProvider)");
        } catch (Throwable t) {
            log("attachInfo hook failed: " + t.getMessage());
        }
    }

    private static boolean sCallHooked = false;

    private void hookCallOn(Class<?> provider) {
        if (sCallHooked) return;
        int hooked = 0;
        for (Method m : provider.getDeclaredMethods()) {
            if (!m.getName().equals("call")) continue;
            if (m.getParameterTypes().length < 3) continue; // call(method, arg, extras[, ...])
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            sCallingUid.set(android.os.Binder.getCallingUid());
                        } catch (Throwable ignored) {}
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try { spoof(param); }
                        catch (Throwable ignored) {}
                        finally { sCallingUid.remove(); }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {}
        }
        sCallHooked = hooked > 0;
        log("SettingsProvider.call hooks installed: " + hooked);
    }

    // Rewrite the returned value to "0" when an APP queried a dev/adb key.
    private void spoof(XC_MethodHook.MethodHookParam param) {
        Integer uidObj = sCallingUid.get();
        if (uidObj == null) return;
        int appId = uidObj % 100000;              // strip user id (multi-user)
        if (appId < FIRST_APP_UID) return;        // system/shell/root -> keep real value

        String name = null;
        for (Object a : param.args) {
            if (isDevKey(a)) { name = (String) a; break; }
        }
        if (name == null) return;

        Object result = param.getResult();
        if (result instanceof android.os.Bundle) {
            android.os.Bundle b = (android.os.Bundle) result;
            if (b.containsKey("value")) {
                b.putString("value", "0");
                log("SPOOF " + name + " -> 0 for uid=" + uidObj);
            }
        }
    }
}
