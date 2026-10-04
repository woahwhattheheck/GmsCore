package com.google.android.gms.cast.framework.internal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.framework.*;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Assertions invoke actual SessionManagerImpl and SessionImpl, never a copied state machine. */
public class LifecycleRegression {
    static final String PREFS = "com.google.android.gms.cast.framework.internal.session";
    static final String CATEGORY = CastContextImpl.CATEGORY;
    static final String DEFAULT = "<default>";
    static int checks, failures;

    static void expect(String description, Object expected, Object actual) {
        checks++;
        if (Objects.equals(expected, actual)) {
            System.out.println("PASS " + description);
        } else {
            failures++;
            System.out.println("FAIL " + description + " expected=" + expected + " actual=" + actual);
        }
    }

    static String record(String route, String session) {
        return route + "|" + session + "|" + CATEGORY;
    }

    static class Proxy implements ISessionProxy {
        final Object clientSession = new Object();
        int endCalls;
        public IObjectWrapper getWrappedSession() { return ObjectWrapper.wrap(clientSession); }
        public void end(boolean stopCasting) { endCalls++; }
    }

    static class Provider implements ISessionProvider {
        SessionImpl last;
        int recoverabilityChecks;
        public IObjectWrapper getSession(String sessionId) {
            last = new SessionImpl(CATEGORY, sessionId, new Proxy());
            return ObjectWrapper.wrap(last);
        }
        public boolean isSessionRecoverable() { recoverabilityChecks++; return true; }
    }

    static class Router implements IMediaRouter {
        String selected = DEFAULT;
        int defaultSelections;
        int routeLookups, selections;
        final Bundle extras = new Bundle();
        Router() { extras.putParcelable("device", new CastDevice()); }
        public boolean isRouteAvailable(Bundle selector, int flags) { return true; }
        public void selectRouteById(String id) { selected = id; selections++; }
        public void selectDefaultRoute() { selected = DEFAULT; defaultSelections++; }
        public Bundle getRouteInfoExtrasById(String id) { routeLookups++; return extras; }
        public String getSelectedRouteId() { return selected; }
    }

    static class Listener implements ISessionManagerListener {
        final List<Object> ended = new ArrayList<>();
        public IObjectWrapper getWrappedThis() { return ObjectWrapper.wrap(this); }
        public void onSessionEnded(IObjectWrapper session, int error) {
            ended.add(ObjectWrapper.unwrap(session));
        }
    }

    static class Fixture {
        final CastContextImpl context = new CastContextImpl();
        final Router router = new Router();
        final Provider provider = new Provider();
        final SessionManagerImpl manager;
        final SharedPreferences preferences;
        Fixture() {
            context.router = router;
            context.defaultSessionProvider = provider;
            manager = new SessionManagerImpl(context);
            context.manager = manager;
            preferences = context.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        SessionImpl select(String route) {
            router.selectRouteById(route);
            manager.onRouteSelected(route, router.extras);
            return provider.last;
        }
        SessionImpl connect(String route, String id) {
            SessionImpl session = select(route);
            session.notifySessionStarted(id);
            return session;
        }
        String saved() {
            return preferences.getString("route_id", null) + "|"
                    + preferences.getString("session_id", null) + "|"
                    + preferences.getString("category", null);
        }
        void seed(String route, String id) {
            preferences.edit().putString("route_id", route).putString("session_id", id)
                    .putString("category", CATEGORY).apply();
        }
    }

    static void connectedReplacement() throws Exception {
        System.out.println("CASE connected A then B; delayed A termination");
        Fixture f = new Fixture();
        Listener listener = new Listener();
        f.manager.addSessionManagerListener(listener);
        SessionImpl a = f.connect("A", "session-A");
        SessionImpl b = f.connect("B", "session-B");
        expect("A end requested on replacement", 1, ((Proxy)a.getSessionProxy()).endCalls);
        expect("B persisted before old callback", record("B", "session-B"), f.saved());
        a.notifySessionEnded(0);
        expect("late A end preserves B current pointer", true, f.manager.getCurrentSession() == b);
        expect("late A end preserves B persisted record", record("B", "session-B"), f.saved());
        expect("late A end preserves B selected route", "B", f.router.selected);
        expect("late A end still emits A listener event", true,
                listener.ended.size() == 1 && listener.ended.get(0) == ((Proxy)a.getSessionProxy()).clientSession);
    }

    static void startingReplacement() {
        System.out.println("CASE B starting; A still owns the persisted record");
        Fixture f = new Fixture();
        SessionImpl a = f.connect("A", "session-A");
        SessionImpl b = f.select("B");
        expect("B is starting before old callback", true, b.isConnecting());
        expect("unsaved B leaves A record before old callback", record("A", "session-A"), f.saved());
        a.notifySessionEnded(0);
        expect("late A end preserves starting B pointer", true, f.manager.getCurrentSession() == b);
        expect("late A end clears A-owned persisted record", "null|null|null", f.saved());
        expect("late A end leaves starting B selected", "B", f.router.selected);
    }

    static void sameRouteAndIdReplacement() {
        System.out.println("CASE A1 -> B -> A2; same A route and application session ID");
        Fixture f = new Fixture();
        SessionImpl a1 = f.connect("A", "reused-session-id");
        f.connect("B", "session-B");
        SessionImpl a2 = f.connect("A", "reused-session-id");
        expect("A1 and A2 are distinct objects", true, a1 != a2);
        a1.notifySessionEnded(0);
        expect("late A1 end preserves A2 pointer", true, f.manager.getCurrentSession() == a2);
        expect("late A1 end preserves A2 same-ID persisted record", record("A", "reused-session-id"), f.saved());
        expect("late A1 end does not unselect A2 route", "A", f.router.selected);
        expect("late A1 end makes no default route selection", 0, f.router.defaultSelections);
    }

    static void queuedCleanup() throws Exception {
        System.out.println("CASE off-main A end; A2 starts before queued route cleanup executes");
        Fixture f = new Fixture();
        SessionImpl a = f.connect("A", "session-A");
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { a.notifySessionEnded(0); }
            catch (Throwable failure) { error.set(failure); }
        }, "host-binder-callback");
        worker.start();
        worker.join(2000);
        if (worker.isAlive()) throw new AssertionError("Worker callback did not finish in 2 seconds");
        if (error.get() != null) throw new AssertionError("Worker callback failed", error.get());
        expect("old cleanup is queued before replacement", true, f.context.queuedActions() > 0);
        expect("off-main active end releases current pointer", null, f.manager.getCurrentSession());
        SessionImpl a2 = f.connect("A", "session-A2");
        f.context.drainMainQueue();
        expect("queued cleanup preserves A2 current pointer", true, f.manager.getCurrentSession() == a2);
        expect("queued cleanup preserves A2 persisted record", record("A", "session-A2"), f.saved());
        expect("queued cleanup does not unselect newer A2", "A", f.router.selected);
        expect("main queue finishes", 0, f.context.queuedActions());
    }

    static void failedSavedResume() {
        System.out.println("CASE persisted resume attempt fails normally");
        Fixture f = new Fixture();
        f.context.options.resumeSavedSession = true;
        f.seed("A", "saved-session");
        f.manager.tryResumeSavedSession();
        expect("saved resume selects its available route", "A", f.router.selected);
        // Deliver the router's selected callback into the actual production manager.
        f.manager.onRouteSelected(f.router.selected, f.router.extras);
        SessionImpl resumed = f.provider.last;
        expect("saved session enters real resuming state", true, resumed.isResuming());
        expect("saved session ID reaches provider", "saved-session", resumed.getSessionId());
        resumed.notifyFailedToResumeSession(17);
        expect("failed active resume clears persisted record", "null|null|null", f.saved());
        expect("failed active resume clears current pointer", null, f.manager.getCurrentSession());
        expect("failed active resume selects default route", DEFAULT, f.router.selected);
    }

    static void disabledSavedResume() {
        System.out.println("CASE saved-session recovery explicitly disabled");
        Fixture f = new Fixture();
        f.context.options.resumeSavedSession = false;
        f.seed("A", "saved-session");
        Context c = f.context.context;
        String initialAccess = c.preferenceLookups + "|" + c.preferenceReads + "|" + c.preferenceWrites;
        f.manager.tryResumeSavedSession();
        f.manager.onRouteAdded("A");
        expect("disabled recovery skips preference lookup/read/write", initialAccess,
                c.preferenceLookups + "|" + c.preferenceReads + "|" + c.preferenceWrites);
        expect("disabled recovery preserves existing saved record", record("A", "saved-session"), f.saved());
        expect("disabled recovery skips provider recoverability query", 0, f.provider.recoverabilityChecks);
        expect("disabled recovery skips route lookup", 0, f.router.routeLookups);
        expect("disabled recovery never selects even on later route-added callback", 0, f.router.selections);
        expect("disabled recovery leaves default route selected", DEFAULT, f.router.selected);
    }

    static void normalActiveEnd() throws Exception {
        System.out.println("CASE normal active session end");
        Fixture f = new Fixture();
        SessionImpl a = f.connect("A", "session-A");
        f.manager.endCurrentSession(false, true);
        expect("normal end reaches ending state", true, a.isDisconnecting());
        a.notifySessionEnded(0);
        expect("normal active end clears persisted record", "null|null|null", f.saved());
        expect("normal active end clears current pointer", null, f.manager.getCurrentSession());
        expect("normal active end selects default route", DEFAULT, f.router.selected);
        expect("normal active end selects default exactly once", 1, f.router.defaultSelections);
    }

    public static void main(String[] args) throws Exception {
        Looper.getMainLooper(); // Establish this host thread as main before any worker starts.
        System.out.println("SCOPE actual SessionManagerImpl.java + SessionImpl.java; handwritten host collaborators; no Android/Binder IPC/receiver runtime");
        connectedReplacement();
        startingReplacement();
        sameRouteAndIdReplacement();
        queuedCleanup();
        failedSavedResume();
        disabledSavedResume();
        normalActiveEnd();
        System.out.println("RESULT checks=" + checks + " passed=" + (checks-failures) + " failed=" + failures);
        if (failures != 0) System.exit(1);
    }
}
