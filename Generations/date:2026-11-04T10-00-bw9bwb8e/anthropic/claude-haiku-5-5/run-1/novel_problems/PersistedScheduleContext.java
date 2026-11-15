@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}


    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class ScheduleSnapshot {
        private final long revision;
        private final long[] starts;
        private final long[] ends;
        private final long[] ids;

        ScheduleSnapshot(long revision, long[] starts, long[] ends, long[] ids) {
            this.revision = revision;
            this.starts = starts;
            this.ends = ends;
            this.ids = ids;
        }

        public long revision() {
            return revision;
        }

        public int size() {
            return ids.length;
        }

        public long[] starts() {
            return starts.clone();
        }

        public long[] ends() {
            return ends.clone();
        }

        public long[] ids() {
            return ids.clone();
        }
    }

    public static final class ContextSnapshot {
        private final ScheduleSnapshot schedule;
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flusherActive;
        private final RuntimeException failure;

        ContextSnapshot(ScheduleSnapshot schedule, long committedRevision, long durableRevision,
                        boolean flusherActive, RuntimeException failure) {
            this.schedule = schedule;
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherActive = flusherActive;
            this.failure = failure;
        }

        public ScheduleSnapshot schedule() {
            return schedule;
        }

        public long committedRevision() {
            return committedRevision;
        }

        public long durableRevision() {
            return durableRevision;
        }

        public boolean flusherActive() {
            return flusherActive;
        }

        public RuntimeException failure() {
            return failure;
        }
    }

    private final Object lock = new Object();
    private final ScheduleSink sink;

    // All fields below are guarded by lock.
    private long[] starts = new long[16];
    private long[] ends = new long[16];
    private long[] ids = new long[16];
    private int count = 0;
    private long nextId = 1L;
    private long committedRevision = 0L;
    private long durableRevision = 0L;
    private boolean flusherActive = false;
    private Thread flusherThread = null;
    private RuntimeException failure = null;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        synchronized (lock) {
            checkFailureLocked();
            if (committedRevision == Long.MAX_VALUE || nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("counter overflow");
            }
            for (int i = 0; i < count; i++) {
                if (start < ends[i] && starts[i] < end) {
                    return -1L;
                }
            }
            ensureCapacityLocked(count + 1);
            long id = nextId++;
            starts[count] = start;
            ends[count] = end;
            ids[count] = id;
            count++;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (lock) {
            checkFailureLocked();
            int index = -1;
            for (int i = 0; i < count; i++) {
                if (ids[i] == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("counter overflow");
            }
            int last = count - 1;
            starts[index] = starts[last];
            ends[index] = ends[last];
            ids[index] = ids[last];
            count = last;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        long target;
        synchronized (lock) {
            if (flusherActive && Thread.currentThread() == flusherThread) {
                throw new IllegalStateException("flush must not be called from the sink");
            }
            target = committedRevision;
            while (true) {
                if (durableRevision >= target) {
                    return;
                }
                if (failure != null) {
                    throw failure;
                }
                if (!flusherActive) {
                    flusherActive = true;
                    flusherThread = Thread.currentThread();
                    break;
                }
                lock.wait();
            }
        }
        runFlusher(target);
    }

    public ContextSnapshot snapshot() {
        synchronized (lock) {
            return new ContextSnapshot(copyScheduleLocked(), committedRevision, durableRevision,
                    flusherActive, failure);
        }
    }

    private void runFlusher(long target) {
        try {
            while (true) {
                ScheduleSnapshot snap;
                synchronized (lock) {
                    if (durableRevision >= target) {
                        return;
                    }
                    snap = copyScheduleLocked();
                }
                try {
                    sink.persist(snap);
                } catch (RuntimeException e) {
                    synchronized (lock) {
                        failure = e;
                    }
                    throw e;
                }
                synchronized (lock) {
                    if (snap.revision() > durableRevision) {
                        durableRevision = snap.revision();
                    }
                    lock.notifyAll();
                }
            }
        } finally {
            synchronized (lock) {
                flusherActive = false;
                flusherThread = null;
                lock.notifyAll();
            }
        }
    }

    // Must be called while holding lock.
    private ScheduleSnapshot copyScheduleLocked() {
        long[] s = new long[count];
        long[] e = new long[count];
        long[] i = new long[count];
        System.arraycopy(starts, 0, s, 0, count);
        System.arraycopy(ends, 0, e, 0, count);
        System.arraycopy(ids, 0, i, 0, count);
        return new ScheduleSnapshot(committedRevision, s, e, i);
    }

    // Must be called while holding lock.
    private void checkFailureLocked() {
        if (failure != null) {
            throw new IllegalStateException("persistence failed", failure);
        }
    }

    // Must be called while holding lock.
    private void ensureCapacityLocked(int needed) {
        if (needed <= starts.length) {
            return;
        }
        int newLength = Math.max(needed, starts.length * 2);
        long[] s = new long[newLength];
        long[] e = new long[newLength];
        long[] i = new long[newLength];
        System.arraycopy(starts, 0, s, 0, count);
        System.arraycopy(ends, 0, e, 0, count);
        System.arraycopy(ids, 0, i, 0, count);
        starts = s;
        ends = e;
        ids = i;
    }
}
