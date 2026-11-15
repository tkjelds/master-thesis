@BitmaskedTimelineStage.ThreadSafe
public class BitmaskedTimelineStage {
    @interface ThreadSafe {}


    public static final int INITIAL = 2;
    public static final int SUCCESS = 4;
    public static final int WARNING = 8;
    public static final int FAILURE = 16;
    public static final int SKIPPED = 32;
    public static final int LOGGED = 64;

    public interface Timeline {
        void report(Event event);
    }

    public record Event(int mask, String message, String label, long revision) {
    }

    public record Snapshot(int mask, String message, String label, long revision,
                           Event[] pendingEvents, boolean dispatchActive) {
        public Snapshot {
            pendingEvents = pendingEvents == null ? new Event[0] : pendingEvents.clone();
        }

        @Override
        public Event[] pendingEvents() {
            return pendingEvents.clone();
        }
    }

    private final Object lock = new Object();
    private final Timeline timeline;

    // Guarded by lock
    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision = 0L;
    private Event[] queue = new Event[8];
    private int head = 0;
    private int size = 0;
    private boolean dispatchActive = false;

    public BitmaskedTimelineStage(Timeline timeline) {
        if (timeline == null) {
            throw new NullPointerException("timeline");
        }
        this.timeline = timeline;
    }

    public void success(String message) {
        outcome(SUCCESS, "SUCCESS", message);
    }

    public void warning(String message) {
        outcome(WARNING, "WARNING", message);
    }

    public void failure(String message) {
        outcome(FAILURE, "FAILURE", message);
    }

    public void skip(String message) {
        outcome(SKIPPED, "SKIPPED", message);
    }

    private void outcome(int bit, String newLabel, String newMessage) {
        if (newMessage == null) {
            throw new NullPointerException("message");
        }
        synchronized (lock) {
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            mask = (mask & LOGGED) | bit;
            message = newMessage;
            label = newLabel;
            revision++;
        }
    }

    public void log() {
        synchronized (lock) {
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            mask |= LOGGED;
            revision++;
            enqueueLocked(new Event(mask, message, label, revision));
        }
    }

    private void enqueueLocked(Event e) {
        if (size == queue.length) {
            Event[] bigger = new Event[queue.length * 2];
            for (int i = 0; i < size; i++) {
                bigger[i] = queue[(head + i) % queue.length];
            }
            queue = bigger;
            head = 0;
        }
        queue[(head + size) % queue.length] = e;
        size++;
    }

    public boolean dispatchNext() {
        Event event;
        synchronized (lock) {
            if (dispatchActive || size == 0) {
                return false;
            }
            event = queue[head];
            queue[head] = null;
            head = (head + 1) % queue.length;
            size--;
            dispatchActive = true;
        }
        try {
            timeline.report(event);
        } finally {
            synchronized (lock) {
                dispatchActive = false;
                lock.notifyAll();
            }
        }
        return true;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            Event[] pending = new Event[size];
            for (int i = 0; i < size; i++) {
                pending[i] = queue[(head + i) % queue.length];
            }
            return new Snapshot(mask, message, label, revision, pending, dispatchActive);
        }
    }
}
