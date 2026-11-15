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
            return pendingEvents;
        }

        public boolean isDispatchActive() {
            return dispatchActive;
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
    private boolean dispatchActive;

    public BitmaskedTimelineStage(Timeline timeline) {
        if (timeline == null) {
            throw new IllegalArgumentException("timeline must not be null");
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
        synchronized (stateMonitor) {
            if (newMessage == null) {
                throw new IllegalArgumentException("message must not be null");
            }
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

            int resultingMask = mask | LOGGED;
            long resultingRevision = revision + 1;
            Event event = new Event(resultingMask, message, label, resultingRevision);
            QueueNode node = new QueueNode(event);

            if (queueTail == null) {
                queueHead = node;
            } else {
                queueTail.next = node;
            }
            queueTail = node;

            mask = resultingMask;
            revision = resultingRevision;
        }
    }

    public boolean dispatchNext() {
        Event event;
        synchronized (stateMonitor) {
            if (dispatchActive || queueHead == null) {
                return false;
            }

            QueueNode node = queueHead;
            queueHead = node.next;
            if (queueHead == null) {
                queueTail = null;
            }

            dispatchActive = true;
            event = node.event;
        }

        try {
            timeline.report(event);
        } finally {
            synchronized (stateMonitor) {
                dispatchActive = false;
            }
        }
        return true;
    }

    public Snapshot snapshot() {
        synchronized (stateMonitor) {
            int count = 0;
            for (QueueNode node = queueHead; node != null; node = node.next) {
                count++;
            }

            Event[] events = new Event[count];
            int index = 0;
            for (QueueNode node = queueHead; node != null; node = node.next) {
                events[index++] = copyEvent(node.event);
            }

            return new Snapshot(mask, message, label, revision, events, dispatchActive);
        }
    }

    private void ensureRevisionAvailable() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("revision overflow");
        }
    }

    private static Event copyEvent(Event event) {
        return new Event(event.mask, event.message, event.label, event.revision);
    }
}
