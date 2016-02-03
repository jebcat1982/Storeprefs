package com.stirante.storeprefs;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.stirante.storeprefs.utils.SimpleDatabase;

import java.io.File;
import java.util.HashMap;

import dalvik.system.PathClassLoader;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Created by stirante
 */
public class StorePrefsModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private final String WARN_COMPATIBILITY = "warnCompatibility";
    private final HashMap<String, Object> apks = new HashMap<>();
    private boolean initialized = false;
    private XSharedPreferences prefs;
    private HashMap<String, Integer> dontUpdate;

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
        XSharedPreferences enabled_modules = new XSharedPreferences("de.robv.android.xposed.installer", "enabled_modules");
        for (String s : enabled_modules.getAll().keySet()) {
            if (enabled_modules.getInt(s, 0) == 1) {
                apks.put(s, null);
            }
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam packageParam) throws Throwable {
        if (packageParam.packageName.startsWith("com.android.vending")) {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.args[0];
                    if (!initialized)
                        loadModules(context);
                    hookMethods(packageParam, context);
                }
            });
        }
    }

    private void loadModules(Context ctx) {
        for (String s : apks.keySet()) {
            try {
                ApplicationInfo app = ctx.getPackageManager().getApplicationInfo(s, PackageManager.GET_META_DATA);
                if (app.metaData.containsKey("storeprefs_mainclass")) {
                    PathClassLoader pathClassLoader = new dalvik.system.PathClassLoader(new File(app.publicSourceDir).getAbsolutePath(), ClassLoader.getSystemClassLoader());
                    try {
                        Class<?> clz = Class.forName(app.metaData.getString("storeprefs_mainclass"), true, pathClassLoader);
                        final Object listener = clz.newInstance();
                        debug("Loaded class " + clz.toString());
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                XposedHelpers.callMethod(listener, "init");
                            }
                        }).start();
                        apks.put(s, listener);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }
        initialized = true;
    }

    private void hookMethods(final XC_LoadPackage.LoadPackageParam packageParam, final Context ctx) throws Throwable {
        SimpleDatabase.load();
        prefs = new XSharedPreferences("com.stirante.storeprefs");
        dontUpdate = (HashMap<String, Integer>) SimpleDatabase.get("dontUpdate", new HashMap<String, Integer>());
        Class<?> adapterClass = XposedHelpers.findClass("com.google.android.finsky.activities.myapps.MyAppsInstalledAdapter", packageParam.classLoader);
        Class<?> docClass = XposedHelpers.findClass("com.google.android.finsky.api.model.Document", packageParam.classLoader);
        Class<?> installPoliciesClass = XposedHelpers.findClass("com.google.android.finsky.installer.InstallPolicies", packageParam.classLoader);
        XposedHelpers.findAndHookMethod(adapterClass, "access$300$46a91253", adapterClass, docClass, View.class, ViewGroup.class, XposedHelpers.findClass("com.google.android.finsky.layout.play.PlayStoreUiElementNode", packageParam.classLoader), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                View inflate = (View) param.getResult();
                final Object adapter = param.args[0];
                Object doc = param.args[1];
                if ((boolean) XposedHelpers.callMethod(doc, "hasDetails")) {
                    Object appDetails = XposedHelpers.callMethod(doc, "getAppDetails");
                    final String packageName = (String) XposedHelpers.getObjectField(appDetails, "packageName");
                    final int versionCode = (int) XposedHelpers.getObjectField(appDetails, "versionCode");
                    Object packageState = XposedHelpers.callMethod(XposedHelpers.getObjectField(XposedHelpers.getObjectField(adapter, "mAppStates"), "mPackageManager"), "get", packageName);
                    int localVersion = XposedHelpers.getIntField(packageState, "installedVersion");
                    if (localVersion < versionCode && (!dontUpdate.containsKey(packageName) || dontUpdate.get(packageName) < versionCode)) {
                        inflate.setOnLongClickListener(new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                debug("Ignoring update for " + packageName + " since " + versionCode);
                                dontUpdate.put(packageName, versionCode);
                                SimpleDatabase.saveAsync();
                                XposedHelpers.callMethod(adapter, "notifyDataSetInvalidated");
                                Toast.makeText(ctx, "Adding to ignored updates", Toast.LENGTH_LONG).show();
                                return true;
                            }
                        });
                    }
                }
            }
        });
        XposedHelpers.findAndHookMethod(installPoliciesClass, "canUpdateApp", XposedHelpers.findClass("com.google.android.finsky.appstate.PackageStateRepository$PackageState", packageParam.classLoader), docClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if ((boolean) param.getResult()) {
                    Object doc = param.args[1];
                    if ((boolean) XposedHelpers.callMethod(doc, "hasDetails")) {
                        Object appDetails = XposedHelpers.callMethod(doc, "getAppDetails");
                        String packageName = (String) XposedHelpers.getObjectField(appDetails, "packageName");
                        int versionCode = (int) XposedHelpers.getObjectField(appDetails, "versionCode");
                        if (dontUpdate.containsKey(packageName) && dontUpdate.get(packageName) >= versionCode) {
                            param.setResult(false);
                        }
                    }
                }
            }
        });
        //warn user
        if (prefs.getBoolean("enable_warning", false)) {
            XposedHelpers.findAndHookMethod("com.google.android.finsky.billing.lightpurchase.LightPurchaseFlowActivity", packageParam.classLoader, "acquire", Bundle.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object warnCompatibility = XposedHelpers.getAdditionalInstanceField(param.thisObject, WARN_COMPATIBILITY);
                    if (warnCompatibility != null && !((boolean) warnCompatibility)) return;
                    Object doc = XposedHelpers.getObjectField(param.thisObject, "mDoc");
                    if ((boolean) XposedHelpers.callMethod(doc, "hasDetails")) {
                        Object appDetails = XposedHelpers.callMethod(doc, "getAppDetails");
                        String packageName = (String) XposedHelpers.getObjectField(appDetails, "packageName");
                        String versionString = (String) XposedHelpers.getObjectField(appDetails, "versionString");
                        int versionCode = (int) XposedHelpers.getObjectField(appDetails, "versionCode");
                        if (!shouldUserUpdate(packageName, versionCode, versionString)) {
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, WARN_COMPATIBILITY, true);
                            Object dialog = XposedHelpers.newInstance(XposedHelpers.findClass("com.google.android.finsky.billing.DownloadNetworkDialogFragment", packageParam.classLoader));
                            Bundle arguments = new Bundle();
                            arguments.putBoolean(WARN_COMPATIBILITY, true);
                            XposedHelpers.callMethod(dialog, "setArguments", arguments);
                            XposedHelpers.callMethod(dialog, "show", XposedHelpers.callMethod(param.thisObject, "getSupportFragmentManager"), "LightPurchaseFlowActivity.errorDialog");
                            param.setResult(null);
                        }
                    }
                }
            });
            //change dialog appearance
            XposedHelpers.findAndHookMethod("com.google.android.finsky.billing.DownloadNetworkDialogFragment", packageParam.classLoader, "onCreateDialog", Bundle.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    Bundle arguments = (Bundle) XposedHelpers.getObjectField(param.thisObject, "mArguments");
                    if (arguments.getBoolean(WARN_COMPATIBILITY, false)) {
                        Context context = (Context) XposedHelpers.callMethod(param.thisObject, "getActivity");
                        Object builder = XposedHelpers.newInstance(XposedHelpers.findClass("com.google.android.wallet.ui.common.AlertDialogBuilderCompat", packageParam.classLoader), new Class[]{Context.class, byte.class}, context, (byte) 0);
                        Context myContext = context.createPackageContext("com.stirante.storeprefs", Context.CONTEXT_IGNORE_SECURITY);
                        XposedHelpers.callMethod(builder, "setTitle", new Class[]{CharSequence.class}, myContext.getResources().getString(R.string.title));
                        View content = LayoutInflater.from(myContext).inflate(R.layout.warning_install, null);
                        XposedHelpers.callMethod(builder, "setView", content);
                        XposedHelpers.callMethod(builder, "setPositiveButton", new Class[]{CharSequence.class, DialogInterface.OnClickListener.class}, myContext.getResources().getString(R.string.update), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Object activity = XposedHelpers.callMethod(param.thisObject, "getListener");
                                XposedHelpers.setAdditionalInstanceField(activity, WARN_COMPATIBILITY, false);
                                XposedHelpers.callMethod(activity, "onDownloadOk", false, false);
                            }
                        });
                        XposedHelpers.callMethod(builder, "setNegativeButton", new Class[]{CharSequence.class, DialogInterface.OnClickListener.class}, myContext.getResources().getString(R.string.cancel), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                XposedHelpers.callMethod(XposedHelpers.callMethod(param.thisObject, "getListener"), "onDownloadCancel");
                            }
                        });
                        param.setResult(XposedHelpers.callMethod(builder, "create"));
                    }
                }
            });
        }
        //disable auto update
        XposedHelpers.findAndHookMethod(installPoliciesClass, "getUpdateWarningsForDocument", docClass, boolean.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object warnings = param.getResult();
                Object doc = param.args[0];
                if ((boolean) XposedHelpers.callMethod(doc, "hasDetails")) {
                    Object appDetails = XposedHelpers.callMethod(doc, "getAppDetails");
                    String packageName = (String) XposedHelpers.getObjectField(appDetails, "packageName");
                    int versionCode = (int) XposedHelpers.getObjectField(appDetails, "versionCode");
                    if (!canAutoUpdate(packageName, versionCode))
                        XposedHelpers.setBooleanField(warnings, "autoUpdateDisabled", true);
                }
            }
        });
        if (prefs.getBoolean("disable_rapid", false)) {
            //disable rapid update
            XposedHelpers.findAndHookMethod("com.google.android.finsky.autoupdate.RapidAutoUpdatePolicy", packageParam.classLoader, "apply", XposedHelpers.findClass("com.google.android.finsky.autoupdate.AutoUpdateEntry", packageParam.classLoader), XC_MethodReplacement.DO_NOTHING);
        }
    }

    private boolean shouldUserUpdate(String packageName, int versionCode, String versionString) {
        debug("User wants to update " + packageName + " version: " + versionString + " (code: " + versionCode + ")");
        for (Object listener : apks.values()) {
            if (listener == null) continue;
            try {
                if (!((boolean) XposedHelpers.callMethod(listener, "shouldUserUpdate", packageName, versionCode, versionString)))
                    return false;
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        return true;
    }

    private boolean canAutoUpdate(String packageName, int versionCode) {
        debug("Store tries to auto update " + packageName + " version code: " + versionCode);
        for (Object listener : apks.values()) {
            if (listener == null) continue;
            try {
                if (!((boolean) XposedHelpers.callMethod(listener, "canAutoUpdate", packageName, versionCode)))
                    return false;
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        return true;
    }

    private void debug(String string) {
        XposedBridge.log("[Storeprefs] " + string);
    }

}
