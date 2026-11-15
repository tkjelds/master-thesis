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

        public Event() {
        }

        public Event(int mask, String message, String label, long revision) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
        }

        public int mask() {
            return mask;
        }

        public String message() {
            return message;
        }

        public String label() {
            return label;
        }

        public long revision() {
            return revision;
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

        public int mask() {
            return mask;
        }

        public String message() {
            return message;
        }

        public String label() {
            return label;
        }

        public long revision() {
            return revision;
        }

        public Event[] pendingEvents() {
            return pendingEvents;
        }

        public boolean dispatchActive() {
            return dispatchActive;
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
            long newRevision = revision + 1;
            int newMask = mask | LOGGED;
            Event event = new Event(newMask, message, label, newRevision);
            ensureQueueCapacity();

            mask = newMask;
            revision = newRevision;
            enqueue(event);
        }
    }

    public boolean dispatchNext() {
        Event event;
        synchronized (stateMonitor) {
            if (dispatchActive || queueSize == 0) {
                return false;
            }
            event = dequeue();
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
            Event[] copiedEvents = new Event[queueSize];
            for (int i = 0; i < queueSize; i++) {
                int index = queueHead + i;
                if (index >= queue.length) {
                    index -= queue.length;
                }
                Event queued = queue[index];
                copiedEvents[i] = new Event(
                    queued.mask, queued.message, queued.label, queued.revision
                );
            }
            return new Snapshot(
                mask, message, label, revision, copiedEvents, dispatchActive
            );
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

        int newCapacity = queue.length * 2;
        if (newCapacity <= queue.length) {
            newCapacity = Integer.MAX_VALUE;
        }
        Event[] expanded = new Event[newCapacity];
        for (int i = 0; i < queueSize; i++) {
            int index = queueHead + i;
            if (index >= queue.length) {
                index -= queue.length;
            }
            expanded[i] = queue[index];
        }
        queue = expanded;
        queueHead = 0;
    }

    private void enqueue(Event event) {
        int index = queueHead + queueSize;
        if (index >= queue.length) {
            index -= queue.length;
        }
        queue[index] = event;
        queueSize++;
    }

    private Event dequeue() {
        Event event = queue[queueHead];
        queue[queueHead] = null;
        queueHead++;
        if (queueHead == queue.length) {
            queueHead = 0;
        }
        queueSize--;
        return event;
    }
}
