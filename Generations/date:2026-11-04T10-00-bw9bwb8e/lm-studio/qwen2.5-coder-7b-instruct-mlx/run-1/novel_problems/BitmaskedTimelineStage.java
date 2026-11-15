@BitmaskedTimelineStage.ThreadSafe
public class BitmaskedTimelineStage {
    @interface ThreadSafe {}

    private static final int INITIAL = 2;
    private static final int SUCCESS = 4;
    private static final int WARNING = 8;
    private static final int FAILURE = 16;
    private static final int SKIPPED = 32;
    private static final int LOGGED = 64;

    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private int revision = 0;
    private Event[] queue = new Event[16];
    private int head = 0, tail = 0;

    public void report(Event event) {
        synchronized (this) {
            advanceRevision();
            mask &= ~(SUCCESS | WARNING | FAILURE | SKIPPED);
            message = "";
            label = "INITIAL";
        }
    }

    public void success(String message) {
        if (message == null) throw new IllegalArgumentException("Message cannot be null");
        synchronized (this) {
            advanceRevision();
            mask |= SUCCESS;
            this.message = message;
            label = "SUCCESS";
        }
    }

    public void warning(String message) {
        if (message == null) throw new IllegalArgumentException("Message cannot be null");
        synchronized (this) {
            advanceRevision();
            mask |= WARNING;
            this.message = message;
            label = "WARNING";
        }
    }

    public void failure(String message) {
        if (message == null) throw new IllegalArgumentException("Message cannot be null");
        synchronized (this) {
            advanceRevision();
            mask |= FAILURE;
            this.message = message;
            label = "FAILURE";
        }
    }

    public void skip(String message) {
        if (message == null) throw new IllegalArgumentException("Message cannot be null");
        synchronized (this) {
            advanceRevision();
            mask |= SKIPPED;
            this.message = message;
            label = "SKIPPED";
        }
    }

    public void log() {
        synchronized (this) {
            advanceRevision();
            mask |= LOGGED;
            queue[tail] = new Event(mask, message, label, revision);
            tail = (tail + 1) % queue.length;
        }
    }

    public boolean dispatchNext() {
        synchronized (this) {
            if (head == tail || dispatching) return false;
            dispatching = true;
        }
        try {
            Event event = queue[head];
            head = (head + 1) % queue.length;
            new Thread(() -> timeline.report(event)).start();
            return true;
        } finally {
            synchronized (this) {
                dispatching = false;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(mask, message, label, revision, Arrays.copyOfRange(queue, head, tail), dispatching);
        }
    }

    private void advanceRevision() {
        if (revision == Integer.MAX_VALUE) throw new IllegalStateException("Revision overflow");
        revision++;
    }

    private boolean dispatching = false;

    public interface Timeline {
        void report(Event event);
    }

    public static class Event {
        private final int mask;
        private final String message;
        private final String label;
        private final int revision;

        public Event(int mask, String message, String label, int revision) {
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

        public int getRevision() {
            return revision;
        }
    }

    public static class Snapshot {
        private final int mask;
        private final String message;
        private final String label;
        private final int revision;
        private final Event[] events;
        private final boolean dispatching;

        public Snapshot(int mask, String message, String label, int revision, Event[] events, boolean dispatching) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
            this.events = events;
            this.dispatching = dispatching;
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

        public int getRevision() {
            return revision;
        }

        public Event[] getEvents() {
            return events;
        }

        public boolean isDispatching() {
            return dispatching;
        }
    }
}
