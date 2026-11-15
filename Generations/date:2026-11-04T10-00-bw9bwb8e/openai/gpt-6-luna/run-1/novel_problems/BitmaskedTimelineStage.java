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
            Event[] copy = new Event[pendingEvents.length];
            for (int i = 0; i < pendingEvents.length; i++) {
                Event event = pendingEvents[i];
                copy[i] = new Event(event.mask, event.message, event.label, event.revision);
            }
            return copy;
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

    private void setOutcome(int outcomeBit, String outcomeLabel, String newMessage) {
        if (newMessage == null) {
            throw new NullPointerException("message");
        }

        synchronized (stateMonitor) {
            ensureRevisionAvailable();
            mask = (mask & LOGGED) | outcomeBit;
            message = newMessage;
            label = outcomeLabel;
            revision++;
        }
    }

    public void log() {
        synchronized (stateMonitor) {
            ensureRevisionAvailable();

            int newMask = mask | LOGGED;
            long newRevision = revision + 1;
            Event event = new Event(newMask, message, label, newRevision);
            QueueNode node = new QueueNode(event);

            if (queueTail == null) {
                queueHead = node;
            } else {
                queueTail.next = node;
            }
            queueTail = node;

            mask = newMask;
            revision = newRevision;
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
            for (QueueNode node = queueHead; node != null; node = node.next) {
                count++;
            }

            Event[] events = new Event[count];
            int index = 0;
            for (QueueNode node = queueHead; node != null; node = node.next) {
                Event event = node.event;
                events[index++] = new Event(
                    event.mask, event.message, event.label, event.revision
                );
            }

            return new Snapshot(mask, message, label, revision, events, dispatchActive);
        }
    }

    private void ensureRevisionAvailable() {
        if (revision == Long.MAX_VALUE) {
            throw new ArithmeticException("revision overflow");
        }
    }
}
