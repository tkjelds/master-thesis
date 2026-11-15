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

    private static final int OUTCOME_MASK = SUCCESS | WARNING | FAILURE | SKIPPED;

    private final Object monitor = new Object();
    private final Timeline timeline;
    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private long revision;
    private Event[] queue = new Event[8];
    private int head;
    private int size;
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

    public void log() {
        synchronized (monitor) {
            ensureRevisionAvailable();
            long nextRevision = revision + 1;
            int nextMask = mask | LOGGED;
            Event event = new Event(nextMask, message, label, nextRevision);
            ensureQueueCapacity();

            int tail;
            int remaining = queue.length - head;
            if (size >= remaining) {
                tail = size - remaining;
            } else {
                tail = head + size;
            }
            queue[tail] = event;
            size++;
            mask = nextMask;
            revision = nextRevision;
        }
    }

    public boolean dispatchNext() {
        Event event;
        synchronized (monitor) {
            if (dispatchActive || size == 0) {
                return false;
            }
            event = queue[head];
            queue[head] = null;
            head++;
            if (head == queue.length) {
                head = 0;
            }
            size--;
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
            Event[] pending = new Event[size];
            for (int i = 0; i < size; i++) {
                int index = head + i;
                if (index >= queue.length) {
                    index -= queue.length;
                }
                pending[i] = queue[index].copy();
            }
            return new Snapshot(mask, message, label, revision, pending, dispatchActive);
        }
    }

    private void setOutcome(int outcome, String outcomeLabel, String newMessage) {
        if (newMessage == null) {
            throw new NullPointerException("message");
        }
        synchronized (monitor) {
            ensureRevisionAvailable();
            mask = (mask & LOGGED) | outcome;
            message = newMessage;
            label = outcomeLabel;
            revision++;
        }
    }

    private void ensureRevisionAvailable() {
        if (revision == Long.MAX_VALUE) {
            throw new ArithmeticException("revision overflow");
        }
    }

    private void ensureQueueCapacity() {
        if (size < queue.length) {
            return;
        }

        int newCapacity = queue.length <= Integer.MAX_VALUE / 2
                ? queue.length * 2
                : Integer.MAX_VALUE;
        Event[] expanded = new Event[newCapacity];
        for (int i = 0; i < size; i++) {
            int index = head + i;
            if (index >= queue.length) {
                index -= queue.length;
            }
            expanded[i] = queue[index];
        }
        queue = expanded;
        head = 0;
    }
}
