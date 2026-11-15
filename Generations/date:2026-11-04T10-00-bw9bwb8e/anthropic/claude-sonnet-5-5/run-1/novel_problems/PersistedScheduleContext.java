@ScheduleSnapshotHolderUnused.ThreadSafe
final class ScheduleSnapshotHolderUnused {
    @interface ThreadSafe {}
}

interface ScheduleSink {
    void persist(ScheduleSnapshot snapshot);
}

@ScheduleSnapshot.ThreadSafe
final class ScheduleSnapshot {
    @interface ThreadSafe {}

    private final long[] starts;
    private final long[] ends;
    private final long[] ids;
    private final long committedRevision;
    private final long durableRevision;
    private final boolean flusherActive;
    private final RuntimeException failure;

    ScheduleSnapshot(long[] starts, long[] ends, long[] ids, long committedRevision,
                     long durableRevision, boolean flusherActive, RuntimeException failure) {
        this.starts = starts;
        this.ends = ends;
        this.ids = ids;
        this.committedRevision = committedRevision;
        this.durableRevision = durableRevision;
        this.flusherActive = flusherActive;
        this.failure = failure;
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

    public long start(int index) {
        return starts[index];
    }

    public long end(int index) {
        return ends[index];
    }

    public long id(int index) {
        return ids[index];
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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ScheduleSnapshot{committed=").append(committedRevision)
          .append(", durable=").append(durableRevision)
          .append(", flusher=").append(flusherActive)
          .append(", failure=").append(failure)
          .append(", reservations=[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ids[i]).append(":[").append(starts[i]).append(',').append(ends[i]).append(')');
        }
        sb.append("]}");
        return sb.toString();
    }
}

@PersistedScheduleContext.ThreadSafe
public class PersistedScheduleContext {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final ScheduleSink sink;

    // Guarded by lock; sorted by start.
    private long[] starts = new long[8];
    private long[] ends = new long[8];
    private long[] ids = new long[8];
    private int size = 0;
    private long nextId = 1;
    private long committedRevision = 0;
    private long durableRevision = 0;
    private boolean flushing = false;
    private Thread flusherThread = null;
    private RuntimeException failure = null;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
    }

    private void checkFailureLocked() {
        if (failure != null) {
            throw new IllegalStateException("Persistence failed", failure);
        }
    }

    private ScheduleSnapshot buildSnapshotLocked() {
        long[] s = new long[size];
        long[] e = new long[size];
        long[] d = new long[size];
        System.arraycopy(starts, 0, s, 0, size);
        System.arraycopy(ends, 0, e, 0, size);
        System.arraycopy(ids, 0, d, 0, size);
        return new ScheduleSnapshot(s, e, d, committedRevision, durableRevision, flushing, failure);
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        synchronized (lock) {
            checkFailureLocked();
            if (committedRevision == Long.MAX_VALUE || nextId == Long.MAX_VALUE) {
                throw new ArithmeticException("counter overflow");
            }
            int pos = size;
            for (int i = 0; i < size; i++) {
                if (start < ends[i] && starts[i] < end) {
                    return -1;
                }
                if (pos == size && starts[i] > start) {
                    pos = i;
                }
            }
            if (size == starts.length) {
                int cap = size * 2;
                long[] ns = new long[cap];
                long[] ne = new long[cap];
                long[] nd = new long[cap];
                System.arraycopy(starts, 0, ns, 0, size);
                System.arraycopy(ends, 0, ne, 0, size);
                System.arraycopy(ids, 0, nd, 0, size);
                starts = ns;
                ends = ne;
                ids = nd;
            }
            int move = size - pos;
            if (move > 0) {
                System.arraycopy(starts, pos, starts, pos + 1, move);
                System.arraycopy(ends, pos, ends, pos + 1, move);
                System.arraycopy(ids, pos, ids, pos + 1, move);
            }
            long id = nextId++;
            starts[pos] = start;
            ends[pos] = end;
            ids[pos] = id;
            size++;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("reservationId must be positive");
        }
        synchronized (lock) {
            checkFailureLocked();
            int idx = -1;
            for (int i = 0; i < size; i++) {
                if (ids[i] == reservationId) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return false;
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new ArithmeticException("counter overflow");
            }
            int move = size - idx - 1;
            if (move > 0) {
                System.arraycopy(starts, idx + 1, starts, idx, move);
                System.arraycopy(ends, idx + 1, ends, idx, move);
                System.arraycopy(ids, idx + 1, ids, idx, move);
            }
            size--;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        ScheduleSnapshot toPersist;
        synchronized (lock) {
            long target = committedRevision;
            while (true) {
                if (durableRevision >= target) {
                    return;
                }
                if (failure != null) {
                    throw failure;
                }
                if (!flushing) {
                    flushing = true;
                    flusherThread = Thread.currentThread();
                    toPersist = buildSnapshotLocked();
                    break;
                }
                if (flusherThread == Thread.currentThread()) {
                    throw new IllegalStateException("Recursive flush from sink");
                }
                lock.wait();
            }
        }

        boolean success = false;
        RuntimeException retained = null;
        try {
            sink.persist(toPersist);
            success = true;
        } catch (RuntimeException ex) {
            retained = ex;
            throw ex;
        } finally {
            synchronized (lock) {
                if (success) {
                    if (toPersist.committedRevision() > durableRevision) {
                        durableRevision = toPersist.committedRevision();
                    }
                } else if (retained != null && failure == null) {
                    failure = retained;
                }
                flushing = false;
                flusherThread = null;
                lock.notifyAll();
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (lock) {
            return buildSnapshotLocked();
        }
    }
}
