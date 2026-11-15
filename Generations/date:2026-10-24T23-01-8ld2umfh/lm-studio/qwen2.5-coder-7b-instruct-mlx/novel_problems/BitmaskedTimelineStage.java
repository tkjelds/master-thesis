@BitmaskedTimelineStage.ThreadSafe
class BitmaskedTimelineStage {
    @interface ThreadSafe {}

    private static final int INITIAL = 2, SUCCESS = 4, WARNING = 8, FAILURE = 16, SKIPPED = 32, LOGGED = 64;
    private int mask = INITIAL;
    private String message = "";
    private String label = "INITIAL";
    private int revision = 0;
    private Event[] queue = new Event[16];
    private int head = 0, tail = 0;
    private boolean dispatching = false;

    public synchronized void report(Event event) {
        if (event == null) return;
        mask = SUCCESS; // Replace with appropriate outcome
        message = "Success";
        label = "SUCCESS";
        log();
    }

    public synchronized void warning(String message) {
        if (message == null) return;
        this.message = message;
        mask = WARNING;
        label = "WARNING";
        log();
    }

    public synchronized void failure(String message) {
        if (message == null) return;
        this.message = message;
        mask = FAILURE;
        label = "FAILURE";
        log();
    }

    public synchronized void skip(String message) {
        if (message == null) return;
        this.message = message;
        mask = SKIPPED;
        label = "SKIPPED";
        log();
    }

    public synchronized void log() {
        if (revision >= Integer.MAX_VALUE) return;
        queue[tail++] = new Event(mask, message, label, revision);
        tail %= queue.length;
        mask |= LOGGED;
        revision++;
    }

    public synchronized boolean dispatchNext() {
        if (dispatching || head == tail) return false;
        dispatching = true;
        try {
            Event event = queue[head++];
            head %= queue.length;
            new Thread(() -> report(event)).start();
        } finally {
            dispatching = false;
        }
        return true;
    }

    public synchronized Event[] snapshot() {
        Event[] snapshot = queue.clone();
        return new Event[]{
            new Event(mask, message, label, revision),
            snapshot
        };
    }

    static class Event {
        int mask;
        String message;
        String label;
        int revision;

        Event(int mask, String message, String label, int revision) {
            this.mask = mask;
            this.message = message;
            this.label = label;
            this.revision = revision;
        }
    }
}
