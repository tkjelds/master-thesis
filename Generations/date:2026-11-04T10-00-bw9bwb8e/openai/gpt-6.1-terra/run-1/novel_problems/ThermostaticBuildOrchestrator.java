@ThermostaticBuildOrchestrator.ThreadSafe
public class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    public interface Job {
        void run();
    }

    public enum Mode {
        NORMAL,
        THROTTLED
    }

    public static final class Snapshot {
        public final Mode automaticMode;
        public final Mode overrideMode;
        public final Mode effectiveMode;
        public final int pendingCount;
        public final int runningCount;
        public final int limit;
        public final boolean closed;

        private final long[] pendingIds;

        private Snapshot(
                Mode automaticMode,
                Mode overrideMode,
                Mode effectiveMode,
                long[] pendingIds,
                int runningCount,
                int limit,
                boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingIds = pendingIds;
            this.pendingCount = pendingIds.length;
            this.runningCount = runningCount;
            this.limit = limit;
            this.closed = closed;
        }

        public long[] getPendingIds() {
            long[] copy = new long[pendingIds.length];
            for (int i = 0; i < pendingIds.length; i++) {
                copy[i] = pendingIds[i];
            }
            return copy;
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private final int normalLimit;
    private final int low;
    private final int high;
    private final Job[] jobs;
    private final long[] jobIds;

    private int head;
    private int pendingCount;
    private int runningCount;
    private long nextId = 1;
    private boolean closed;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;

    public ThermostaticBuildOrchestrator(
            int queueCapacity, int normalConcurrencyLimit, int low, int high) {
        if (queueCapacity <= 0 || normalConcurrencyLimit <= 0) {
            throw new IllegalArgumentException("Capacity and concurrency limit must be positive");
        }
        if (low < 0 || low >= high || high > 100) {
            throw new IllegalArgumentException("Thresholds must satisfy 0 <= low < high <= 100");
        }

        this.capacity = queueCapacity;
        this.normalLimit = normalConcurrencyLimit;
        this.low = low;
        this.high = high;
        this.jobs = new Job[queueCapacity];
        this.jobIds = new long[queueCapacity];
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }

        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("Orchestrator is closed");
            }
            if (pendingCount == capacity) {
                throw new IllegalStateException("Pending queue is full");
            }
            if (nextId == 0) {
                throw new IllegalStateException("Job IDs are exhausted");
            }

            long id = nextId;
            nextId = id == Long.MAX_VALUE ? 0 : id + 1;

            int tail;
            int remaining = capacity - head;
            if (pendingCount >= remaining) {
                tail = pendingCount - remaining;
            } else {
                tail = head + pendingCount;
            }

            jobs[tail] = job;
            jobIds[tail] = id;
            pendingCount++;
            monitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            int index = head;
            for (int offset = 0; offset < pendingCount; offset++) {
                if (jobIds[index] == jobId) {
                    int current = index;
                    for (int i = offset; i < pendingCount - 1; i++) {
                        int next = nextIndex(current);
                        jobs[current] = jobs[next];
                        jobIds[current] = jobIds[next];
                        current = next;
                    }
                    jobs[current] = null;
                    jobIds[current] = 0;
                    pendingCount--;
                    monitor.notifyAll();
                    return true;
                }
                index = nextIndex(index);
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("Load must be between 0 and 100");
        }

        synchronized (monitor) {
            if (load >= high) {
                automaticMode = Mode.THROTTLED;
            } else if (load <= low) {
                automaticMode = Mode.NORMAL;
            }
            monitor.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }

        synchronized (monitor) {
            overrideMode = mode;
            monitor.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (monitor) {
            overrideMode = null;
            monitor.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        Job job;

        synchronized (monitor) {
            while (true) {
                if (pendingCount > 0 && runningCount < effectiveLimit()) {
                    job = jobs[head];
                    jobs[head] = null;
                    jobIds[head] = 0;
                    head = nextIndex(head);
                    pendingCount--;
                    runningCount++;
                    monitor.notifyAll();
                    break;
                }

                if (closed && pendingCount == 0 && runningCount == 0) {
                    return false;
                }

                monitor.wait();
            }
        }

        try {
            job.run();
            return true;
        } finally {
            synchronized (monitor) {
                runningCount--;
                monitor.notifyAll();
            }
        }
    }

    public void close() {
        synchronized (monitor) {
            closed = true;
            monitor.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long[] ids = new long[pendingCount];
            int index = head;
            for (int i = 0; i < pendingCount; i++) {
                ids[i] = jobIds[index];
                index = nextIndex(index);
            }

            Mode effective = effectiveMode();
            int limit = effective == Mode.THROTTLED ? 1 : normalLimit;
            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effective,
                    ids,
                    runningCount,
                    limit,
                    closed);
        }
    }

    private int nextIndex(int index) {
        return index == capacity - 1 ? 0 : index + 1;
    }

    // Called only while holding monitor.
    private Mode effectiveMode() {
        return overrideMode == null ? automaticMode : overrideMode;
    }

    // Called only while holding monitor.
    private int effectiveLimit() {
        return effectiveMode() == Mode.THROTTLED ? 1 : normalLimit;
    }
}
