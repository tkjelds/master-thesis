import java.lang.*;

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

    /** Detached, immutable view of the orchestrator state. */
    public static final class Snapshot {
        private final Mode automaticMode;
        private final Mode overrideMode; // null if none
        private final Mode effectiveMode;
        private final long[] pendingIds;
        private final int runningCount;
        private final int limit;
        private final boolean closed;

        Snapshot(Mode automaticMode, Mode overrideMode, Mode effectiveMode,
                 long[] pendingIds, int runningCount, int limit, boolean closed) {
            this.automaticMode = automaticMode;
            this.overrideMode = overrideMode;
            this.effectiveMode = effectiveMode;
            this.pendingIds = pendingIds;
            this.runningCount = runningCount;
            this.limit = limit;
            this.closed = closed;
        }

        public Mode automaticMode() { return automaticMode; }
        public Mode overrideMode() { return overrideMode; }
        public Mode effectiveMode() { return effectiveMode; }
        public long[] pendingIds() { return pendingIds.clone(); }
        public int pendingCount() { return pendingIds.length; }
        public int runningCount() { return runningCount; }
        public int limit() { return limit; }
        public boolean closed() { return closed; }
    }

    private static final class Entry {
        final long id;
        final Job job;
        Entry(long id, Job job) {
            this.id = id;
            this.job = job;
        }
    }

    private final Object lock = new Object();
    private final int normalLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private final Entry[] queue;
    private int head = 0;
    private int count = 0;
    private long nextId = 1;
    private int running = 0;
    private boolean closed = false;
    private Mode automaticMode = Mode.NORMAL;
    private Mode override = null;

    public ThermostaticBuildOrchestrator(int queueCapacity, int normalLimit, int low, int high) {
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be positive");
        if (normalLimit <= 0) throw new IllegalArgumentException("normalLimit must be positive");
        if (low < 0 || low >= high || high > 100) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low < high <= 100");
        }
        this.queue = new Entry[queueCapacity];
        this.normalLimit = normalLimit;
        this.lowThreshold = low;
        this.highThreshold = high;
    }

    // Must hold lock.
    private Mode effectiveModeLocked() {
        return override != null ? override : automaticMode;
    }

    // Must hold lock.
    private int limitLocked() {
        return effectiveModeLocked() == Mode.THROTTLED ? 1 : normalLimit;
    }

    public long submit(Job job) {
        if (job == null) throw new NullPointerException("job");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("closed");
            if (count == queue.length) throw new IllegalStateException("queue full");
            long id = nextId++;
            queue[(head + count) % queue.length] = new Entry(id, job);
            count++;
            lock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                int idx = (head + i) % queue.length;
                if (queue[idx].id == jobId) {
                    for (int j = i; j < count - 1; j++) {
                        int from = (head + j + 1) % queue.length;
                        int to = (head + j) % queue.length;
                        queue[to] = queue[from];
                    }
                    queue[(head + count - 1) % queue.length] = null;
                    count--;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) throw new IllegalArgumentException("load out of range");
        synchronized (lock) {
            if (load >= highThreshold) {
                automaticMode = Mode.THROTTLED;
            } else if (load <= lowThreshold) {
                automaticMode = Mode.NORMAL;
            }
            lock.notifyAll();
        }
    }

    public void overrideMode(Mode mode) {
        if (mode == null) throw new NullPointerException("mode");
        synchronized (lock) {
            override = mode;
            lock.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (lock) {
            override = null;
            lock.notifyAll();
        }
    }

    public boolean runNext() throws InterruptedException {
        Entry entry;
        synchronized (lock) {
            while (true) {
                if (count > 0 && running < limitLocked()) {
                    entry = queue[head];
                    queue[head] = null;
                    head = (head + 1) % queue.length;
                    count--;
                    running++;
                    lock.notifyAll();
                    break;
                }
                if (closed && count == 0) {
                    return false;
                }
                lock.wait();
            }
        }
        try {
            entry.job.run();
        } finally {
            synchronized (lock) {
                running--;
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
            long[] ids = new long[count];
            for (int i = 0; i < count; i++) {
                ids[i] = queue[(head + i) % queue.length].id;
            }
            return new Snapshot(automaticMode, override, effectiveModeLocked(),
                    ids, running, limitLocked(), closed);
        }
    }
}
