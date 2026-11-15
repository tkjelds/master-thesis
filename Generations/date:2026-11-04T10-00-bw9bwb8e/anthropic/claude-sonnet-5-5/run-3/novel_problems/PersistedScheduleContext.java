import java.lang.*;

@PersistedScheduleContext.ThreadSafe
public class PersistedScheduleContext {
    @interface ThreadSafe {}


    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class ScheduleSnapshot {
        private final long[] starts;
        private final long[] ends;
        private final long[] ids;
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flusherActive;
        private final RuntimeException failure;

        ScheduleSnapshot(long[] starts, long[] ends, long[] ids, int size,
                         long committedRevision, long durableRevision,
                         boolean flusherActive, RuntimeException failure) {
            this.starts = new long[size];
            this.ends = new long[size];
            this.ids = new long[size];
            System.arraycopy(starts, 0, this.starts, 0, size);
            System.arraycopy(ends, 0, this.ends, 0, size);
            System.arraycopy(ids, 0, this.ids, 0, size);
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherActive = flusherActive;
            this.failure = failure;
        }

        public int size() {
            return ids.length;
        }

        public long[] getStarts() {
            return starts.clone();
        }

        public long[] getEnds() {
            return ends.clone();
        }

        public long[] getIds() {
            return ids.clone();
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
    }

    private final Object lock = new Object();
    private final ScheduleSink sink;

    // guarded by lock; sorted by start
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
            throw new IllegalArgumentException("sink");
        }
        this.sink = sink;
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be < end");
        }
        synchronized (lock) {
            checkFailureLocked();
            int i = 0;
            while (i < size && starts[i] < start) {
                i++;
            }
            if (i > 0 && ends[i - 1] > start) {
                return -1;
            }
            if (i < size && starts[i] < end) {
                return -1;
            }
            if (nextId == Long.MAX_VALUE || committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("counter overflow");
            }
            if (size == ids.length) {
                int n = size * 2;
                long[] ns = new long[n];
                long[] ne = new long[n];
                long[] ni = new long[n];
                System.arraycopy(starts, 0, ns, 0, size);
                System.arraycopy(ends, 0, ne, 0, size);
                System.arraycopy(ids, 0, ni, 0, size);
                starts = ns;
                ends = ne;
                ids = ni;
            }
            for (int j = size; j > i; j--) {
                starts[j] = starts[j - 1];
                ends[j] = ends[j - 1];
                ids[j] = ids[j - 1];
            }
            long id = nextId++;
            starts[i] = start;
            ends[i] = end;
            ids[i] = id;
            size++;
            committedRevision++;
            lock.notifyAll();
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (lock) {
            checkFailureLocked();
            if (reservationId <= 0) {
                return false;
            }
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
            for (int j = idx; j < size - 1; j++) {
                starts[j] = starts[j + 1];
                ends[j] = ends[j + 1];
                ids[j] = ids[j + 1];
            }
            size--;
            committedRevision++;
            lock.notifyAll();
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
                    break;
                }
                if (flusherThread == Thread.currentThread()) {
                    throw new IllegalStateException("recursive flush from sink");
                }
                lock.wait();
            }
            flushing = true;
            flusherThread = Thread.currentThread();
            toPersist = new ScheduleSnapshot(starts, ends, ids, size,
                    committedRevision, durableRevision, true, failure);
        }

        boolean success = false;
        RuntimeException thrown = null;
        try {
            sink.persist(toPersist);
            success = true;
        } catch (RuntimeException e) {
            thrown = e;
        } finally {
            synchronized (lock) {
                if (success) {
                    if (toPersist.getCommittedRevision() > durableRevision) {
                        durableRevision = toPersist.getCommittedRevision();
                    }
                } else if (thrown != null) {
                    if (failure == null) {
                        failure = thrown;
                    }
                }
                flushing = false;
                flusherThread = null;
                lock.notifyAll();
            }
        }
        if (thrown != null) {
            throw thrown;
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (lock) {
            return new ScheduleSnapshot(starts, ends, ids, size,
                    committedRevision, durableRevision, flushing, failure);
        }
    }

    private void checkFailureLocked() {
        if (failure != null) {
            throw new IllegalStateException("persistence failed", failure);
        }
    }
}
