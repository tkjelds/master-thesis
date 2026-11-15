@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}


    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    /** Immutable, detached copy of the reservation schedule at one committed revision. */
    public static final class ScheduleSnapshot {
        private final long revision;
        private final long[] ids;
        private final long[] starts;
        private final long[] ends;

        private ScheduleSnapshot(long revision, long[] srcIds, long[] srcStarts, long[] srcEnds, int n) {
            this.revision = revision;
            this.ids = new long[n];
            this.starts = new long[n];
            this.ends = new long[n];
            System.arraycopy(srcIds, 0, this.ids, 0, n);
            System.arraycopy(srcStarts, 0, this.starts, 0, n);
            System.arraycopy(srcEnds, 0, this.ends, 0, n);
        }

        public long getRevision() {
            return revision;
        }

        public int size() {
            return ids.length;
        }

        public long[] getIds() {
            return ids.clone();
        }

        public long[] getStarts() {
            return starts.clone();
        }

        public long[] getEnds() {
            return ends.clone();
        }
    }

    /** Coherent, detached report of the context's state. */
    public static final class ContextSnapshot {
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flusherActive;
        private final RuntimeException failure;
        private final ScheduleSnapshot schedule;

        private ContextSnapshot(long committedRevision, long durableRevision, boolean flusherActive,
                                RuntimeException failure, ScheduleSnapshot schedule) {
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherActive = flusherActive;
            this.failure = failure;
            this.schedule = schedule;
        }

        public long getCommittedRevision() {
            return committedRevision;
        }

        public long getDurableRevision() {
            return durableRevision;
        }

        public boolean isFlusherActive() {
            return flusherActive;
        }

        public RuntimeException getFailure() {
            return failure;
        }

        public ScheduleSnapshot getSchedule() {
            return schedule;
        }
    }

    private final ScheduleSink sink;
    private final Object lock = new Object();

    // All fields below are guarded by lock.
    private long committedRevision = 0L;
    private long durableRevision = 0L;
    private boolean flusherActive = false;
    private Thread flusherThread = null;
    private RuntimeException failure = null;
    private long nextId = 1L;
    private long[] ids = new long[4];
    private long[] starts = new long[4];
    private long[] ends = new long[4];
    private int count = 0;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
    }

    /**
     * Reserves [start, end). Returns the new positive ID, or -1 on conflict.
     * Throws IllegalArgumentException if start >= end, and IllegalStateException
     * if persistence has failed or a counter would overflow.
     */
    public long reserve(long start, long end) {
        synchronized (lock) {
            checkNotFailed();
            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }
            for (int i = 0; i < count; i++) {
                if (start < ends[i] && starts[i] < end) {
                    return -1L;
                }
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision overflow");
            }
            if (nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("reservation id overflow");
            }
            ensureCapacity();
            long id = nextId;
            nextId++;
            ids[count] = id;
            starts[count] = start;
            ends[count] = end;
            count++;
            committedRevision++;
            return id;
        }
    }

    /**
     * Cancels the reservation with the given ID. Returns true if it existed.
     * Throws IllegalArgumentException for non-positive IDs.
     */
    public boolean cancel(long reservationId) {
        synchronized (lock) {
            checkNotFailed();
            if (reservationId <= 0L) {
                throw new IllegalArgumentException("reservation id must be positive");
            }
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
                throw new IllegalStateException("committed revision overflow");
            }
            int tail = count - index - 1;
            if (tail > 0) {
                System.arraycopy(ids, index + 1, ids, index, tail);
                System.arraycopy(starts, index + 1, starts, index, tail);
                System.arraycopy(ends, index + 1, ends, index, tail);
            }
            count--;
            committedRevision++;
            return true;
        }
    }

    /**
     * Makes all commits up to the committed revision observed at entry durable,
     * or throws the retained persistence failure.
     */
    public void flush() {
        long target;
        synchronized (lock) {
            if (flusherThread == Thread.currentThread()) {
                throw new IllegalStateException("recursive flush from within the sink is not permitted");
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
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for flush", e);
                }
            }
        }

        // We hold the flusher role; persist outside the monitor.
        try {
            while (true) {
                ScheduleSnapshot snap;
                synchronized (lock) {
                    if (durableRevision >= target) {
                        break;
                    }
                    snap = copySchedule();
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
                    if (snap.getRevision() > durableRevision) {
                        durableRevision = snap.getRevision();
                    }
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

    /** Returns a coherent, detached view of the current state. */
    public ContextSnapshot snapshot() {
        synchronized (lock) {
            return new ContextSnapshot(committedRevision, durableRevision, flusherActive,
                    failure, copySchedule());
        }
    }

    // Caller must hold lock.
    private void checkNotFailed() {
        if (failure != null) {
            throw new IllegalStateException("persistence has failed", failure);
        }
    }

    // Caller must hold lock.
    private ScheduleSnapshot copySchedule() {
        return new ScheduleSnapshot(committedRevision, ids, starts, ends, count);
    }

    // Caller must hold lock.
    private void ensureCapacity() {
        if (count < ids.length) {
            return;
        }
        int newCap = Math.max(4, ids.length * 2);
        long[] newIds = new long[newCap];
        long[] newStarts = new long[newCap];
        long[] newEnds = new long[newCap];
        System.arraycopy(ids, 0, newIds, 0, count);
        System.arraycopy(starts, 0, newStarts, 0, count);
        System.arraycopy(ends, 0, newEnds, 0, count);
        ids = newIds;
        starts = newStarts;
        ends = newEnds;
    }
}
