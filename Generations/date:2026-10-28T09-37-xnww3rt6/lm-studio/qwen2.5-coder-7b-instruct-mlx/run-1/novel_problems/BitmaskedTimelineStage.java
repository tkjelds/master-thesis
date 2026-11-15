@BitmaskedTimelineStage.ThreadSafe
public class BitmaskedTimelineStage {
    @interface ThreadSafe {}

    private static final int INITIAL = 2;
    private static final int SUCCESS = 4;
    private static final int WARNING = 8;
    private static final int FAILURE = 16;
    private static final int SKIPPED = 32;
    private static final int LOGGED = 64;

    private int state;
    private String message;
    private String label;
    private long revision;
    private Event[] queue;
    private int front, back;
    private boolean dispatching;

    public BitmaskedTimelineStage() {
        state = INITIAL;
        message = "";
        label = "INITIAL";
        revision = 0;
        queue = new Event[16];
        front = back = dispatching = false;
    }

    public synchronized void report(Event event) {
        if (event == null) throw new NullPointerException("Event must not be null");
        updateState(state | event.mask);
    }

    public synchronized void success(String message) {
        checkMessage(message);
        updateState(state | SUCCESS, message, "SUCCESS", true);
    }

    public synchronized void warning(String message) {
        checkMessage(message);
        updateState(state | WARNING, message, "WARNING", true);
    }

    public synchronized void failure(String message) {
        checkMessage(message);
        updateState(state | FAILURE, message, "FAILURE", true);
    }

    public synchronized void skip(String message) {
        checkMessage(message);
        updateState(state | SKIPPED, message, "SKIPPED", true);
    }

    public synchronized void log() {
        updateState(state | LOGGED, message, label, false);
    }

    public synchronized boolean dispatchNext() {
        if (dispatching || front == back) return false;
        dispatching = true;
        try {
            if (front == back) return false;
            Event event = queue[front++];
            new Thread(() -> {
                try {
                    state = INITIAL;
                    message = "";
                    label = "INITIAL";
                    revision++;
                    event.report();
                } catch (RuntimeException e) {
                    // Propagate exception but release dispatch claim
                    throw e;
                } finally {
                    dispatching = false;
                }
            }).start();
        } catch (RuntimeException e) {
            dispatching = false;
            throw e;
        }
        return true;
    }

    public synchronized Timeline snapshot() {
        return new Timeline(state, message, label, revision, queue.clone(), dispatching);
    }

    private synchronized void updateState(int newState) {
        state = newState;
        log();
    }

    private synchronized void updateState(int newState, String newMessage, String newLabel, boolean log) {
        state = newState;
        message = newMessage;
        label = newLabel;
        if (log) log();
    }

    private void checkMessage(String message) {
        if (message == null || message.isEmpty()) throw new IllegalArgumentException("Message must not be empty");
    }

    public static class Event {
        private final int mask;
        private final Runnable report;

        public Event(int mask, Runnable report) {
            this.mask = mask;
            this.report = report;
        }

        public void report() {
            report.run();
        }
    }

    public static class Timeline {
        private final int state;
        private final String message;
        private final String label;
        private final long revision;
        private final Event[] queue;
        private final boolean dispatching;

        public Timeline(int state, String message, String label, long revision, Event[] queue, boolean dispatching) {
            this.state = state;
            this.message = message;
            this.label = label;
            this.revision = revision;
            this.queue = queue;
            this.dispatching = dispatching;
        }

        public int getState() {
            return state;
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

        public Event[] getQueue() {
            return queue.clone(); // Defensive copy
        }

        public boolean isDispatching() {
            return dispatching;
        }
    }
}
