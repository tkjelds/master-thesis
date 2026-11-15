@BitmaskedTimelineStage.ThreadSafe
public class BitmaskedTimelineStage {
    @interface ThreadSafe {}

    private static final int INITIAL = 2;
    private static final int SUCCESS = 4;
    private static final int WARNING = 8;
    private static final int FAILURE = 16;
    private static final int SKIPPED = 32;
    private static final int LOGGED = 64;

    private int state = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private int revision = 0;
    private final Object queueLock = new Object();
    private Event[] eventQueue = new Event[10];
    private int enqueueIndex = 0;
    private int dequeueIndex = 0;
    private boolean dispatchActive = false;

    public interface Timeline {
        void report(Event event);
        void success(String message);
        void warning(String message);
        void failure(String message);
        void skip(String message);
        void log();
        boolean dispatchNext();
        Object[] snapshot();
    }

    public class Event {
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

    public BitmaskedTimelineStage(Timeline timeline) {
        // Constructor does nothing as all operations are thread-safe
    }

    public synchronized void success(String message) {
        if (message == null) throw new IllegalArgumentException("null message");
        state = SUCCESS;
        this.message = message;
        label = "SUCCESS";
        revision++;
    }

    public synchronized void warning(String message) {
        if (message == null) throw new IllegalArgumentException("null message");
        state = WARNING;
        this.message = message;
        label = "WARNING";
        revision++;
    }

    public synchronized void failure(String message) {
        if (message == null) throw new IllegalArgumentException("null message");
        state = FAILURE;
        this.message = message;
        label = "FAILURE";
        revision++;
    }

    public synchronized void skip(String message) {
        if (message == null) throw new IllegalArgumentException("null message");
        state = SKIPPED;
        this.message = message;
        label = "SKIPPED";
        revision++;
    }

    public synchronized void log() {
        state |= LOGGED;
        enqueueEvent(new Event(state, message, label, revision));
    }

    public synchronized boolean dispatchNext() {
        if (dispatchActive || enqueueIndex == dequeueIndex) return false;
        dispatchActive = true;
        try {
            if (enqueueIndex == dequeueIndex) return false;
            Event event = eventQueue[dequeueIndex];
            dequeueIndex = (dequeueIndex + 1) % eventQueue.length;
            timeline.report(event);
        } finally {
            dispatchActive = false;
        }
        return true;
    }

    public synchronized Object[] snapshot() {
        int size = (enqueueIndex - dequeueIndex + eventQueue.length) % eventQueue.length;
        Event[] snapshotEvents = new Event[size];
        for (int i = 0; i < size; i++) {
            snapshotEvents[i] = eventQueue[(dequeueIndex + i) % eventQueue.length];
        }
        return new Object[]{state, message, label, revision, snapshotEvents, dispatchActive};
    }

    private synchronized void enqueueEvent(Event event) {
        if (enqueueIndex - dequeueIndex == eventQueue.length) {
            // Resize the queue
            Event[] newEventQueue = new Event[eventQueue.length * 2];
            int size = (enqueueIndex - dequeueIndex + eventQueue.length) % eventQueue.length;
            System.arraycopy(eventQueue, dequeueIndex, newEventQueue, 0, size);
            enqueueIndex = size;
            dequeueIndex = 0;
            eventQueue = newEventQueue;
        }
        eventQueue[enqueueIndex] = event;
        enqueueIndex = (enqueueIndex + 1) % eventQueue.length;
    }
}
