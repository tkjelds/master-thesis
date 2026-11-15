import java.io.Serializable;

@ThermostaticBuildOrchestrator.ThreadSafe
public class ThermostaticBuildOrchestrator {
    @interface ThreadSafe {}

    private static final int MIN_CAPACITY = 1;
    private static final int MAX_LOAD = 100;

    private final JobQueue queue;
    private final ThreadMonitor monitor;
    private int loadThresholdLow;
    private int loadThresholdHigh;
    private Mode currentMode;
    private boolean closed;

    public ThermostaticBuildOrchestrator(int queueCapacity, int concurrencyLimit, int lowThreshold, int highThreshold) {
        if (queueCapacity < MIN_CAPACITY || concurrencyLimit <= 0 || lowThreshold < 0 || highThreshold > MAX_LOAD || lowThreshold >= highThreshold) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.queue = new JobQueue(queueCapacity);
        this.monitor = new ThreadMonitor(concurrencyLimit);
        this.loadThresholdLow = lowThreshold;
        this.loadThresholdHigh = highThreshold;
        this.currentMode = Mode.NORMAL;
        this.closed = false;
    }

    public long submit(Job job) throws IllegalStateException {
        synchronized (this) {
            if (closed || queue.isFull()) {
                throw new IllegalStateException("Cannot submit job");
            }
            if (job == null) {
                throw new IllegalArgumentException("Job cannot be null");
            }
            return queue.add(job);
        }
    }

    public boolean cancel(long jobId) {
        synchronized (this) {
            return queue.remove(jobId);
        }
    }

    public void updateLoad(int load) {
        synchronized (this) {
            if (!isWithinBounds(load)) {
                throw new IllegalArgumentException("Load out of bounds");
            }
            this.loadThresholdLow = load;
            this.loadThresholdHigh = load;
        }
    }

    public void overrideMode(Mode mode) {
        synchronized (this) {
            this.currentMode = mode;
        }
    }

    public void clearOverride() {
        synchronized (this) {
            this.currentMode = determineMode();
        }
    }

    public boolean runNext() throws InterruptedException {
        synchronized (this) {
            while (true) {
                if (closed && queue.isEmpty()) {
                    return false;
                }
                Job job = null;
                try {
                    job = queue.poll();
                } catch (InterruptedException e) {
                    if (!closed) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
                if (job != null) {
                    try {
                        monitor.enter();
                        job.run();
                        return true;
                    } catch (RuntimeException e) {
                        // Propagate exception after releasing monitor
                        throw e;
                    } finally {
                        monitor.exit();
                    }
                }
            }
        }
    }

    public void close() {
        synchronized (this) {
            this.closed = true;
            queue.clear();
            monitor.wakeAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(currentMode, queue.getPendingIds(), monitor.getRunningCount(), monitor.getLimit(), closed);
        }
    }

    private Mode determineMode() {
        if (loadThresholdLow <= monitor.getLoad() && monitor.getLoad() < loadThresholdHigh) {
            return Mode.NORMAL;
        } else if (monitor.getLoad() >= loadThresholdHigh) {
            return Mode.THROTTLED;
        } else {
            return Mode.NORMAL; // Default to normal if load is below low threshold
        }
    }

    private boolean isWithinBounds(int value) {
        return 0 <= value && value <= MAX_LOAD;
    }

    public interface Job extends Serializable {
        void run();
    }

    public enum Mode {
        NORMAL,
        THROTTLED
    }

    private static class JobQueue {
        private final Job[] jobs;
        private int head;
        private int tail;
        private int count;

        public JobQueue(int capacity) {
            this.jobs = new Job[capacity];
            this.head = 0;
            this.tail = 0;
            this.count = 0;
        }

        public long add(Job job) {
            jobs[tail] = job;
            tail = (tail + 1) % jobs.length;
            count++;
            return System.currentTimeMillis(); // Simple ID using current time
        }

        public Job poll() throws InterruptedException {
            while (count == 0) {
                wait();
            }
            Job job = jobs[head];
            head = (head + 1) % jobs.length;
            count--;
            notifyAll();
            return job;
        }

        public boolean remove(long jobId) {
            for (int i = 0; i < count; i++) {
                int index = (head + i) % jobs.length;
                if (jobs[index] != null && System.currentTimeMillis() == jobId) {
                    while (true) {
                        jobs[index] = null;
                        head = (head + 1) % jobs.length;
                        count--;
                        if (i == count - 1) {
                            break;
                        }
                        jobs[index] = null; // Clear the gap
                    }
                    notifyAll();
                    return true;
                }
            }
            return false;
        }

        public boolean isFull() {
            return count == jobs.length;
        }

        public long[] getPendingIds() {
            long[] ids = new long[count];
            for (int i = 0; i < count; i++) {
                ids[i] = System.currentTimeMillis(); // Simple ID using current time
            }
            return ids;
        }

        public void clear() {
            Arrays.fill(jobs, null);
            head = 0;
            tail = 0;
            count = 0;
        }

        public int size() {
            return count;
        }
    }

    private static class ThreadMonitor {
        private final boolean[] slots;
        private int count;

        public ThreadMonitor(int capacity) {
            this.slots = new boolean[capacity];
            for (int i = 0; i < capacity; i++) {
                slots[i] = false;
            }
            this.count = 0;
        }

        public synchronized void enter() throws InterruptedException {
            while (count >= slots.length) {
                wait();
            }
            int index = findOpenSlot();
            slots[index] = true;
            count++;
        }

        public synchronized void exit() {
            int index = findOccupiedSlot();
            slots[index] = false;
            count--;
            notifyAll();
        }

        public synchronized void wakeAll() {
            for (int i = 0; i < slots.length; i++) {
                slots[i] = true;
            }
            count = slots.length;
            notifyAll();
        }

        private int findOpenSlot() {
            for (int i = 0; i < slots.length; i++) {
                if (!slots[i]) {
                    return i;
                }
            }
            throw new IllegalStateException("No open slot found");
        }

        private int findOccupiedSlot() {
            for (int i = 0; i < slots.length; i++) {
                if (slots[i]) {
                    return i;
                }
            }
            throw new IllegalStateException("No occupied slot found");
        }

        public int getLimit() {
            return slots.length;
        }

        public int getRunningCount() {
            return count;
        }
    }

    private static class Snapshot implements Serializable {
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
            return pendingIds;
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
}
