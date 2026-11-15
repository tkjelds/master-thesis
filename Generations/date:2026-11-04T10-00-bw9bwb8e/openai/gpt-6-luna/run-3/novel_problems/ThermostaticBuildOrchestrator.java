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
        public final Mode effectiveMode;
        public final Mode overrideMode;
        public final long[] pendingJobIds;
        public final int runningCount;
        public final int admissionLimit;
        public final boolean closed;

        private Snapshot(
                Mode automaticMode,
                Mode effectiveMode,
                Mode overrideMode,
                long[] pendingJobIds,
                int runningCount,
                int admissionLimit,
                boolean closed) {
            this.automaticMode = automaticMode;
            this.effectiveMode = effectiveMode;
            this.overrideMode = overrideMode;
            this.pendingJobIds = new long[pendingJobIds.length];
            for (int i = 0; i < pendingJobIds.length; i++) {
                this.pendingJobIds[i] = pendingJobIds[i];
            }
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
            this.closed = closed;
        }

        public long[] getPendingJobIds() {
            long[] copy = new long[pendingJobIds.length];
            for (int i = 0; i < pendingJobIds.length; i++) {
                copy[i] = pendingJobIds[i];
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
    private final int lowThreshold;
    private final int highThreshold;

    private int pendingCount;
    private int runningCount;
    private long nextJobId = 1;
    private boolean idsExhausted;
    private boolean closed;

    private Mode automaticMode = Mode.NORMAL;
    private Mode manualOverride;

    public ThermostaticBuildOrchestrator(
            int queueCapacity,
            int normalConcurrencyLimit,
            int lowThreshold,
            int highThreshold) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queue capacity must be positive");
        }
        if (normalConcurrencyLimit <= 0) {
            throw new IllegalArgumentException("normal concurrency limit must be positive");
        }
        if (lowThreshold < 0 || highThreshold > 100 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low < high <= 100");
        }

        this.pending = new Entry[queueCapacity];
        this.normalLimit = normalConcurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }

        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (pendingCount == pending.length) {
                throw new IllegalStateException("pending queue is full");
            }
            if (idsExhausted) {
                throw new IllegalStateException("job ID space is exhausted");
            }

            long id = nextJobId;
            if (nextJobId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextJobId++;
            }

            pending[pendingCount++] = new Entry(id, job);
            monitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            for (int i = 0; i < pendingCount; i++) {
                if (pending[i].id == jobId) {
                    removeAt(i);
                    monitor.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("load must be between 0 and 100");
        }

        synchronized (monitor) {
            if (load >= highThreshold) {
                automaticMode = Mode.THROTTLED;
            } else if (load <= lowThreshold) {
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
            manualOverride = mode;
            monitor.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (monitor) {
            manualOverride = null;
            monitor.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        final Entry entry;

        synchronized (monitor) {
            while (true) {
                if (pendingCount == 0) {
                    if (closed) {
                        return false;
                    }
                    monitor.wait();
                    continue;
                }

                if (runningCount < currentLimit()) {
                    entry = pending[0];
                    removeAt(0);
                    runningCount++;
                    monitor.notifyAll();
                    break;
                }

                monitor.wait();
            }
        }

        try {
            entry.job.run();
        } finally {
            synchronized (monitor) {
                runningCount--;
                monitor.notifyAll();
            }
        }
        return true;
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
                ids[i] = pending[i].id;
            }

            Mode effectiveMode = manualOverride != null ? manualOverride : automaticMode;
            return new Snapshot(
                    automaticMode,
                    effectiveMode,
                    manualOverride,
                    ids,
                    runningCount,
                    limitFor(effectiveMode),
                    closed);
        }
    }

    private int currentLimit() {
        Mode mode = manualOverride != null ? manualOverride : automaticMode;
        return limitFor(mode);
    }

    private int limitFor(Mode mode) {
        return mode == Mode.THROTTLED ? 1 : normalLimit;
    }

    private void removeAt(int index) {
        for (int i = index; i < pendingCount - 1; i++) {
            pending[i] = pending[i + 1];
        }
        pending[--pendingCount] = null;
    }
}
