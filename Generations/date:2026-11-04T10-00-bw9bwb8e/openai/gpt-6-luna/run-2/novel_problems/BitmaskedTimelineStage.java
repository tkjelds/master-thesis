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

    public static class Event {
        public int mask;
        public String message;
        public String label;
        public long revision;

        public Event(int mask, String message, String label, long revision) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
        }
    }

    public static class Snapshot {
        public int mask;
        public String message;
        public String label;
        public long revision;
        public Event[] pendingEvents;
        public boolean dispatchActive;

        public Snapshot(int mask, String message, String label, long revision,
                        Event[] pendingEvents, boolean dispatchActive) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
            this.pendingEvents = pendingEvents;
            this.dispatchActive = dispatchActive;
        }
    }

    private static final int OUTCOME_BITS = SUCCESS | WARNING | FAILURE | SKIPPED;

    private static final class QueueNode {
        final Event event;
        QueueNode next;

        QueueNode(Event event) {
            this.event = event;
        }
    }

    private final Object monitor = new Object();
    private final Timeline timeline;

    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision;
    private QueueNode head;
    private QueueNode tail;
    private int pendingCount;
    private boolean dispatchActive;

    public BitmaskedTimelineStage(Timeline timeline) {
        if (timeline == null) {
            throw new NullPointerException("timeline");
        }
        this.timeline = timeline;
    }

    public void success(String message) {
        setOutcome(SUCCESS, "SUCCESS", message);
    }

    public void warning(String message) {
        setOutcome(WARNING, "WARNING", message);
    }

    public void failure(String message) {
        setOutcome(FAILURE, "FAILURE", message);
    }

    public void skip(String message) {
        setOutcome(SKIPPED, "SKIPPED", message);
    }

    private void setOutcome(int outcomeBit, String outcomeLabel, String newMessage) {
        if (newMessage == null) {
            throw new NullPointerException("message");
        }

        synchronized (monitor) {
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }

            mask = (mask & ~OUTCOME_BITS) | outcomeBit;
            message = newMessage;
            label = outcomeLabel;
            revision++;
        }
    }

    public void log() {
        synchronized (monitor) {
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("revision overflow");
            }

            int newMask = mask | LOGGED;
            long newRevision = revision + 1;
            Event event = new Event(newMask, message, label, newRevision);
            QueueNode node = new QueueNode(event);

            mask = newMask;
            revision = newRevision;

            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            pendingCount++;
        }
    }

    public boolean dispatchNext() {
        Event event;

        synchronized (monitor) {
            if (dispatchActive || head == null) {
                return false;
            }

            QueueNode claimed = head;
            head = claimed.next;
            if (head == null) {
                tail = null;
            }
            pendingCount--;

            dispatchActive = true;
            event = copyEvent(claimed.event);
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
            QueueNode node = head;
            int index = 0;

            while (node != null) {
                events[index++] = copyEvent(node.event);
                node = node.next;
            }

            return new Snapshot(mask, message, label, revision, events, dispatchActive);
        }
    }

    private static Event copyEvent(Event event) {
        return new Event(event.mask, event.message, event.label, event.revision);
    }
}
