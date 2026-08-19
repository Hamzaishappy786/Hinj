package com.hinj.player.playback;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process pub/sub between the NotificationListenerService and the
 * playback service. Both live in the same process, so a lightweight
 * singleton is simpler and more reliable than binding a service to a
 * service for a "duck for 2s" / "already playing" style signal.
 */
public class PlaybackCommandBus {

    public interface Listener {
        void onDuckRequested();
        void onBlinkRequested();
    }

    private static final PlaybackCommandBus INSTANCE = new PlaybackCommandBus();

    public static PlaybackCommandBus getInstance() {
        return INSTANCE;
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public void register(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void unregister(Listener listener) {
        listeners.remove(listener);
    }

    public void requestDuck() {
        for (Listener l : listeners) l.onDuckRequested();
    }

    public void requestBlink() {
        for (Listener l : listeners) l.onBlinkRequested();
    }
}
