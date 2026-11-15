@BitmaskedTimelineStage.ThreadSafe
public final class BitmaskedTimelineStage {
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
        public final int mask;
        public final String message;
        public final String label;
        public final long revision;

        private Event(int mask, String message, String label, long revision) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
        }
    }

    public static final class Snapshot {
        public final int mask;
        public final String message;
        public final String label;
        public final long revision;
        public final Event[] pendingEvents;
        public final boolean dispatchActive;

        private Snapshot(
                int mask,
                String message,
                String label,
                long revision,
                Event[] pendingEvents,
                boolean dispatchActive) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
            this.pendingEvents = pendingEvents;
            this.dispatchActive = dispatchActive;
        }
    }

    private static final class Node {
        final Event event;
        Node next;

        Node(Event event) {
            this.event = event;
        }
    }

    private final Timeline timeline;
    private final Object monitor = new Object();

    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision = 0;

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
        setOutcome(SUCCESS, message, "SUCCESS");
    }

    public void warning(String message) {
        setOutcome(WARNING, message, "WARNING");
    }

    public void failure(String message) {
        setOutcome(FAILURE, message, "FAILURE");
    }

    public void skip(String message) {
        setOutcome(SKIPPED, message, "SKIPPED");
    }

    private void setOutcome(int outcome, String newMessage, String newLabel) {
        if (newMessage == null) {
            throw new NullPointerException("message");
        }
        synchronized (monitor) {
            checkRevision();
            mask = (mask & LOGGED) | outcome;
            message = newMessage;
            label = newLabel;
            revision++;
        }
    }

    public void log() {
        synchronized (monitor) {
            checkRevision();
            int newMask = mask | LOGGED;
            long newRevision = revision + 1;
            Event event = new Event(newMask, message, label, newRevision);
            Node node = new Node(event);

            mask = newMask;
            revision = newRevision;
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            pendingCount++;
        }
    }

    public boolean dispatchNext() {
        Event event;
        synchronized (monitor) {
            if (dispatchActive || head == null) {
                return false;
            }
            dispatchActive = true;
            event = head.event;
            head = head.next;
            if (head == null) {
                tail = null;
            }
            pendingCount--;
        }

        try {
            timeline.report(event);
            return true;
        } finally {
            synchronized (monitor) {
                dispatchActive = false;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            Event[] events = new Event[pendingCount];
            Node node = head;
            for (int i = 0; i < events.length; i++) {
                Event event = node.event;
                events[i] = new Event(
                        event.mask, event.message, event.label, event.revision);
                node = node.next;
            }
            return new Snapshot(
                    mask, message, label, revision, events, dispatchActive);
        }
    }

    private void checkRevision() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("revision overflow");
        }
    }
}
