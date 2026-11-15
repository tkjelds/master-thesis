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

    public record Snapshot(
            Mode automaticMode,
            Mode overrideMode,
            Mode effectiveMode,
            long[] pendingIds,
            int runningCount,
            int limit,
            boolean closed) {

        public Snapshot {
            if (pendingIds == null) {
                throw new NullPointerException("pendingIds");
            }
            pendingIds = copyIds(pendingIds);
        }

        @Override
        public long[] pendingIds() {
            return copyIds(pendingIds);
        }

        private static long[] copyIds(long[] source) {
            long[] copy = new long[source.length];
            for (int i = 0; i < source.length; i++) {
                copy[i] = source[i];
            }
            return copy;
        }
    }

    private static final class PendingJob {
        final long id;
        final Job job;
        PendingJob next;

        PendingJob(long id, Job job) {
            this.id = id;
            this.job = job;
        }
    }

    private final Object monitor = new Object();
    private final int queueCapacity;
    private final int normalLimit;
    private final int low;
    private final int high;

    private Mode automaticMode = Mode.NORMAL;
    private Mode overrideMode;
    private PendingJob head;
    private PendingJob tail;
    private int pendingCount;
    private int runningCount;
    private long lastJobId;
    private boolean closed;

    public ThermostaticBuildOrchestrator(
            int queueCapacity, int normalLimit, int low, int high) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("Queue capacity must be positive");
        }
        if (normalLimit <= 0) {
            throw new IllegalArgumentException("Normal limit must be positive");
        }
        if (low < 0 || low >= high || high > 100) {
            throw new IllegalArgumentException(
                    "Thresholds must satisfy 0 <= low < high <= 100");
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
            if (lastJobId == 0x7fffffffffffffffL) {
                throw new IllegalStateException("Job ID space exhausted");
            }

            long id = lastJobId + 1;
            PendingJob entry = new PendingJob(id, job);
            if (tail == null) {
                head = entry;
            } else {
                tail.next = entry;
            }
            tail = entry;
            lastJobId = id;
            pendingCount++;
            monitor.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (monitor) {
            PendingJob previous = null;
            PendingJob current = head;

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
                    current.next = null;
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
        PendingJob claimed;

        synchronized (monitor) {
            while (head == null || runningCount >= admissionLimitLocked()) {
                // Drained means that neither pending nor running jobs remain.
                if (closed && head == null && runningCount == 0) {
                    return false;
                }
                monitor.wait();
            }

            claimed = head;
            head = claimed.next;
            claimed.next = null;
            if (head == null) {
                tail = null;
            }
            pendingCount--;
            runningCount++;
            monitor.notifyAll();
        }

        try {
            claimed.job.run();
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
            PendingJob current = head;
            for (int i = 0; i < ids.length; i++) {
                ids[i] = current.id;
                current = current.next;
            }

            return new Snapshot(
                    automaticMode,
                    overrideMode,
                    effectiveModeLocked(),
                    ids,
                    runningCount,
                    admissionLimitLocked(),
                    closed);
        }
    }

    private Mode effectiveModeLocked() {
        return overrideMode == null ? automaticMode : overrideMode;
    }

    private int admissionLimitLocked() {
        return effectiveModeLocked() == Mode.THROTTLED ? 1 : normalLimit;
    }
}
