@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}


    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class ScheduleSnapshot {
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flusherActive;
        private final RuntimeException failure;
        private final long[] starts;
        private final long[] ends;
        private final long[] ids;

        private ScheduleSnapshot(long committedRevision, long durableRevision, boolean flusherActive,
                                 RuntimeException failure, long[] srcStarts, long[] srcEnds,
                                 long[] srcIds, int count) {
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherActive = flusherActive;
            this.failure = failure;
            this.starts = copyPrefix(srcStarts, count);
            this.ends = copyPrefix(srcEnds, count);
            this.ids = copyPrefix(srcIds, count);
        }

        private static long[] copyPrefix(long[] src, int count) {
            long[] dst = new long[count];
            System.arraycopy(src, 0, dst, 0, count);
            return dst;
        }

        public long committedRevision() {
            return committedRevision;
        }

        public long durableRevision() {
            return durableRevision;
        }

        public boolean isFlusherActive() {
            return flusherActive;
        }

        public RuntimeException failure() {
            return failure;
        }

        public int reservationCount() {
            return ids.length;
        }

        public long[] reservationStarts() {
            return copyPrefix(starts, starts.length);
        }

        public long[] reservationEnds() {
            return copyPrefix(ends, ends.length);
        }

        public long[] reservationIds() {
            return copyPrefix(ids, ids.length);
        }
    }

    private final ScheduleSink sink;

    // All fields below are guarded by the monitor of this object.
    private long[] starts = new long[16];
    private long[] ends = new long[16];
    private long[] ids = new long[16];
    private int count = 0;
    private long committedRevision = 0L;
    private long durableRevision = 0L;
    private long nextId = 1L;
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
        synchronized (this) {
            checkNotFailedLocked();
            if (committedRevision == Long.MAX_VALUE || nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("counter overflow");
            }
            for (int i = 0; i < count; i++) {
                if (start < ends[i] && starts[i] < end) {
                    return -1L;
                }
            }
            ensureCapacityLocked();
            long id = nextId;
            nextId++;
            starts[count] = start;
            ends[count] = end;
            ids[count] = id;
            count++;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (this) {
            checkNotFailedLocked();
            int idx = indexOfLocked(reservationId);
            if (idx < 0) {
                return false;
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            int last = count - 1;
            starts[idx] = starts[last];
            ends[idx] = ends[last];
            ids[idx] = ids[last];
            count = last;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        long target;
        synchronized (this) {
            if (flusherThread == Thread.currentThread()) {
                throw new IllegalStateException("recursive flush from sink is not permitted");
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
                    break;
                }
                wait();
            }
            flusherActive = true;
            flusherThread = Thread.currentThread();
        }
        runFlusher(target);
    }

    public ScheduleSnapshot snapshot() {
        synchronized (this) {
            return buildSnapshotLocked(flusherActive, failure);
        }
    }

    private void runFlusher(long target) {
        try {
            while (true) {
                ScheduleSnapshot snap;
                synchronized (this) {
                    if (durableRevision >= target) {
                        return;
                    }
                    snap = buildSnapshotLocked(true, null);
                }
                try {
                    sink.persist(snap);
                } catch (RuntimeException e) {
                    publishFailure(e);
                    throw e;
                } catch (Error e) {
                    RuntimeException wrapped = new IllegalStateException("sink failed", e);
                    publishFailure(wrapped);
                    throw e;
                }
                synchronized (this) {
                    if (snap.committedRevision() > durableRevision) {
                        durableRevision = snap.committedRevision();
                    }
                    notifyAll();
                }
            }
        } finally {
            synchronized (this) {
                flusherActive = false;
                flusherThread = null;
                notifyAll();
            }
        }
    }

    private void publishFailure(RuntimeException e) {
        synchronized (this) {
            failure = e;
            notifyAll();
        }
    }

    private void checkNotFailedLocked() {
        if (failure != null) {
            throw new IllegalStateException("persistence failed", failure);
        }
    }

    private int indexOfLocked(long id) {
        for (int i = 0; i < count; i++) {
            if (ids[i] == id) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacityLocked() {
        if (count < starts.length) {
            return;
        }
        int newLen = starts.length * 2;
        long[] ns = new long[newLen];
        long[] ne = new long[newLen];
        long[] ni = new long[newLen];
        System.arraycopy(starts, 0, ns, 0, count);
        System.arraycopy(ends, 0, ne, 0, count);
        System.arraycopy(ids, 0, ni, 0, count);
        starts = ns;
        ends = ne;
        ids = ni;
    }

    private ScheduleSnapshot buildSnapshotLocked(boolean active, RuntimeException fail) {
        return new ScheduleSnapshot(committedRevision, durableRevision, active, fail,
                starts, ends, ids, count);
    }
}
