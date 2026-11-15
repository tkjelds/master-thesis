interface Job {
    void run();
}

enum Mode {
    NORMAL,
    THROTTLED
}

@ThermostaticBuildOrchestrator.ThreadSafe
public class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    private final int queueCapacity;
    private final int concurrencyLimit;
    private final int lowThreshold;
    private final int highThreshold;

    private volatile Mode mode = Mode.NORMAL;
    private final Object lock = new Object();
    private final Job[] queue;
    private int head = 0;
    private int tail = 0;
    private int runningCount = 0;

    public ThermostaticBuildOrchestrator(int queueCapacity, int concurrencyLimit, int lowThreshold, int highThreshold) {
        if (queueCapacity <= 0 || concurrencyLimit <= 0 || lowThreshold < 0 || highThreshold > 100 || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.queueCapacity = queueCapacity;
        this.concurrencyLimit = concurrencyLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        queue = new Job[queueCapacity];
    }

    public long submit(Job job) throws IllegalStateException {
        synchronized (lock) {
            if (isClosed()) {
                throw new IllegalStateException("ThermostaticBuildOrchestrator is closed");
            }
            if (isFull()) {
                throw new IllegalStateException("Queue is full");
            }
            if (job == null) {
                throw new IllegalArgumentException("Job cannot be null");
            }
        }
        long jobId = submitInternal(job);
        synchronized (lock) {
            checkMode();
            notifyThreads();
        }
        return jobId;
    }

    private long submitInternal(Job job) {
        int i = tail++;
        queue[i % queueCapacity] = job;
        return i;
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            if (isClosed()) {
                return false;
            }
            int index = (int) jobId % queueCapacity;
            if (index == head || index > tail) {
                return false;
            }
            queue[index] = null;
        }
        synchronized (lock) {
            notifyThreads();
        }
        return true;
    }

    public void updateLoad(int load) {
        synchronized (lock) {
            if (load < 0 || load > 100) {
                throw new IllegalArgumentException("Load must be between 0 and 100");
            }
        }
        updateLoadInternal(load);
    }

    private void updateLoadInternal(int load) {
        Mode newMode = mode;
        if (load >= highThreshold) {
            newMode = Mode.THROTTLED;
        } else if (load <= lowThreshold) {
            newMode = Mode.NORMAL;
        }
        mode = newMode;
    }

    public void overrideMode(Mode newMode) {
        synchronized (lock) {
            mode = newMode;
        }
        notifyThreads();
    }

    public void clearOverride() {
        synchronized (lock) {
            mode = Mode.NORMAL;
        }
        notifyThreads();
    }

    public boolean runNext() {
        Job job = null;
        synchronized (lock) {
            while (isClosed() || isEmpty()) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            job = queue[head++ % queueCapacity];
        }
        if (job != null) {
            try {
                job.run();
            } catch (RuntimeException e) {
                throw e;
            } finally {
                synchronized (lock) {
                    incrementRunningCount(-1);
                    lock.notifyAll();
                }
            }
        }
        return job != null;
    }

    private void incrementRunningCount(int delta) {
        runningCount += delta;
    }

    public void close() {
        synchronized (lock) {
            if (!isClosed()) {
                closeInternal();
            }
        }
    }

    private void closeInternal() {
        incrementRunningCount(-runningCount);
        lock.notifyAll();
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(mode, head, tail, runningCount, concurrencyLimit, isClosed());
        }
    }

    private boolean isEmpty() {
        return head == tail;
    }

    private boolean isFull() {
        return (tail - head + queueCapacity) % queueCapacity == 0;
    }

    private boolean isClosed() {
        return runningCount < 0;
    }

    public static class Snapshot {
        private final Mode mode;
        private final int head;
        private final int tail;
        private final int runningCount;
        private final int limit;
        private final boolean closed;

        public Snapshot(Mode mode, int head, int tail, int runningCount, int limit, boolean closed) {
            this.mode = mode;
            this.head = head;
            this.tail = tail;
            this.runningCount = runningCount;
            this.limit = limit;
            this.closed = closed;
        }

        public Mode getMode() {
            return mode;
        }

        public int[] getPendingIds() {
            synchronized (lock) {
                int size = tail - head;
                int[] ids = new int[size];
                for (int i = 0; i < size; i++) {
                    ids[i] = head + i;
                }
                return ids;
            }
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

    private void notifyThreads() {
        lock.notifyAll();
    }
}
