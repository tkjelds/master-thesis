@ThermostaticBuildOrchestrator.ThreadSafe
public class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    static interface Job {
        void run();
        enum Mode { NORMAL, THROTTLED };
    }

    private final int capacity;
    private final int normalLimit;
    private final int lowThreshold;
    private final int highThreshold;
    private long nextJobId = 0;
    private volatile Job.Mode mode = Job.Mode.NORMAL;
    private volatile int currentLimit;
    private final Object lock = new Object();
    private final LinkedList<Job> queue;

    public ThermostaticBuildOrchestrator(int capacity, int normalLimit, int lowThreshold, int highThreshold) {
        if (capacity <= 0 || normalLimit <= 0 || lowThreshold < 0 || highThreshold > 100 ||
            lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.capacity = capacity;
        this.normalLimit = normalLimit;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        this.currentLimit = normalLimit;
        this.queue = new LinkedList<>();
    }

    public long submit(Job job) {
        if (job == null) {
            throw new NullPointerException("Job cannot be null");
        }
        synchronized (lock) {
            if (isClosed() || queue.size() == capacity) {
                throw new IllegalStateException("Cannot submit job");
            }
            long jobId = nextJobId++;
            queue.addLast(job);
            lock.notifyAll();
            return jobId;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            Iterator<Job> it = queue.iterator();
            while (it.hasNext()) {
                Job job = it.next();
                long currentId = 0;
                if (job instanceof CancellableJob) {
                    currentId = ((CancellableJob) job).getJobId();
                }
                if (currentId == jobId) {
                    it.remove();
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public void updateLoad(int load) {
        synchronized (lock) {
            if (load < 0 || load > 100) {
                throw new IllegalArgumentException("Invalid load value");
            }
            if (load >= highThreshold) {
                mode = Job.Mode.THROTTLED;
                currentLimit = 1;
            } else if (load <= lowThreshold) {
                mode = Job.Mode.NORMAL;
                currentLimit = normalLimit;
            } else {
                mode = jobMode();
            }
            lock.notifyAll();
        }
    }

    public void overrideMode(Job.Mode mode) {
        synchronized (lock) {
            this.mode = mode;
            currentLimit = mode == Job.Mode.THROTTLED ? 1 : normalLimit;
            lock.notifyAll();
        }
    }

    public void clearOverride() {
        synchronized (lock) {
            overrideMode(jobMode());
        }
    }

    private Job.Mode jobMode() {
        return mode;
    }

    public boolean runNext() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (isClosed()) {
                    return false;
                }
                Job job = queue.pollFirst();
                if (job != null && currentLimit > 0) {
                    currentLimit--;
                    lock.notifyAll();
                    try {
                        job.run();
                    } catch (RuntimeException e) {
                        // Propagate callback RuntimeException after releasing capacity
                        throw new RuntimeException("Callback failed", e);
                    } finally {
                        synchronized (lock) {
                            currentLimit++;
                        }
                    }
                    return true;
                } else {
                    lock.wait();
                }
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (!isClosed()) {
                queue.clear();
                currentLimit = 0;
                mode = Job.Mode.NORMAL;
                lock.notifyAll();
            }
        }
    }

    public synchronized State snapshot() {
        return new State(mode, queue.stream().map(job -> ((CancellableJob) job).getJobId()).toList(), currentLimit, capacity, isClosed());
    }

    private boolean isClosed() {
        return currentLimit == 0;
    }
}

@CancellableJob.ThreadSafe
class CancellableJob implements Job {
    @interface ThreadSafe {}

    private final long jobId;

    public CancellableJob(long jobId) {
        this.jobId = jobId;
    }

    @Override
    public void run() {
        // Implement job logic here
    }

    @Override
    public Job.Mode Mode() {
        return null;
    }

    public long getJobId() {
        return jobId;
    }
}

@State.ThreadSafe
class State {
    @interface ThreadSafe {}

    private final Job.Mode mode;
    private final List<Long> pendingIds;
    private final int runningCount;
    private final int limit;
    private final boolean closed;

    public State(Job.Mode mode, List<Long> pendingIds, int runningCount, int limit, boolean closed) {
        this.mode = mode;
        this.pendingIds = pendingIds;
        this.runningCount = runningCount;
        this.limit = limit;
        this.closed = closed;
    }

    // Getters for state fields
}
