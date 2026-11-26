package it.smg.hu.manager.steering;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The last few steering wheel and head unit events, newest first, for the
 * statistics overlay: one test drive then shows which callback each press
 * arrived on and what the head unit did around it.
 */
public final class HondaEventLog {

    private final int capacity_;
    private final ArrayDeque<String> events_;

    public HondaEventLog(int capacity) {
        capacity_ = capacity;
        events_ = new ArrayDeque<>(capacity);
    }

    public synchronized void add(String event) {
        if (events_.size() == capacity_) {
            events_.removeLast();
        }
        events_.addFirst(event);
    }

    /** "a | b | c", newest first; empty when nothing happened yet. */
    public synchronized String joined() {
        StringBuilder text = new StringBuilder();
        Iterator<String> it = events_.iterator();
        while (it.hasNext()) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(it.next());
        }
        return text.toString();
    }

    public synchronized void clear() {
        events_.clear();
    }
}
