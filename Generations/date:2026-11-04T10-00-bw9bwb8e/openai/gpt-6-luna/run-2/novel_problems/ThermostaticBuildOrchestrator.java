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
        public final Mode effectiveMode;
        public final Mode overrideMode;
        public final int runningCount;
        public final int admissionLimit;
        public final boolean closed;
        public final long[] pendingJobIds;

        private Snapshot(
                Mode automaticMode,
                Mode effectiveMode,
                Mode overrideMode,
                int runningCount,
                int admissionLimit,
                boolean closed,
                long[] pendingJobIds) {
            this.automaticMode = automaticMode;
            this.effectiveMode = effectiveMode;
            this.overrideMode = overrideMode;
            this.runningCount = runningCount;
            this.admissionLimit = admissionLimit;
            this.closed = closed;
            this.pendingJobIds = pendingJobIds;
        }

        public long[] getPendingJobIds() {
            return pendingJobIds.clone();
        }
    }

    private static final class QueueEntry {
        final long id;
        final Job job;

        QueueEntry(long id, Job job) {
            this.id = id;
            this.job = job;
        }
    }

    private final Object lock = new Object();
    private final QueueEntry[] queue;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;

    private int head;
    private int pendingCount;
    private int runningCount;
    private long nextJobId = 1L;
    private boolean jobIdsExhausted;
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

        this.queue = new QueueEntry[queueCapacity];
        this.normalConcurrencyLimit = normalConcurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("job");
        }

        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("orchestrator is closed");
            }
            if (pendingCount == queue.length) {
                throw new IllegalStateException("pending queue is full");
            }
            if (jobIdsExhausted) {
                throw new IllegalStateException("no positive job IDs remain");
            }

            long id = nextJobId;
            if (nextJobId == Long.MAX_VALUE) {
                jobIdsExhausted = true;
            } else {
                nextJobId++;
            }

            int tail = indexAt(pendingCount);
            queue[tail] = new QueueEntry(id, job);
            pendingCount++;
            lock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (int i = 0; i < pendingCount; i++) {
                int index = indexAt(i);
                if (queue[index].id == jobId) {
                    for (int j = i; j < pendingCount - 1; j++) {
                        queue[indexAt(j)] = queue[indexAt(j + 1)];
                    }
                    queue[indexAt(pendingCount - 1)] = null;
                    pendingCount--;
                    lock.notifyAll();
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

        synchronized (lock) {
            if (automaticMode == Mode.NORMAL && load >= highThreshold) {
                automaticMode = Mode.THROTTLED;
            } else if (automaticMode == Mode.THROTTLED && load <= lowThreshold) {
                automaticMode = Mode.NORMAL;
            }
            lock.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }

        synchronized (lock) {
            manualOverride = mode;
            lock.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (lock) {
            manualOverride = null;
            lock.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        QueueEntry entry;

        synchronized (lock) {
            while (true) {
                if (pendingCount == 0 && closed) {
                    return false;
                }

                if (pendingCount > 0 && runningCount < effectiveLimit()) {
                    entry = queue[head];
                    queue[head] = null;
                    head = (head + 1) % queue.length;
                    pendingCount--;
                    runningCount++;
                    lock.notifyAll();
                    break;
                }

                lock.wait();
            }
        }

        try {
            entry.job.run();
        } finally {
            synchronized (lock) {
                runningCount--;
                lock.notifyAll();
            }
        }
        return true;
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] pendingIds = new long[pendingCount];
            for (int i = 0; i < pendingCount; i++) {
                pendingIds[i] = queue[indexAt(i)].id;
            }

            Mode effective = effectiveMode();
            return new Snapshot(
                    automaticMode,
                    effective,
                    manualOverride,
                    runningCount,
                    limitFor(effective),
                    closed,
                    pendingIds);
        }
    }

    private Mode effectiveMode() {
        return manualOverride != null ? manualOverride : automaticMode;
    }

    private int effectiveLimit() {
        return limitFor(effectiveMode());
    }

    private int limitFor(Mode mode) {
        return mode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
    }

    private int indexAt(int offset) {
        return (int) (((long) head + offset) % queue.length);
    }
}
