@BitmaskedTimelineStage.ThreadSafe
public class BitmaskedTimelineStage {
    @interface ThreadSafe {}

    public static final int INITIAL = 2;
    public static final int SUCCESS = 4;
    public static final int WARNING = 8;
    public static final int FAILURE = 16;
    public static final int SKIPPED = 32;
    public static final int LOGGED = 64;

    private static final int OUTCOME_MASK = SUCCESS | WARNING | FAILURE | SKIPPED;

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

    private final Object stateMonitor = new Object();
    private final Timeline timeline;
    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision;

    private Event[] queue = new Event[16];
    private int queueHead;
    private int queueTail;
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
            mask = (mask & ~OUTCOME_MASK) | outcome;
            message = newMessage;
            label = outcomeLabel;
            revision++;
        }
    }

    public void log() {
        synchronized (stateMonitor) {
            ensureRevisionAvailable();
            ensureQueueCapacity();

            long nextRevision = revision + 1;
            int nextMask = mask | LOGGED;
            Event event = new Event(nextMask, message, label, nextRevision);

            mask = nextMask;
            revision = nextRevision;
            queue[queueTail] = event;
            queueTail = nextIndex(queueTail, queue.length);
            queueSize++;
        }
    }

    public boolean dispatchNext() {
        Event claimed;
        synchronized (stateMonitor) {
            if (queueSize == 0 || dispatchActive) {
                return false;
            }

            claimed = queue[queueHead];
            queue[queueHead] = null;
            queueHead = nextIndex(queueHead, queue.length);
            queueSize--;
            dispatchActive = true;
        }

        try {
            timeline.report(claimed);
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
            int index = queueHead;
            for (int i = 0; i < queueSize; i++) {
                pending[i] = queue[index].copy();
                index = nextIndex(index, queue.length);
            }
            return new Snapshot(mask, message, label, revision, pending, dispatchActive);
        }
    }

    private void ensureRevisionAvailable() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("revision overflow");
        }
    }

    private void ensureQueueCapacity() {
        if (queueSize < queue.length) {
            return;
        }

        int newCapacity;
        if (queue.length <= Integer.MAX_VALUE / 2) {
            newCapacity = queue.length * 2;
        } else if (queue.length < Integer.MAX_VALUE) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            throw new IllegalStateException("event queue capacity overflow");
        }

        Event[] expanded = new Event[newCapacity];
        int index = queueHead;
        for (int i = 0; i < queueSize; i++) {
            expanded[i] = queue[index];
            index = nextIndex(index, queue.length);
        }
        queue = expanded;
        queueHead = 0;
        queueTail = queueSize;
    }

    private static int nextIndex(int index, int length) {
        index++;
        return index == length ? 0 : index;
    }
}
