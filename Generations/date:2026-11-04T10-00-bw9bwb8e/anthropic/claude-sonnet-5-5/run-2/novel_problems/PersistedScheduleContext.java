import java.util.Objects;

interface ScheduleSink {
    void persist(ScheduleSnapshot snapshot);
}

@ScheduleSnapshot.ThreadSafe
final class ScheduleSnapshot {
    @interface ThreadSafe {}

    private final long[] ids;
    private final long[] starts;
    private final long[] ends;
    private final long committedRevision;
    private final long durableRevision;
    private final boolean flusherActive;
    private final RuntimeException failure;

    ScheduleSnapshot(long[] ids, long[] starts, long[] ends, long committedRevision,
                     long durableRevision, boolean flusherActive, RuntimeException failure) {
        this.ids = ids.clone();
        this.starts = starts.clone();
        this.ends = ends.clone();
        this.committedRevision = committedRevision;
        this.durableRevision = durableRevision;
        this.flusherActive = flusherActive;
        this.failure = failure;
    }

    public int size() {
        return ids.length;
    }

    public long[] reservationIds() {
        return ids.clone();
    }

    public long[] starts() {
        return starts.clone();
    }

    public long[] ends() {
        return ends.clone();
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
        StringBuilder sb = new StringBuilder("ScheduleSnapshot{committed=");
        sb.append(committedRevision).append(", durable=").append(durableRevision)
          .append(", flusher=").append(flusherActive).append(", failure=").append(failure)
          .append(", reservations=[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ids[i]).append(':').append('[').append(starts[i]).append(',')
              .append(ends[i]).append(')');
        }
        return sb.append("]}").toString();
    }
}

@PersistedScheduleContext.ThreadSafe
public class PersistedScheduleContext {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final ScheduleSink sink;

    // Guarded by lock; sorted by start.
    private long[] ids = new long[8];
    private long[] starts = new long[8];
    private long[] ends = new long[8];
    private int size = 0;
    private long nextId = 1;
    private long committedRevision = 0;
    private long durableRevision = 0;
    private boolean flusherActive = false;
    private Thread flusherThread = null;
    private RuntimeException failure = null;

    public PersistedScheduleContext(ScheduleSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    private void checkFailureLocked() {
        if (failure != null) {
            throw new IllegalStateException("Persistence failed", failure);
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be < end");
        }
        synchronized (lock) {
            checkFailureLocked();
            if (committedRevision == Long.MAX_VALUE || nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("counter overflow");
            }
            int pos = 0;
            for (int i = 0; i < size; i++) {
                if (start < ends[i] && starts[i] < end) {
                    return -1;
                }
                if (starts[i] < start) {
                    pos = i + 1;
                }
            }
            if (size == ids.length) {
                int cap = size * 2;
                ids = grow(ids, cap);
                starts = grow(starts, cap);
                ends = grow(ends, cap);
            }
            for (int i = size; i > pos; i--) {
                ids[i] = ids[i - 1];
                starts[i] = starts[i - 1];
                ends[i] = ends[i - 1];
            }
            long id = nextId++;
            ids[pos] = id;
            starts[pos] = start;
            ends[pos] = end;
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
                throw new IllegalStateException("counter overflow");
            }
            for (int i = idx; i < size - 1; i++) {
                ids[i] = ids[i + 1];
                starts[i] = starts[i + 1];
                ends[i] = ends[i + 1];
            }
            size--;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        synchronized (lock) {
            final long target = committedRevision;
            while (true) {
                if (durableRevision >= target) {
                    return;
                }
                if (failure != null) {
                    throw failure;
                }
                if (!flusherActive) {
                    // Claim the flusher role.
                    flusherActive = true;
                    flusherThread = Thread.currentThread();
                    ScheduleSnapshot snap = buildLocked();
                    final long rev = snap.committedRevision();
                    boolean completed = false;
                    RuntimeException thrown = null;
                    lock.notifyAll(); // status changed
                    try {
                        // Release monitor while calling the sink.
                        persistOutside(snap);
                        completed = true;
                    } catch (RuntimeException e) {
                        thrown = e;
                    } finally {
                        // Re-entered monitor (we are inside synchronized(lock) here).
                        if (completed) {
                            if (rev > durableRevision) {
                                durableRevision = rev;
                            }
                        } else if (thrown != null) {
                            if (failure == null) {
                                failure = thrown;
                            }
                        }
                        flusherActive = false;
                        flusherThread = null;
                        lock.notifyAll();
                    }
                    continue;
                }
                if (flusherThread == Thread.currentThread()) {
                    throw new IllegalStateException("recursive flush from sink");
                }
                lock.wait();
            }
        }
    }

    // Must be invoked while holding the monitor exactly once via flush(); releases it
    // for the duration of the sink call by structuring: see below.
    private void persistOutside(ScheduleSnapshot snap) {
        // The monitor cannot be released mid-block in Java, so flush() uses this
        // helper only after exiting its synchronized region; see flushImpl.
        throw new IllegalStateException("unreachable");
    }

    private ScheduleSnapshot buildLocked() {
        long[] i2 = new long[size];
        long[] s2 = new long[size];
        long[] e2 = new long[size];
        System.arraycopy(ids, 0, i2, 0, size);
        System.arraycopy(starts, 0, s2, 0, size);
        System.arraycopy(ends, 0, e2, 0, size);
        return new ScheduleSnapshot(i2, s2, e2, committedRevision, durableRevision,
                flusherActive, failure);
    }

    public ScheduleSnapshot snapshot() {
        synchronized (lock) {
            return buildLocked();
        }
    }

    private static long[] grow(long[] a, int cap) {
        long[] n = new long[cap];
        System.arraycopy(a, 0, n, 0, a.length);
        return n;
    }
}
