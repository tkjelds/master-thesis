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

    public static final class Snapshot {
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

    private static final class QueueNode {
        private final Event event;
        private QueueNode next;

        private QueueNode(Event event) {
            this.event = event;
        }
    }

    private final Object stateMonitor = new Object();
    private final Timeline timeline;
    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision;
    private QueueNode queueHead;
    private QueueNode queueTail;
    private int queueSize;
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

    private void setOutcome(int outcome, String outcomeLabel, String newMessage) {
        if (newMessage == null) {
            throw new NullPointerException("message");
        }
        synchronized (stateMonitor) {
            ensureRevisionAvailable();
            mask = (mask & LOGGED) | outcome;
            message = newMessage;
            label = outcomeLabel;
            revision++;
        }
    }

    public void log() {
        synchronized (stateMonitor) {
            ensureRevisionAvailable();
            long nextRevision = revision + 1;
            int nextMask = mask | LOGGED;
            Event event = new Event(nextMask, message, label, nextRevision);
            QueueNode node = new QueueNode(event);

            mask = nextMask;
            revision = nextRevision;
            if (queueTail == null) {
                queueHead = node;
            } else {
                queueTail.next = node;
            }
            queueTail = node;
            queueSize++;
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
            queueSize--;
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
            Event[] pending = new Event[queueSize];
            QueueNode node = queueHead;
            for (int i = 0; i < pending.length; i++) {
                pending[i] = node.event.copy();
                node = node.next;
            }
            return new Snapshot(mask, message, label, revision, pending, dispatchActive);
        }
    }

    private void ensureRevisionAvailable() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("revision overflow");
        }
    }
}
