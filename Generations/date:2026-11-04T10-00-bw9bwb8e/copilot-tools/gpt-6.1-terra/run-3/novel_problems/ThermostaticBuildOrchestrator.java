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
        private final Mode automaticMode;
        private final Mode overrideMode;
        private final Mode effectiveMode;
        private final long[] pendingJobIds;
        private final int runningCount;
        private final int limit;
        private final boolean closed;

        private Snapshot(
                Mode automaticMode,
                Mode overrideMode,
                Mode effectiveMode,
                long[] pendingJobIds,
                int runningCount,
                int limit,
                boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingJobIds = pendingJobIds;
            this.runningCount = runningCount;
            this.limit = limit;
            this.closed = closed;
        }

        public Mode getAutomaticMode() {
            return automaticMode;
        }

        public Mode getOverrideMode() {
            return overrideMode;
        }

        public Mode getEffectiveMode() {
            return effectiveMode;
        }

        public long[] getPendingJobIds() {
            long[] copy = new long[pendingJobIds.length];
            for (int i = 0; i < pendingJobIds.length; i++) {
                copy[i] = pendingJobIds[i];
            }
            return copy;
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

    private final Object monitor = new Object();
    private final Job[] pendingJobs;
    private final long[] pendingIds;
    private final int normalLimit;
    private final int low;
    private final int high;

    private int head;
    private int pendingCount;
    private int runningCount;
    private long nextJobId = 1;
    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;
    private boolean closed;

    public ThermostaticBuildOrchestrator(
            int queueCapacity, int normalLimit, int low, int high) {
        if (queueCapacity <= 0 || normalLimit <= 0) {
            throw new IllegalArgumentException("Capacity and limit must be positive");
        }
        if (low < 0 || low >= high || high > 100) {
            throw new IllegalArgumentException("Thresholds must satisfy 0 <= low < high <= 100");
        }
        this.pendingJobs = new Job[queueCapacity];
        this.pendingIds = new long[queueCapacity];
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
            if (pendingCount == pendingJobs.length) {
                throw new IllegalStateException("Pending queue is full");
            }
            if (nextJobId == 0) {
                throw new IllegalStateException("Job IDs exhausted");
            }

            long id = nextJobId;
            nextJobId = id == Long.MAX_VALUE ? 0 : id + 1;
            int index = indexAt(pendingCount);
            pendingJobs[index] = job;
            pendingIds[index] = id;
            pendingCount++;
            monitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            int offset = 0;
            while (offset < pendingCount && pendingIds[indexAt(offset)] != jobId) {
                offset++;
            }
            if (offset == pendingCount) {
                return false;
            }

            while (offset + 1 < pendingCount) {
                int current = indexAt(offset);
                int next = indexAt(offset + 1);
                pendingJobs[current] = pendingJobs[next];
                pendingIds[current] = pendingIds[next];
                offset++;
            }
            int last = indexAt(pendingCount - 1);
            pendingJobs[last] = null;
            pendingIds[last] = 0;
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
        Job job;
        synchronized (monitor) {
            while (pendingCount == 0 || runningCount >= effectiveLimit()) {
                if (closed && pendingCount == 0 && runningCount == 0) {
                    return false;
                }
                monitor.wait();
            }

            job = pendingJobs[head];
            pendingJobs[head] = null;
            pendingIds[head] = 0;
            head++;
            if (head == pendingJobs.length) {
                head = 0;
            }
            pendingCount--;
            runningCount++;
            monitor.notifyAll();
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
            for (int i = 0; i < pendingCount; i++) {
                ids[i] = pendingIds[indexAt(i)];
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

    private int indexAt(int offset) {
        return (int) (((long) head + offset) % pendingJobs.length);
    }

    private Mode effectiveMode() {
        return overrideMode == null ? automaticMode : overrideMode;
    }

    private int effectiveLimit() {
        return effectiveMode() == Mode.THROTTLED ? 1 : normalLimit;
    }
}
