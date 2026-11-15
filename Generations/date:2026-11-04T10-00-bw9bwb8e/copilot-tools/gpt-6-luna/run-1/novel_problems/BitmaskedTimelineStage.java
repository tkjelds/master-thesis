@BitmaskedTimelineStage.ThreadSafe
public final class BitmaskedTimelineStage {
    @interface ThreadSafe {}

    public static final int INITIAL = 2;
    public static final int SUCCESS = 4;
    public static final int WARNING = 8;
    public static final int FAILURE = 16;
    public static final int SKIPPED = 32;
    public static final int LOGGED = 64;

    private static final int OUTCOME_MASK = SUCCESS | WARNING | FAILURE | SKIPPED;
    private static final int INITIAL_QUEUE_CAPACITY = 8;

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

    private final Object monitor;
    private final Timeline timeline;

    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision;

    private Event[] queue;
    private int queueHead;
    private int queueSize;
    private boolean dispatchActive;

    public BitmaskedTimelineStage(Timeline timeline) {
        if (timeline == null) {
            throw new NullPointerException("timeline");
        }
        this.timeline = timeline;
        this.monitor = new Object();
        this.queue = new Event[INITIAL_QUEUE_CAPACITY];
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

    public void log() {
        synchronized (monitor) {
            long nextRevision = nextRevisionLocked();
            int nextMask = mask | LOGGED;
            Event event = new Event(nextMask, message, label, nextRevision);
            ensureQueueCapacityLocked();

            mask = nextMask;
            revision = nextRevision;
            enqueueLocked(event);
        }
    }

    public boolean dispatchNext() {
        Event event;
        synchronized (monitor) {
            if (dispatchActive || queueSize == 0) {
                return false;
            }

            event = queue[queueHead];
            queue[queueHead] = null;
            queueHead++;
            if (queueHead == queue.length) {
                queueHead = 0;
            }
            queueSize--;
            if (queueSize == 0) {
                queueHead = 0;
            }
            dispatchActive = true;
        }

        try {
            timeline.report(event);
        } finally {
            synchronized (monitor) {
                dispatchActive = false;
            }
        }
        return true;
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            Event[] pending = new Event[queueSize];
            int index = queueHead;
            for (int i = 0; i < queueSize; i++) {
                pending[i] = queue[index].copy();
                index++;
                if (index == queue.length) {
                    index = 0;
                }
            }
            return new Snapshot(mask, message, label, revision, pending, dispatchActive);
        }
    }

    private void setOutcome(String newMessage, int outcome, String newLabel) {
        if (newMessage == null) {
            throw new NullPointerException("message");
        }

        synchronized (monitor) {
            long nextRevision = nextRevisionLocked();
            mask = (mask & ~(INITIAL | OUTCOME_MASK)) | outcome;
            message = newMessage;
            label = newLabel;
            revision = nextRevision;
        }
    }

    private long nextRevisionLocked() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("revision overflow");
        }
        return revision + 1;
    }

    private void ensureQueueCapacityLocked() {
        if (queueSize < queue.length) {
            return;
        }
        if (queue.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("pending event queue is full");
        }

        int newCapacity = queue.length <= Integer.MAX_VALUE / 2
                ? queue.length * 2
                : Integer.MAX_VALUE;
        Event[] expanded = new Event[newCapacity];
        int source = queueHead;
        for (int i = 0; i < queueSize; i++) {
            expanded[i] = queue[source];
            source++;
            if (source == queue.length) {
                source = 0;
            }
        }
        queue = expanded;
        queueHead = 0;
    }

    private void enqueueLocked(Event event) {
        int untilEnd = queue.length - queueHead;
        int tail = queueSize >= untilEnd
                ? queueSize - untilEnd
                : queueHead + queueSize;
        queue[tail] = event;
        queueSize++;
    }
}
