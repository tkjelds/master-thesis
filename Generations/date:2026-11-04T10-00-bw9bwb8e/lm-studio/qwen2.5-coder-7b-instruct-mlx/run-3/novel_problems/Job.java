@ThermostaticBuildOrchestrator.ThreadSafe
class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    private final int capacity;
    private final int normalLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private int currentLoad = 0;
    private Mode mode = Mode.NORMAL;
    private final Job[] queue;
    private int head = 0;
    private int tail = 0;
    private int runningCount = 0;
    private int nextJobId = 1;
    private boolean closed = false;

    public ThermostaticBuildOrchestrator(int capacity, int normalLimit, int lowThreshold, int highThreshold) {
        if (capacity <= 0 || normalLimit <= 0 || lowThreshold < 0 || highThreshold > 100 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException();
        }
        this.capacity = capacity;
        this.normalLimit = normalLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        queue = new Job[capacity];
    }

    public synchronized long submit(Job job) throws IllegalStateException {
        if (closed || queueLength() == capacity) {
            throw new IllegalStateException();
        }
        if (job == null) {
            throw new NullPointerException();
        }
        int jobId = nextJobId++;
        queue[tail++] = job;
        tail %= capacity;
        updateMode();
        notifyAll();
        return jobId;
    }

    public synchronized boolean cancel(long jobId) {
        int index = (int)jobId - 1;
        if (index < head || index >= tail || queue[index % capacity] != null) {
            return false;
        }
        queue[index % capacity] = null;
        notifyAll();
        return true;
    }

    public synchronized void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException();
        }
        currentLoad = load;
        updateMode();
    }

    public synchronized void overrideMode(Mode mode) {
        this.mode = mode;
        notifyAll();
    }

    public synchronized void clearOverride() {
        this.mode = determineMode(currentLoad);
        notifyAll();
    }

    public synchronized boolean runNext() throws InterruptedException {
        while (queueLength() == 0 && !closed) {
            wait();
        }
        if (closed) {
            return false;
        }
        Job job = queue[head++];
        head %= capacity;
        runningCount++;
        if (mode == Mode.THROTTLED) {
            queue[tail++] = null;
            tail %= capacity;
        }
        notifyAll();
        try {
            job.run();
        } catch (RuntimeException e) {
            throw e;
        } finally {
            runningCount--;
            notifyAll();
        }
        return true;
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized Snapshot snapshot() {
        while (runningCount > 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Snapshot snapshot = new Snapshot(mode, queueLength(), runningCount, normalLimit, closed);
        notifyAll();
        return snapshot;
    }

    private void updateMode() {
        mode = determineMode(currentLoad);
        notifyAll();
    }

    private Mode determineMode(int load) {
        if (load >= highThreshold) {
            return Mode.THROTTLED;
        }
        if (load <= lowThreshold) {
            return Mode.NORMAL;
        }
        return mode;
    }

    private int queueLength() {
        if (head <= tail) {
            return tail - head;
        }
        return capacity + tail - head;
    }

    public interface Job {
        void run();
    }

    public enum Mode { NORMAL, THROTTLED }
}

@Snapshot.ThreadSafe
class Snapshot {
    @interface ThreadSafe {}

    private final Mode mode;
    private final int pendingJobs;
    private final int runningCount;
    private final int limit;
    private final boolean closed;

    public Snapshot(Mode mode, int pendingJobs, int runningCount, int limit, boolean closed) {
        this.mode = mode;
        this.pendingJobs = pendingJobs;
        this.runningCount = runningCount;
        this.limit = limit;
        this.closed = closed;
    }

    public Mode getMode() {
        return mode;
    }

    public int getPendingJobs() {
        return pendingJobs;
    }

    public int getRunningCount() {
        return runningCount;
    }

    public int getLimit() {
        return limit;
    }

    public boolean isClosed() {
        return closed;
    }
}
