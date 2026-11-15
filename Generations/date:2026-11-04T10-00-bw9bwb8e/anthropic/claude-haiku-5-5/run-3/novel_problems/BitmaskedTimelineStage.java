@BitmaskedTimelineStage.ThreadSafe
public final class BitmaskedTimelineStage {
    @interface ThreadSafe {}


    public static final int INITIAL = 2;
    public static final int SUCCESS = 4;
    public static final int WARNING = 8;
    public static final int FAILURE = 16;
    public static final int SKIPPED = 32;
    public static final int LOGGED = 64;

    private static final int OUTCOME_MASK = INITIAL | SUCCESS | WARNING | FAILURE | SKIPPED;

    public interface Timeline {
        void report(Event event);
    }

    public static final class Event {
        private final int mask;
        private final String message;
        private final String label;
        private final long revision;

        Event(int mask, String message, String label, long revision) {
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
    }

    public static final class Snapshot {
        private final int mask;
        private final String message;
        private final String label;
        private final long revision;
        private final Event[] pendingEvents;
        private final boolean dispatchActive;

        Snapshot(int mask, String message, String label, long revision,
                 Event[] pendingEvents, boolean dispatchActive) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
            this.pendingEvents = pendingEvents;
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
            return pendingEvents.clone();
        }

        public int getPendingCount() {
            return pendingEvents.length;
        }

        public boolean isDispatchActive() {
            return dispatchActive;
        }
    }

    private static final class Node {
        final Event event;
        Node next;

        Node(Event event) {
            this.event = event;
        }
    }

    private final Object lock = new Object();
    private final Timeline timeline;

    // All fields below are guarded by lock.
    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision = 0L;
    private Node head;
    private Node tail;
    private int pendingCount;
    private boolean dispatchActive;

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
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            mask |= LOGGED;
            revision++;
            enqueue(new Event(mask, message, label, revision));
        }
    }

    /**
     * Claims and dispatches the oldest queued event. Never blocks: returns false
     * if the queue is empty or another dispatch (possibly reentrant) is active.
     */
    public boolean dispatchNext() {
        Event claimed;
        synchronized (lock) {
            if (dispatchActive || head == null) {
                return false;
            }
            claimed = dequeue();
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
            Event[] pending = new Event[pendingCount];
            Node n = head;
            int i = 0;
            while (n != null) {
                pending[i++] = n.event;
                n = n.next;
            }
            return new Snapshot(mask, message, label, revision, pending, dispatchActive);
        }
    }

    private void applyOutcome(int bit, String newLabel, String newMessage) {
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

    // Must be called while holding lock.
    private void enqueue(Event event) {
        Node node = new Node(event);
        if (tail == null) {
            head = node;
            tail = node;
        } else {
            tail.next = node;
            tail = node;
        }
        pendingCount++;
    }

    // Must be called while holding lock and with head != null.
    private Event dequeue() {
        Node node = head;
        head = node.next;
        if (head == null) {
            tail = null;
        }
        node.next = null;
        pendingCount--;
        return node.event;
    }
}
