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

        public long[] getPendingIds() {
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
    private final Entry[] pending;
    private final int normalLimit;
    private final int low;
    private final int high;

    private int head;
    private int size;
    private int runningCount;
    private long lastId;
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
        this.pending = new Entry[queueCapacity];
        this.normalLimit = normalConcurrencyLimit;
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
            if (size == pending.length) {
                throw new IllegalStateException("Pending queue is full");
            }
            if (lastId == Long.MAX_VALUE) {
                throw new IllegalStateException("Job IDs are exhausted");
            }

            long id = lastId + 1;
            int tail = (int) (((long) head + size) % pending.length);
            pending[tail] = new Entry(id, job);
            lastId = id;
            size++;
            monitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            int index = head;
            for (int i = 0; i < size; i++) {
                if (pending[index].id == jobId) {
                    for (int j = i; j < size - 1; j++) {
                        int next = nextIndex(index);
                        pending[index] = pending[next];
                        index = next;
                    }
                    pending[index] = null;
                    size--;
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
        Entry entry;
        synchronized (monitor) {
            while (size == 0 || runningCount >= effectiveLimit()) {
                if (closed && size == 0 && runningCount == 0) {
                    return false;
                }
                monitor.wait();
            }

            entry = pending[head];
            pending[head] = null;
            head = nextIndex(head);
            size--;
            runningCount++;
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
            long[] ids = new long[size];
            int index = head;
            for (int i = 0; i < size; i++) {
                ids[i] = pending[index].id;
                index = nextIndex(index);
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

    private int nextIndex(int index) {
        return index == pending.length - 1 ? 0 : index + 1;
    }

    private Mode effectiveMode() {
        return overrideMode != null ? overrideMode : automaticMode;
    }

    private int effectiveLimit() {
        return effectiveMode() == Mode.THROTTLED ? 1 : normalLimit;
    }
}
