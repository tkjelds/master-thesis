@ThermostaticBuildOrchestrator.ThreadSafe
public final class ThermostaticBuildOrchestrator {
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
        public final int runningCount;
        public final int admissionLimit;
        public final boolean closed;

        private final long[] pendingIds;

        private Snapshot(
                Mode automaticMode,
                Mode overrideMode,
                Mode effectiveMode,
                long[] pendingIds,
                int runningCount,
                int admissionLimit,
                boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingIds = pendingIds;
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
            this.closed = closed;
        }

        public long[] pendingIds() {
            long[] copy = new long[pendingIds.length];
            for (int i = 0; i < pendingIds.length; i++) {
                copy[i] = pendingIds[i];
            }
            return copy;
        }
    }

    private static final class Entry {
        final long id;
        final Job job;

        Entry(long id, Job job) {
            this.id = id;
            this.job = job;
        }
    }

    private final Object monitor = new Object();
    private final Entry[] queue;
    private final int normalLimit;
    private final int low;
    private final int high;

    // All mutable orchestrator state is protected by monitor.
    private int head;
    private int pendingCount;
    private int runningCount;
    private long nextId = 1;
    private boolean closed;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;

    public ThermostaticBuildOrchestrator(
            int queueCapacity, int normalLimit, int low, int high) {
        if (queueCapacity <= 0 || normalLimit <= 0) {
            throw new IllegalArgumentException("Capacity and limit must be positive");
        }
        if (low < 0 || low >= high || high > 100) {
            throw new IllegalArgumentException("Expected 0 <= low < high <= 100");
        }

        this.queue = new Entry[queueCapacity];
        this.normalLimit = normalLimit;
        this.low = low;
        this.high = high;
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }

        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("Orchestrator is closed");
            }
            if (pendingCount == queue.length) {
                throw new IllegalStateException("Pending queue is full");
            }
            if (nextId == 0) {
                throw new IllegalStateException("Job IDs exhausted");
            }

            long id = nextId;
            if (nextId == 9223372036854775807L) {
                nextId = 0;
            } else {
                nextId++;
            }

            queue[index(pendingCount)] = new Entry(id, job);
            pendingCount++;
            monitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            int position = -1;
            for (int i = 0; i < pendingCount; i++) {
                if (queue[index(i)].id == jobId) {
                    position = i;
                    break;
                }
            }

            if (position < 0) {
                return false;
            }

            for (int i = position; i < pendingCount - 1; i++) {
                queue[index(i)] = queue[index(i + 1)];
            }
            queue[index(pendingCount - 1)] = null;
            pendingCount--;
            monitor.notifyAll();
            return true;
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
        Entry entry;

        synchronized (monitor) {
            while (true) {
                if (pendingCount > 0 && runningCount < admissionLimit()) {
                    entry = queue[head];
                    queue[head] = null;
                    head++;
                    if (head == queue.length) {
                        head = 0;
                    }
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
            entry.job.run();
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
            for (int i = 0; i < pendingCount; i++) {
                ids[i] = queue[index(i)].id;
            }

            Mode effective = effectiveMode();
            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effective,
                    ids,
                    runningCount,
                    effective == Mode.THROTTLED ? 1 : normalLimit,
                    closed);
        }
    }

    // Called only while holding monitor. The offset must be less than queue.length.
    private int index(int offset) {
        int remaining = queue.length - head;
        return offset < remaining ? head + offset : offset - remaining;
    }

    // Called only while holding monitor.
    private Mode effectiveMode() {
        return overrideMode == null ? automaticMode : overrideMode;
    }

    // Called only while holding monitor.
    private int admissionLimit() {
        return effectiveMode() == Mode.THROTTLED ? 1 : normalLimit;
    }
}
