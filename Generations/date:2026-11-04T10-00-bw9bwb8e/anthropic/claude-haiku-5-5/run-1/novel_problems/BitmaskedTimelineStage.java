@BitmaskedTimelineStage.ThreadSafe
public final class BitmaskedTimelineStage {
    @interface ThreadSafe {}


    public static final int INITIAL = 2;
    public static final int SUCCESS = 4;
    public static final int WARNING = 8;
    public static final int FAILURE = 16;
    public static final int SKIPPED = 32;
    public static final int LOGGED = 64;

    private static final int OUTCOME_MASK = SUCCESS | WARNING | FAILURE | SKIPPED | INITIAL;
    private static final int INITIAL_QUEUE_CAPACITY = 8;

    /** Injected consumer of logged events. */
    public interface Timeline {
        void report(Event event);
    }

    /** Immutable, detached event. */
    public static final class Event {
        private final int mask;
        private final String message;
        private final String label;
        private final long revision;

        private Event(int mask, String message, String label, long revision) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
        }

        public int getMask() {
            return mask;
        }

        public String getMessage() {
            return message;
        }

        public String getLabel() {
            return label;
        }

        public long getRevision() {
            return revision;
        }

        @Override
        public String toString() {
            return "Event[mask=" + mask + ", message=" + message
                    + ", label=" + label + ", revision=" + revision + "]";
        }
    }

    /** Immutable-by-contract snapshot; pending events are copied on access. */
    public static final class Snapshot {
        private final int mask;
        private final String message;
        private final String label;
        private final long revision;
        private final Event[] pending;
        private final boolean dispatchActive;

        private Snapshot(int mask, String message, String label, long revision,
                         Event[] pending, boolean dispatchActive) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
            this.pending = pending;
            this.dispatchActive = dispatchActive;
        }

        public int getMask() {
            return mask;
        }

        public String getMessage() {
            return message;
        }

        public String getLabel() {
            return label;
        }

        public long getRevision() {
            return revision;
        }

        public Event[] getPendingEvents() {
            return pending.clone();
        }

        public int getPendingCount() {
            return pending.length;
        }

        public boolean isDispatchActive() {
            return dispatchActive;
        }

        @Override
        public String toString() {
            return "Snapshot[mask=" + mask + ", message=" + message
                    + ", label=" + label + ", revision=" + revision
                    + ", pending=" + pending.length
                    + ", dispatchActive=" + dispatchActive + "]";
        }
    }

    // All mutable state below is guarded by 'lock'.
    private final Object lock = new Object();
    private final Timeline timeline;

    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision = 0L;

    private Event[] queue = new Event[INITIAL_QUEUE_CAPACITY];
    private int head = 0;
    private int size = 0;

    private boolean dispatchActive = false;

    /**
     * Policy: no operation blocks. Outcome, log and snapshot calls complete
     * immediately (the queue is unbounded). dispatchNext returns false
     * immediately when the queue is empty or another dispatch is active.
     */
    public BitmaskedTimelineStage(Timeline timeline) {
        if (timeline == null) {
            throw new NullPointerException("timeline");
        }
        this.timeline = timeline;
    }

    public void success(String message) {
        applyOutcome(SUCCESS, "SUCCESS", message);
    }

    public void warning(String message) {
        applyOutcome(WARNING, "WARNING", message);
    }

    public void failure(String message) {
        applyOutcome(FAILURE, "FAILURE", message);
    }

    public void skip(String message) {
        applyOutcome(SKIPPED, "SKIPPED", message);
    }

    public void log() {
        synchronized (lock) {
            long next = nextRevisionLocked();
            mask |= LOGGED;
            revision = next;
            enqueueLocked(new Event(mask, message, label, revision));
        }
    }

    /**
     * Claims the oldest queued event if no dispatch is in flight and invokes
     * the timeline outside the monitor. Returns false if nothing can be
     * dispatched right now. Exceptions from the callback propagate after the
     * claim is released; the claimed event is not redelivered.
     */
    public boolean dispatchNext() {
        Event claimed;
        synchronized (lock) {
            if (dispatchActive || size == 0) {
                return false;
            }
            claimed = dequeueLocked();
            dispatchActive = true;
        }
        try {
            timeline.report(claimed);
        } finally {
            synchronized (lock) {
                dispatchActive = false;
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

    private void applyOutcome(int outcomeBit, String outcomeLabel, String newMessage) {
        if (newMessage == null) {
            throw new NullPointerException("message");
        }
        synchronized (lock) {
            long next = nextRevisionLocked();
            mask = outcomeBit | (mask & LOGGED);
            message = newMessage;
            label = outcomeLabel;
            revision = next;
        }
    }

    /** Must be called while holding lock. Throws before any mutation on overflow. */
    private long nextRevisionLocked() {
        if (revision == Long.MAX_VALUE) {
            throw new ArithmeticException("revision overflow");
        }
        return revision + 1L;
    }

    /** Must be called while holding lock. */
    private void enqueueLocked(Event event) {
        if (size == queue.length) {
            Event[] grown = new Event[queue.length * 2];
            for (int i = 0; i < size; i++) {
                grown[i] = queue[(head + i) % queue.length];
            }
            queue = grown;
            head = 0;
        }
        int tail = (head + size) % queue.length;
        queue[tail] = event;
        size++;
    }

    /** Must be called while holding lock and only when size > 0. */
    private Event dequeueLocked() {
        Event event = queue[head];
        queue[head] = null;
        head = (head + 1) % queue.length;
        size--;
        return event;
    }
}
