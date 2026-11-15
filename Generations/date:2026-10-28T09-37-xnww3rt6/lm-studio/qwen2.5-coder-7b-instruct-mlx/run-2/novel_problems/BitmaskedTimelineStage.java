@BitmaskedTimelineStage.ThreadSafe
public class BitmaskedTimelineStage {
    @interface ThreadSafe {}

    private static final int INITIAL = 2;
    private static final int SUCCESS = 4;
    private static final int WARNING = 8;
    private static final int FAILURE = 16;
    private static final int SKIPPED = 32;
    private static final int LOGGED = 64;

    private volatile int state = INITIAL;
    private String message;
    private String label;
    private int revision;
    private Event[] pendingEvents = new Event[16];
    private int head = 0;
    private int tail = 0;

    public void report(Event event) {
        synchronized (this) {
            if (event == null) {
                throw new IllegalArgumentException("Event must not be null");
            }
            state &= ~(SUCCESS | WARNING | FAILURE | SKIPPED);
            message = event.message;
            label = event.label.toUpperCase();
            state |= LOGGED;
        }
    }

    public void success(String message) {
        report(new Event(message, "SUCCESS"));
    }

    public void warning(String message) {
        report(new Event(message, "WARNING"));
    }

    public void failure(String message) {
        report(new Event(message, "FAILURE"));
    }

    public void skip(String message) {
        report(new Event(message, "SKIPPED"));
    }

    public void log() {
        synchronized (this) {
            state |= LOGGED;
            enqueueEvent(new Event(message, "LOG"));
        }
    }

    public boolean dispatchNext() {
        synchronized (this) {
            if (head == tail) {
                return false;
            }
            Event event = pendingEvents[head];
            head = (head + 1) % pendingEvents.length;
            new Thread(() -> event.report()).start();
            return true;
        }
    }

    public Timeline snapshot() {
        synchronized (this) {
            Event[] events = new Event[tail - head];
            for (int i = 0; i < events.length; i++) {
                events[i] = pendingEvents[(head + i) % pendingEvents.length];
            }
            return new Timeline(state, message, label, revision, events, dispatchNext());
        }
    }

    private void enqueueEvent(Event event) {
        synchronized (this) {
            if ((tail + 1) % pendingEvents.length == head) {
                Event[] newPendingEvents = new Event[pendingEvents.length * 2];
                for (int i = 0; i < pendingEvents.length; i++) {
                    newPendingEvents[i] = pendingEvents[(head + i) % pendingEvents.length];
                }
                head = 0;
                tail = pendingEvents.length;
                pendingEvents = newPendingEvents;
            }
        }
    }

    private static class Event {
        private final String message;
        private final String label;

        public Event(String message, String label) {
            this.message = message;
            this.label = label;
        }

        public void report() {
            // Implement the report method as per your requirements
        }
    }

    public interface Timeline {
        int getMask();
        String getMessage();
        String getLabel();
        int getRevision();
        Event[] getPendingEvents();
        boolean isDispatchActive();
    }
}
