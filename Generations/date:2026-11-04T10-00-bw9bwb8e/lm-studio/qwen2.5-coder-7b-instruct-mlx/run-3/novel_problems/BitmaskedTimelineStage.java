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
    private Event[] queue = new Event[64];
    private int head = 0, tail = 0;
    private boolean dispatching = false;

    public synchronized void report(Event event) {
        if (event == null) throw new NullPointerException("Event cannot be null");
        if (revision >= Integer.MAX_VALUE - 1) throw new IllegalStateException("Too many revisions");

        state = SUCCESS;
        this.message = event.getMessage();
        this.label = "SUCCESS";
        revision++;

        log();

        if (dispatchNext()) {
            dispatching = true;
        }
    }

    public synchronized void warning(String message) {
        if (message == null) throw new NullPointerException("Message cannot be null");
        if (revision >= Integer.MAX_VALUE - 1) throw new IllegalStateException("Too many revisions");

        state = WARNING;
        this.message = message;
        this.label = "WARNING";
        revision++;

        log();
    }

    public synchronized void failure(String message) {
        if (message == null) throw new NullPointerException("Message cannot be null");
        if (revision >= Integer.MAX_VALUE - 1) throw new IllegalStateException("Too many revisions");

        state = FAILURE;
        this.message = message;
        this.label = "FAILURE";
        revision++;

        log();
    }

    public synchronized void skip(String message) {
        if (message == null) throw new NullPointerException("Message cannot be null");
        if (revision >= Integer.MAX_VALUE - 1) throw new IllegalStateException("Too many revisions");

        state = SKIPPED;
        this.message = message;
        this.label = "SKIPPED";
        revision++;

        log();
    }

    public synchronized void log() {
        if ((state & LOGGED) == 0) {
            state |= LOGGED;
            Event e = new Event(state, message, label, revision);
            queue[tail] = e;
            tail = (tail + 1) % queue.length;

            if (!dispatching && dispatchNext()) {
                dispatching = true;
            }
        }
    }

    public synchronized boolean dispatchNext() {
        if (head == tail || dispatching) return false;

        Event e = queue[head];
        head = (head + 1) % queue.length;

        dispatching = true;
        try {
            e.report();
        } catch (RuntimeException ex) {
            dispatching = false;
            throw ex;
        }

        dispatching = false;
        return true;
    }

    public synchronized Snapshot snapshot() {
        Event[] q = new Event[queue.length];
        System.arraycopy(queue, head, q, 0, queue.length - head);
        System.arraycopy(queue, 0, q, queue.length - head, tail);

        return new Snapshot(state, message, label, revision, q, dispatching);
    }

    private static class Event {
        final int state;
        final String message;
        final String label;
        final int revision;

        Event(int state, String message, String label, int revision) {
            this.state = state;
            this.message = message;
            this.label = label;
            this.revision = revision;
        }

        void report() {
            // Simulate external event reporting
            System.out.println("Event reported: " + state + ", " + message + ", " + label + ", " + revision);
        }
    }

    public static class Snapshot {
        final int state;
        final String message;
        final String label;
        final int revision;
        final Event[] queue;
        final boolean dispatching;

        Snapshot(int state, String message, String label, int revision, Event[] queue, boolean dispatching) {
            this.state = state;
            this.message = message;
            this.label = label;
            this.revision = revision;
            this.queue = queue.clone();
            this.dispatching = dispatching;
        }
    }
}
