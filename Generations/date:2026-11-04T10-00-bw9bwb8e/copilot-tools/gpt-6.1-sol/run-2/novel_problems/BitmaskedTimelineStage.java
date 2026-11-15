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

        // This array belongs to the snapshot, never to the stage's queue.
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

    private final Object monitor = new Object();
    private final Timeline timeline;

    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision;

    private Node head;
    private Node tail;
    private boolean dispatchActive;

    public BitmaskedTimelineStage(Timeline timeline) {
        if (timeline == null) {
            throw new NullPointerException("timeline");
        }
        this.timeline = timeline;
    }

    public void success(String message) {
        replaceOutcome(SUCCESS, "SUCCESS", message);
    }

    public void warning(String message) {
        replaceOutcome(WARNING, "WARNING", message);
    }

    public void failure(String message) {
        replaceOutcome(FAILURE, "FAILURE", message);
    }

    public void skip(String message) {
        replaceOutcome(SKIPPED, "SKIPPED", message);
    }

    private void replaceOutcome(int outcome, String label, String message) {
        if (message == null) {
            throw new NullPointerException("message");
        }

        synchronized (monitor) {
            long nextRevision = nextRevision();
            this.mask = (this.mask & LOGGED) | outcome;
            this.message = message;
            this.label = label;
            this.revision = nextRevision;
        }
    }

    // Called only while holding monitor.
    private long nextRevision() {
        if (revision == 9223372036854775807L) {
            throw new IllegalStateException("Revision overflow");
        }
        return revision + 1L;
    }

    // The linked queue has no configured capacity and never waits for space.
    public void log() {
        synchronized (monitor) {
            long nextRevision = nextRevision();
            int nextMask = mask | LOGGED;
            Event event = new Event(nextMask, message, label, nextRevision);
            Node node = new Node(event);

            // Allocate everything before mutating state or the queue.
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            mask = nextMask;
            revision = nextRevision;
        }
    }

    // Empty queues and concurrent or reentrant dispatches return immediately.
    public boolean dispatchNext() {
        final Event event;

        synchronized (monitor) {
            if (dispatchActive || head == null) {
                return false;
            }

            event = head.event;
            head = head.next;
            if (head == null) {
                tail = null;
            }
            dispatchActive = true;
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
            int count = 0;
            for (Node node = head; node != null; node = node.next) {
                if (count == 2147483647) {
                    throw new IllegalStateException(
                            "Pending events exceed Java array capacity");
                }
                count++;
            }

            Event[] pendingEvents = new Event[count];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                Event event = node.event;
                pendingEvents[index++] = new Event(
                        event.mask,
                        event.message,
                        event.label,
                        event.revision);
            }

            return new Snapshot(
                    mask,
                    message,
                    label,
                    revision,
                    pendingEvents,
                    dispatchActive);
        }
    }
}
