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

    private final Object stateMonitor = new Object();
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

    public void log() {
        synchronized (stateMonitor) {
            long nextRevision = nextRevision();
            int nextMask = mask | LOGGED;
            Event event = new Event(nextMask, message, label, nextRevision);
            ensureQueueCapacity();

            int tail = (queueHead + queueSize) % queue.length;
            queue[tail] = event;
            queueSize++;
            mask = nextMask;
            revision = nextRevision;
        }
    }

    public boolean dispatchNext() {
        Event event;

        synchronized (stateMonitor) {
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
            synchronized (stateMonitor) {
                dispatchActive = false;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (stateMonitor) {
            Event[] pendingEvents = new Event[queueSize];
            for (int i = 0; i < queueSize; i++) {
                Event event = queue[(queueHead + i) % queue.length];
                pendingEvents[i] = new Event(
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

    private void setOutcome(String message, int outcomeBit, String outcomeLabel) {
        if (message == null) {
            throw new NullPointerException("message");
        }

        synchronized (stateMonitor) {
            long nextRevision = nextRevision();
            mask = (mask & LOGGED) | outcomeBit;
            this.message = message;
            label = outcomeLabel;
            revision = nextRevision;
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

        int newCapacity = queue.length <= Integer.MAX_VALUE / 2
                ? queue.length * 2
                : Integer.MAX_VALUE;
        Event[] expanded = new Event[newCapacity];
        for (int i = 0; i < queueSize; i++) {
            expanded[i] = queue[(queueHead + i) % queue.length];
        }
        queue = expanded;
        queueHead = 0;
    }
}
