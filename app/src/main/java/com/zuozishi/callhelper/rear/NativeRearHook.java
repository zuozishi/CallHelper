package com.zuozishi.callhelper.rear;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Version-scoped native RemoteViews adapter, based on the user's RearHaPoc. */
public final class NativeRearHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.xiaomi.subscreencenter";
    private static final String MODULE = "com.zuozishi.callhelper";
    private static final String VERSION = "RELEASE-1.0.2609141923";
    private static final Uri URI = Uri.parse("content://com.zuozishi.callhelper.rear");
    private static final int ID = 0x43434C52;
    private Class<?> descriptor;
    private Class<?> factory;
    private Session creatingOwner;
    private java.lang.ref.WeakReference<Session> currentSession = new java.lang.ref.WeakReference<>(null);

    private static void log(String text) {
        Log.i("CallHelperRear", text);
        XposedBridge.log("CallHelperRear " + text);
    }

    private final class Session extends ContentObserver implements View.OnAttachStateChangeListener {
        final View panel;
        final Context context;
        final Handler handler = new Handler(Looper.getMainLooper());
        Bundle data = Bundle.EMPTY;
        Object card;
        boolean registered;
        boolean attached;
        boolean updating;
        String shownToken;
        final Runnable expire = () -> refresh();

        Session(View panel) {
            super(new Handler(Looper.getMainLooper()));
            this.panel = panel;
            this.context = panel.getContext();
            panel.addOnAttachStateChangeListener(this);
            if (panel.isAttachedToWindow()) onViewAttachedToWindow(panel);
        }

        boolean active() {
            return data.getBoolean("active") && data.getLong("expires") > SystemClock.elapsedRealtime();
        }

        Bundle extra() {
            Bundle b = new Bundle();
            b.putString("resName", "来电名片");
            b.putString("package_name", MODULE);
            b.putParcelable("miui.rear.rv", data.getParcelable("views"));
            b.putParcelable("miui.rear.rvAOD", data.getParcelable("aod"));
            b.putBoolean("force_popup", false);
            b.putBoolean("show_time_tip", false);
            return b;
        }

        void read() {
            Bundle result = context.getContentResolver().call(URI, "views", null, null);
            data = result == null ? Bundle.EMPTY : result;
            handler.removeCallbacks(expire);
            if (active()) handler.postDelayed(expire, Math.max(1, data.getLong("expires") - SystemClock.elapsedRealtime()));
        }

        Object ensureCard() {
            if (card == null) {
                Object spec = XposedHelpers.newInstance(descriptor, ID, 3, 1, extra(), false, null, null,
                    null, null, null, true, false, -1);
                creatingOwner = this;
                try { card = XposedHelpers.callStaticMethod(factory, "g", spec); }
                finally { creatingOwner = null; }
                if (card == null) throw new IllegalStateException("Native factory returned null");
                log("CARD_CREATED type=" + card.getClass().getName());
                XposedHelpers.setAdditionalInstanceField(card, "callhelper.owner", this);
            } else {
                Bundle b = extra();
                XposedHelpers.setObjectField(card, "i", b);
                XposedHelpers.callMethod(card, "u", b);
            }
            return card;
        }

        void report(String event) {
            Bundle state = new Bundle();
            state.putString("token", data.getString("token"));
            state.putBoolean("active", active());
            state.putBoolean("attached", active() && attached);
            try { context.getContentResolver().call(URI, "report", event, state); }
            catch (Throwable ignored) { log("REPORT_FAILED"); }
            log(event + " active=" + active() + " attached=" + attached);
        }

        @Override public void onChange(boolean selfChange) { refresh(); }

        void refresh() {
            if (updating || !panel.isAttachedToWindow()) return;
            updating = true;
            try {
                try { read(); } catch (Throwable failure) { data = Bundle.EMPTY; log("READ_FAILED " + failure.getClass().getSimpleName()); }
                List<?> current = (List<?>) XposedHelpers.callMethod(panel, "getAllWidgets");
                ArrayList<Object> others = new ArrayList<>();
                boolean hadCard = false;
                if (current != null) for (Object item : current) {
                    if (XposedHelpers.getIntField(item, "d") == ID) hadCard = true;
                    else others.add(item);
                }
                if (active()) {
                    String token = data.getString("token");
                    boolean show = token != null && !token.equals(shownToken) && SystemClock.elapsedRealtime() - data.getLong("show") < 5000;
                    if (!hadCard) XposedHelpers.callMethod(panel, "e", others, others.size());
                    else ensureCard();
                    if (show) {
                        shownToken = token;
                        if (hadCard) XposedHelpers.callMethod(panel, "e", others, others.size());
                        Object power = context.getSystemService(Context.POWER_SERVICE);
                        int displayId = panel.getDisplay() == null ? 1 : panel.getDisplay().getDisplayId();
                        XposedHelpers.callMethod(power, "wakeUp", SystemClock.uptimeMillis(), 2, "CallHelper:card", displayId);
                        XposedHelpers.callMethod(panel, "setAssistantVisibleImpl", true);
                        log("SHOW_REQUESTED native assistant display=" + displayId);
                    }
                } else {
                    if (hadCard) XposedHelpers.callMethod(panel, "e", others, others.isEmpty() ? -1 : 0);
                    card = null; attached = false;
                }
                report("UPDATED");
            } catch (Throwable failure) {
                log("UPDATE_FAILED " + Log.getStackTraceString(failure));
                report("ERROR");
            } finally { updating = false; }
        }

        @Override public void onViewAttachedToWindow(View v) {
            if (!registered) { context.getContentResolver().registerContentObserver(URI, false, this); registered = true; }
            report("READY");
            handler.post(this::refresh);
        }
        @Override public void onViewDetachedFromWindow(View v) {
            if (registered) { context.getContentResolver().unregisterContentObserver(this); registered = false; }
            handler.removeCallbacksAndMessages(null);
            attached = false;
        }
    }

    private Session session(View panel) {
        Session s = (Session) XposedHelpers.getAdditionalInstanceField(panel, "callhelper.session");
        if (s == null) {
            try {
                if (!VERSION.equals(panel.getContext().getPackageManager().getPackageInfo(TARGET, 0).versionName)) return null;
            } catch (Throwable ignored) { return null; }
            s = new Session(panel);
            XposedHelpers.setAdditionalInstanceField(panel, "callhelper.session", s);
        }
        currentSession = new java.lang.ref.WeakReference<>(s);
        return s;
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!TARGET.equals(p.packageName) || !TARGET.equals(p.processName)) return;
        try {
            Class<?> panelClass = XposedHelpers.findClass(TARGET + ".SmartAssistantPanel", p.classLoader);
            descriptor = XposedHelpers.findClass("x2.a", p.classLoader);
            factory = XposedHelpers.findClass("E2.w", p.classLoader);
            Class<?> widget = XposedHelpers.findClass("E2.p", p.classLoader);
            XposedHelpers.findAndHookMethod(panelClass, "e", List.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Session s = session((View) param.thisObject);
                        if (s == null) return;
                        s.read();
                        ArrayList<Object> cards = new ArrayList<>();
                        for (Object item : (List<?>) param.args[0]) if (XposedHelpers.getIntField(item, "d") != ID) cards.add(item);
                        if (s.active()) cards.add(s.ensureCard());
                        param.args[0] = cards;
                        log("LIST_UPDATED count=" + cards.size() + " active=" + s.active());
                        if (cards.isEmpty()) param.args[1] = -1;
                        else if ((Integer) param.args[1] >= cards.size()) param.args[1] = cards.size() - 1;
                    } catch (Throwable failure) { log("INJECT_FAILED " + Log.getStackTraceString(failure)); }
                }
            });
            XposedBridge.hookAllConstructors(panelClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { session((View) param.thisObject); }
            });
            XposedHelpers.findAndHookMethod(widget, "r", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (XposedHelpers.getIntField(param.thisObject, "d") != ID || param.hasThrowable()) return;
                    Session existing = (Session) XposedHelpers.getAdditionalInstanceField(param.thisObject, "callhelper.owner");
                    final Session owner = existing != null ? existing : creatingOwner != null ? creatingOwner : currentSession.get();
                    log("HOST_RETURN owner=" + (owner != null) + " view=" + (param.getResult() instanceof View));
                    if (owner == null || !(param.getResult() instanceof View)) return;
                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "callhelper.owner", owner);
                    owner.card = param.thisObject;
                    View root = (View) param.getResult();
                    root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        @Override public void onViewAttachedToWindow(View v) { owner.attached = true; owner.report("ATTACHED"); }
                        @Override public void onViewDetachedFromWindow(View v) { owner.attached = false; owner.report("DETACHED"); }
                    });
                    log("HOST_CREATED native RemoteViews");
                }
            });
            log("HOOKS_READY");
        } catch (Throwable failure) { log("HOOK_SETUP_FAILED " + Log.getStackTraceString(failure)); }
    }
}
