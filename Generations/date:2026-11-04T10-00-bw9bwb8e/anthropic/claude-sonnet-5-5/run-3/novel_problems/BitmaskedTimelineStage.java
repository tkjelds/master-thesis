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

    public static final class Event {
        private final int mask;
        private final String message;
        private final String label;
        private final long revision;

        public Event(int mask, String message, String label, long revision) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
        }

        public int mask() { return mask; }
        public String message() { return message; }
        public String label() { return label; }
        public long revision() { return revision; }

        public int getMask() { return mask; }
        public String getMessage() { return message; }
        public String getLabel() { return label; }
        public long getRevision() { return revision; }

        @Override
        public String toString() {
            return "Event[mask=" + mask + ", message=" + message + ", label=" + label
                    + ", revision=" + revision + "]";
        }
    }

    public static final class Snapshot {
        private final int mask;
        private final String message;
        private final String label;
        private final long revision;
        private final Event[] pending;
        private final boolean dispatchActive;

        Snapshot(int mask, String message, String label, long revision,
                 Event[] pending, boolean dispatchActive) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
            this.pending = pending;
            this.dispatchActive = dispatchActive;
        }

        public int mask() { return mask; }
        public String message() { return message; }
        public String label() { return label; }
        public long revision() { return revision; }
        public Event[] pending() { return pending.clone(); }
        public Event[] pendingEvents() { return pending.clone(); }
        public boolean dispatchActive() { return dispatchActive; }

        public int getMask() { return mask; }
        public String getMessage() { return message; }
        public String getLabel() { return label; }
        public long getRevision() { return revision; }
        public Event[] getPending() { return pending.clone(); }
        public Event[] getPendingEvents() { return pending.clone(); }
        public boolean isDispatchActive() { return dispatchActive; }
    }

    private static final class Node {
        final Event event;
        Node next;
        Node(Event event) { this.event = event; }
    }

    private final Object lock = new Object();
    private final Timeline timeline;

    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision = 0L;
    private Node head;
    private Node tail;
    private int size;
    private boolean dispatching;

    public BitmaskedTimelineStage(Timeline timeline) {
        if (timeline == null) {
            throw new NullPointerException("timeline");
        }
        this.timeline = timeline;
    }

    private void outcome(int bit, String lbl, String msg) {
        if (msg == null) {
            throw new NullPointerException("message");
        }
        synchronized (lock) {
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            mask = (mask & LOGGED) | bit;
            message = msg;
            label = lbl;
            revision++;
        }
    }

    public void success(String message) { outcome(SUCCESS, "SUCCESS", message); }

    public void warning(String message) { outcome(WARNING, "WARNING", message); }

    public void failure(String message) { outcome(FAILURE, "FAILURE", message); }

    public void skip(String message) { outcome(SKIPPED, "SKIPPED", message); }

    public void log() {
        synchronized (lock) {
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            mask |= LOGGED;
            revision++;
            Node n = new Node(new Event(mask, message, label, revision));
            if (tail == null) {
                head = n;
                tail = n;
            } else {
                tail.next = n;
                tail = n;
            }
            size++;
        }
    }

    public boolean dispatchNext() {
        Event event;
        synchronized (lock) {
            if (dispatching || head == null) {
                return false;
            }
            Node n = head;
            head = n.next;
            if (head == null) {
                tail = null;
            }
            size--;
            event = n.event;
            dispatching = true;
        }
        try {
            timeline.report(event);
        } finally {
            synchronized (lock) {
                dispatching = false;
                lock.notifyAll();
            }
        }
        return true;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            Event[] copy = new Event[size];
            Node n = head;
            int i = 0;
            while (n != null) {
                Event e = n.event;
                copy[i++] = new Event(e.mask(), e.message(), e.label(), e.revision());
                n = n.next;
            }
            return new Snapshot(mask, message, label, revision, copy, dispatching);
        }
    }
}
