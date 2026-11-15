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
        private final int admissionLimit;
        private final boolean closed;

        private Snapshot(
                Mode automaticMode,
                Mode overrideMode,
                Mode effectiveMode,
                long[] pendingJobIds,
                int runningCount,
                int admissionLimit,
                boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingJobIds = pendingJobIds;
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
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

        public int getAdmissionLimit() {
            return admissionLimit;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private static final class Entry {
        private final long id;
        private final Job job;
        private Entry next;

        private Entry(long id, Job job) {
            this.id = id;
            this.job = job;
        }
    }

    private final Object monitor = new Object();
    private final int queueCapacity;
    private final int normalLimit;
    private final int low;
    private final int high;

    private Entry head;
    private Entry tail;
    private int pendingCount;
    private int runningCount;
    private long nextId = 1;
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
        this.queueCapacity = queueCapacity;
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
            if (pendingCount == queueCapacity) {
                throw new IllegalStateException("Pending queue is full");
            }
            if (nextId == 0) {
                throw new IllegalStateException("Job IDs exhausted");
            }

            long id = nextId;
            Entry entry = new Entry(id, job);
            if (tail == null) {
                head = entry;
            } else {
                tail.next = entry;
            }
            tail = entry;
            pendingCount++;
            nextId = id == Long.MAX_VALUE ? 0 : id + 1;
            monitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            Entry previous = null;
            Entry current = head;
            while (current != null) {
                if (current.id == jobId) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    if (tail == current) {
                        tail = previous;
                    }
                    pendingCount--;
                    monitor.notifyAll();
                    return true;
                }
                previous = current;
                current = current.next;
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
                if (head != null && runningCount < admissionLimit()) {
                    Entry entry = head;
                    head = entry.next;
                    if (head == null) {
                        tail = null;
                    }
                    pendingCount--;
                    runningCount++;
                    job = entry.job;
                    break;
                }
                if (closed && head == null && runningCount == 0) {
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
            long[] pendingJobIds = new long[pendingCount];
            Entry current = head;
            for (int i = 0; i < pendingJobIds.length; i++) {
                pendingJobIds[i] = current.id;
                current = current.next;
            }
            Mode effectiveMode = effectiveMode();
            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effectiveMode,
                    pendingJobIds,
                    runningCount,
                    effectiveMode == Mode.THROTTLED ? 1 : normalLimit,
                    closed);
        }
    }

    private Mode effectiveMode() {
        return overrideMode == null ? automaticMode : overrideMode;
    }

    private int admissionLimit() {
        return effectiveMode() == Mode.THROTTLED ? 1 : normalLimit;
    }
}
