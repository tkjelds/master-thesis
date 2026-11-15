public interface Job {
    void run();
}

enum Mode {
    NORMAL,
    THROTTLED
}

@ThermostaticBuildOrchestrator.ThreadSafe
class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    private final int capacity;
    private final int limit;
    private final int low;
    private final int high;
    private final Job[] jobs;
    private final boolean[] running;
    private final Object lock = new Object();
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    private boolean closed = false;

    public ThermostaticBuildOrchestrator(int capacity, int limit, int low, int high) {
        if (capacity <= 0 || limit <= 0 || low < 0 || high > 100 || low >= high) {
            throw new IllegalArgumentException();
        }
        this.capacity = capacity;
        this.limit = limit;
        this.low = low;
        this.high = high;
        this.jobs = new Job[capacity];
        this.running = new boolean[capacity];
    }

    public long submit(Job job) throws IllegalStateException {
        synchronized (lock) {
            if (closed || count == capacity) {
                throw new IllegalStateException();
            }
            if (job == null) {
                return -1;
            }
            int jobId = tail++;
            jobs[jobId % capacity] = job;
            count++;
            lock.notifyAll();
            return jobId;
        }
    }

    public boolean cancel(long jobId) {
        synchronized (lock) {
            if (jobId < 0 || jobId >= count) {
                return false;
            }
            jobs[(int) jobId % capacity] = null;
            running[(int) jobId % capacity] = false;
            count--;
            lock.notifyAll();
            return true;
        }
    }

    public void updateLoad(int load) {
        synchronized (lock) {
            if (load < 0 || load > 100) {
                return;
            }
            Mode newMode = load >= high ? Mode.THROTTLED : low <= load ? Mode.NORMAL : currentMode();
            overrideMode(newMode);
        }
    }

    private Mode currentMode() {
        synchronized (lock) {
            int runningCount = 0;
            for (boolean isRunning : running) {
                if (isRunning) {
                    runningCount++;
                }
            }
            return runningCount > limit ? Mode.THROTTLED : Mode.NORMAL;
        }
    }

    public void overrideMode(Mode mode) {
        synchronized (lock) {
            for (int i = 0; i < capacity; i++) {
                running[i] = mode == Mode.THROTTLED;
            }
        }
    }

    public void clearOverride() {
        synchronized (lock) {
            overrideMode(currentMode());
        }
    }

    public boolean runNext() throws InterruptedException {
        synchronized (lock) {
            while (count == 0 && !closed) {
                lock.wait();
            }
            if (closed || count == 0) {
                return false;
            }
            int jobId = head++;
            Job job = jobs[jobId % capacity];
            running[jobId % capacity] = true;
            count--;
            lock.notifyAll();
            try {
                job.run();
            } catch (RuntimeException e) {
                running[jobId % capacity] = false;
                lock.notifyAll();
                throw e;
            } finally {
                running[jobId % capacity] = false;
                lock.notifyAll();
            }
            return true;
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public void snapshot() {
        synchronized (lock) {
            System.out.println("Mode: " + currentMode());
            System.out.print("Pending IDs: ");
            for (int i = 0; i < count; i++) {
                System.out.print(head + i);
                if (i < count - 1) {
                    System.out.print(",");
                }
            }
            System.out.println();
            System.out.println("Running Count: " + count);
            System.out.println("Limit: " + limit);
            System.out.println("Closed: " + closed);
        }
    }
}
