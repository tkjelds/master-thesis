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

        private Event copy() {
            return new Event(mask, message, label, revision);
        }
    }

    public static class Snapshot {
        public int mask;
        public String message;
        public String label;
        public long revision;
        public Event[] pendingEvents;
        public boolean dispatchActive;

        private Snapshot(int mask, String message, String label, long revision,
                         Event[] pendingEvents, boolean dispatchActive) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
            this.pendingEvents = pendingEvents;
            this.dispatchActive = dispatchActive;
        }
    }

    private static final class QueueNode {
        private final Event event;
        private QueueNode next;

        private QueueNode(Event event) {
            this.event = event;
        }
    }

    private final Timeline timeline;
    private final Object stateMonitor = new Object();

    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision = 0L;

    private QueueNode queueHead;
    private QueueNode queueTail;
    private boolean dispatchActive;

    public BitmaskedTimelineStage(Timeline timeline) {
        if (timeline == null) {
            throw new NullPointerException("timeline");
        }
        this.timeline = timeline;
    }

    public void success(String message) {
        setOutcome(message, SUCCESS, "SUCCESS");
    }

    public void warning(String message) {
        setOutcome(message, WARNING, "WARNING");
    }

    public void failure(String message) {
        setOutcome(message, FAILURE, "FAILURE");
    }

    public void skip(String message) {
        setOutcome(message, SKIPPED, "SKIPPED");
    }

    private void setOutcome(String newMessage, int outcomeBit, String outcomeLabel) {
        if (newMessage == null) {
            throw new NullPointerException("message");
        }

        synchronized (stateMonitor) {
            ensureRevisionAvailable();
            int newMask = (mask & LOGGED) | outcomeBit;
            long newRevision = revision + 1L;

            mask = newMask;
            message = newMessage;
            label = outcomeLabel;
            revision = newRevision;
        }
    }

    public void log() {
        synchronized (stateMonitor) {
            ensureRevisionAvailable();

            long newRevision = revision + 1L;
            int newMask = mask | LOGGED;
            Event event = new Event(newMask, message, label, newRevision);
            QueueNode node = new QueueNode(event);

            mask = newMask;
            revision = newRevision;

            if (queueTail == null) {
                queueHead = node;
                queueTail = node;
            } else {
                queueTail.next = node;
                queueTail = node;
            }
        }
    }

    public boolean dispatchNext() {
        Event event;

        synchronized (stateMonitor) {
            if (dispatchActive || queueHead == null) {
                return false;
            }

            QueueNode claimed = queueHead;
            queueHead = claimed.next;
            if (queueHead == null) {
                queueTail = null;
            }

            dispatchActive = true;
            event = claimed.event;
        }

        try {
            timeline.report(event);
            return true;
        } finally {
            synchronized (stateMonitor) {
                dispatchActive = false;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (stateMonitor) {
            int count = 0;
            QueueNode node = queueHead;
            while (node != null) {
                count++;
                node = node.next;
            }

            Event[] pending = new Event[count];
            node = queueHead;
            for (int i = 0; i < count; i++) {
                pending[i] = node.event.copy();
                node = node.next;
            }

            return new Snapshot(mask, message, label, revision, pending, dispatchActive);
        }
    }

    private void ensureRevisionAvailable() {
        if (revision == Long.MAX_VALUE) {
            throw new ArithmeticException("revision overflow");
        }
    }
}
