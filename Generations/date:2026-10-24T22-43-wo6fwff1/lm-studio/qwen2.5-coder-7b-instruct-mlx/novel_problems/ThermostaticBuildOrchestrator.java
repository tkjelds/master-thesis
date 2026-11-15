```java
public class ThermostaticBuildOrchestrator {
    private final int queueCapacity;
    private final int normalConcurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private final Job[] queue;
    private int head = 0;
    private int tail = 0;
    private int runningCount = 0;
    private Mode mode = Mode.NORMAL;
    private int currentLimit;
    private boolean closed;

    public ThermostaticBuildOrchestrator(int queueCapacity, int normalConcurrencyLimit, int lowThreshold, int highThreshold) {
        if (queueCapacity <= 0 || normalConcurrencyLimit <= 0 || lowThreshold < 0 || highThreshold > 100 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.queueCapacity = queueCapacity;
        this.normalConcurrencyLimit = normalConcurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.queue = new Job[queueCapacity];
        this.currentLimit = normalConcurrencyLimit;
    }

    public long submit(Job job) throws IllegalStateException {
        if (job == null || closed) {
            throw new IllegalArgumentException("Invalid job or closed state");
        }
        synchronized (this) {
            while (isFull()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Thread interrupted", e);
                }
            }
            queue[tail] = job;
            tail = (tail + 1) % queueCapacity;
        }
        notifyAll();
        return generateJobId();
    }

    private long generateJobId() {
        synchronized (this) {
            return head;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (this) {
            for (int i = head; i != tail; i = (i + 1) % queueCapacity) {
                if (getJobId(queue[i]) == jobId) {
                    removeJob(i);
                    return true;
                }
            }
        }
        return false;
    }

    private void removeJob(int index) {
        queue[index] = null;
        head = (index + 1) % queueCapacity;
    }

    public void updateLoad(int load) {
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("Invalid load value");
        }
        synchronized (this) {
            mode = load >= highThreshold ? Mode.THROTTLED : (load <= lowThreshold ? Mode.NORMAL : mode);
            currentLimit = mode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
        }
        notifyAll();
    }

    public void overrideMode(Mode mode) {
        if (mode != Mode.NORMAL && mode != Mode.THROTTLED) {
            throw new IllegalArgumentException("Invalid mode");
        }
        synchronized (this) {
            this.mode = mode;
            currentLimit = mode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
        }
        notifyAll();
    }

    public void clearOverride() {
        synchronized (this) {
            mode = calculateMode();
            currentLimit = mode == Mode.THROTTLED ? 1 : normalConcurrencyLimit;
        }
        notifyAll();
    }

    private Mode calculateMode() {
        int load = estimateLoad();
        return load >= highThreshold ? Mode.THROTTLED : (load <= lowThreshold ? Mode.NORMAL : mode);
    }

    private int estimateLoad() {
        synchronized (this) {
            return (tail - head + queueCapacity) % queueCapacity;
        }
    }

    public boolean runNext() {
        synchronized (this) {
            while (isEmpty() || runningCount >= currentLimit) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        Job job = null;
        synchronized (this) {
            job = queue[head];
            head = (head + 1) % queueCapacity;
            runningCount++;
        }
        try {
            job.run();
        } catch (RuntimeException e) {
            synchronized (this) {
                runningCount--;
            }
            throw e;
        } finally {
            synchronized (this) {
                runningCount--;
                notifyAll();
            }
        }
        return true;
    }

    public void close() {
        synchronized (this) {
            closed = true;
        }
        notifyAll();
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(mode, getPendingIds(), runningCount, currentLimit, closed);
        }
    }

    private boolean isFull() {
        return (tail + 1) % queueCapacity == head;
    }

    private boolean isEmpty() {
        return head == tail;
    }

    public enum Mode {
        NORMAL, THROTTLED
    }

    public static class Snapshot {
        private final Mode mode;
        private final long[] pendingIds;
        private final int runningCount;
        private final int limit;
        private final boolean closed;

        public Snapshot(Mode mode, long[] pendingIds, int runningCount, int limit, boolean closed) {
            this.mode = mode;
            this.pendingIds = pendingIds;
            this.runningCount = runningCount;
            this.limit = limit;
            this.closed = closed;
        }

        public Mode getMode() {
            return mode;
        }

        public long[] getPendingIds() {
            return pendingIds.clone();
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

    @FunctionalInterface
    public interface Job {
        void run() throws RuntimeException;
    }
}
```
