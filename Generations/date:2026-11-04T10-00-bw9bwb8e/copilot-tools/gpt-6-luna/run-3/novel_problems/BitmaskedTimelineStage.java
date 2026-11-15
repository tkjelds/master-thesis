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

    private static final int OUTCOME_MASK = SUCCESS | WARNING | FAILURE | SKIPPED;

    private final Object monitor = new Object();
    private final Timeline timeline;
    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision;
    private Event[] queue = new Event[8];
    private int queueHead;
    private int queueSize;
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

    private void setOutcome(String newMessage, int outcome, String newLabel) {
        synchronized (monitor) {
            if (newMessage == null) {
                throw new NullPointerException("message");
            }
            ensureRevisionCanAdvance();
            mask = (mask & ~OUTCOME_MASK) | outcome;
            message = newMessage;
            label = newLabel;
            revision++;
        }
    }

    public void log() {
        synchronized (monitor) {
            ensureRevisionCanAdvance();
            int newMask = mask | LOGGED;
            long newRevision = revision + 1;
            Event event = new Event(newMask, message, label, newRevision);
            ensureQueueCapacity();

            int tail = (queueHead + queueSize) % queue.length;
            queue[tail] = event;
            queueSize++;
            mask = newMask;
            revision = newRevision;
        }
    }

    public boolean dispatchNext() {
        Event event;
        synchronized (monitor) {
            if (queueSize == 0 || dispatchActive) {
                return false;
            }
            event = queue[queueHead];
            queue[queueHead] = null;
            queueHead = (queueHead + 1) % queue.length;
            queueSize--;
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
            Event[] pending = new Event[queueSize];
            for (int i = 0; i < queueSize; i++) {
                Event queued = queue[(queueHead + i) % queue.length];
                pending[i] = copyEvent(queued);
            }
            return new Snapshot(mask, message, label, revision, pending, dispatchActive);
        }
    }

    private void ensureRevisionCanAdvance() {
        if (revision == Long.MAX_VALUE) {
            throw new ArithmeticException("revision overflow");
        }
    }

    private void ensureQueueCapacity() {
        if (queueSize < queue.length) {
            return;
        }

        int newCapacity = queue.length * 2;
        if (newCapacity <= queue.length) {
            throw new OutOfMemoryError("event queue capacity overflow");
        }

        Event[] expanded = new Event[newCapacity];
        for (int i = 0; i < queueSize; i++) {
            expanded[i] = queue[(queueHead + i) % queue.length];
        }
        queue = expanded;
        queueHead = 0;
    }

    private static Event copyEvent(Event event) {
        return new Event(event.mask, event.message, event.label, event.revision);
    }
}
