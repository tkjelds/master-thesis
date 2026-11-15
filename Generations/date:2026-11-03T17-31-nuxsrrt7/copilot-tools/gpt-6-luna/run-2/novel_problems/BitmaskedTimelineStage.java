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

    private final Object stateLock = new Object();
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
        synchronized (stateLock) {
            long nextRevision = nextRevision();
            mask = (mask & LOGGED) | outcome;
            message = newMessage;
            label = outcomeLabel;
            revision = nextRevision;
        }
    }

    public void log() {
        synchronized (stateLock) {
            long nextRevision = nextRevision();
            int nextMask = mask | LOGGED;
            Event event = new Event(nextMask, message, label, nextRevision);
            ensureQueueCapacity();
            mask = nextMask;
            revision = nextRevision;
            int tail = queueIndex(queueSize);
            queue[tail] = event;
            queueSize++;
        }
    }

    public boolean dispatchNext() {
        Event event;
        synchronized (stateLock) {
            if (dispatchActive || queueSize == 0) {
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
            synchronized (stateLock) {
                dispatchActive = false;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            Event[] pending = new Event[queueSize];
            for (int i = 0; i < queueSize; i++) {
                pending[i] = queue[queueIndex(i)].copy();
            }
            return new Snapshot(mask, message, label, revision, pending, dispatchActive);
        }
    }

    private long nextRevision() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("revision overflow");
        }
        return revision + 1;
    }

    private void ensureQueueCapacity() {
        if (queueSize < queue.length) {
            return;
        }
        if (queue.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("event queue is full");
        }
        int newLength = queue.length > (Integer.MAX_VALUE - 1) / 2
                ? Integer.MAX_VALUE : queue.length * 2;
        if (newLength <= queueSize) {
            throw new IllegalStateException("event queue is full");
        }
        Event[] expanded = new Event[newLength];
        for (int i = 0; i < queueSize; i++) {
            expanded[i] = queue[(queueHead + i) % queue.length];
        }
        queue = expanded;
        queueHead = 0;
    }

    private int queueIndex(int offset) {
        return (int) (((long) queueHead + offset) % queue.length);
    }
}
