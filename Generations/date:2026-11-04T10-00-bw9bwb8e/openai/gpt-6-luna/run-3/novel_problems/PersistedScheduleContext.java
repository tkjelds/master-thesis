@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}

    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class ScheduleSnapshot {
        private final long committedRevision;
        private final long durableRevision;
        private final long[] reservationIds;
        private final long[] starts;
        private final long[] ends;
        private final boolean flusherInProgress;
        private final RuntimeException persistenceFailure;

        private ScheduleSnapshot(
                long committedRevision,
                long durableRevision,
                long[] reservationIds,
                long[] starts,
                long[] ends,
                boolean flusherInProgress,
                RuntimeException persistenceFailure) {
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.reservationIds = reservationIds;
            this.starts = starts;
            this.ends = ends;
            this.flusherInProgress = flusherInProgress;
            this.persistenceFailure = persistenceFailure;
        }

        public long getCommittedRevision() {
            return committedRevision;
        }

        public long getDurableRevision() {
            return durableRevision;
        }

        public int getReservationCount() {
            return reservationIds.length;
        }

        public long getReservationId(int index) {
            checkIndex(index);
            return reservationIds[index];
        }

        public long getStart(int index) {
            checkIndex(index);
            return starts[index];
        }

        public long getEnd(int index) {
            checkIndex(index);
            return ends[index];
        }

        public boolean isFlusherInProgress() {
            return flusherInProgress;
        }

        public RuntimeException getPersistenceFailure() {
            return persistenceFailure;
        }

        private void checkIndex(int index) {
            if (index < 0 || index >= reservationIds.length) {
                throw new IndexOutOfBoundsException("reservation index: " + index);
            }
        }
    }

    private final ScheduleSink sink;

    private long[] reservationIds = new long[0];
    private long[] starts = new long[0];
    private long[] ends = new long[0];
    private int reservationCount;

    private long committedRevision;
    private long durableRevision;
    private long nextReservationId = 1;
    private boolean reservationIdsExhausted;

    private boolean flusherInProgress;
    private Thread flusherThread;
    private RuntimeException persistenceFailure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        this.sink = sink;
    }

    public long reserve(long start, long end) {
        synchronized (this) {
            rejectMutationAfterFailure();

            if (start >= end) {
                throw new IllegalArgumentException("reservation must have start < end");
            }

            int insertionIndex = 0;
            while (insertionIndex < reservationCount
                    && starts[insertionIndex] < start) {
                insertionIndex++;
            }

            for (int i = 0; i < reservationCount; i++) {
                if (start < ends[i] && starts[i] < end) {
                    return -1;
                }
            }

            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision exhausted");
            }
            if (reservationIdsExhausted) {
                throw new IllegalStateException("reservation ID space exhausted");
            }
            if (reservationCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("reservation capacity exhausted");
            }

            ensureCapacity(reservationCount + 1);

            long id = nextReservationId;
            for (int i = reservationCount; i > insertionIndex; i--) {
                reservationIds[i] = reservationIds[i - 1];
                starts[i] = starts[i - 1];
                ends[i] = ends[i - 1];
            }
            reservationIds[insertionIndex] = id;
            starts[insertionIndex] = start;
            ends[insertionIndex] = end;
            reservationCount++;
            committedRevision++;

            if (id == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId = id + 1;
            }

            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (this) {
            rejectMutationAfterFailure();

            if (reservationId <= 0) {
                throw new IllegalArgumentException("reservation ID must be positive");
            }

            int index = -1;
            for (int i = 0; i < reservationCount; i++) {
                if (reservationIds[i] == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }

            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision exhausted");
            }

            for (int i = index; i < reservationCount - 1; i++) {
                reservationIds[i] = reservationIds[i + 1];
                starts[i] = starts[i + 1];
                ends[i] = ends[i + 1];
            }
            reservationCount--;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        final long target;
        final Thread currentThread = Thread.currentThread();

        synchronized (this) {
            target = committedRevision;

            if (durableRevision >= target) {
                return;
            }
            if (persistenceFailure != null) {
                throw persistenceFailure;
            }
            if (flusherInProgress && flusherThread == currentThread) {
                throw new IllegalStateException("recursive flush from the sink is not allowed");
            }

            while (flusherInProgress) {
                wait();

                if (durableRevision >= target) {
                    return;
                }
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
                if (flusherInProgress && flusherThread == currentThread) {
                    throw new IllegalStateException("recursive flush is not allowed");
                }
            }

            if (durableRevision >= target) {
                return;
            }
            if (persistenceFailure != null) {
                throw persistenceFailure;
            }

            flusherInProgress = true;
            flusherThread = currentThread;
        }

        try {
            while (true) {
                ScheduleSnapshot toPersist;

                synchronized (this) {
                    if (durableRevision >= target) {
                        return;
                    }
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }

                    toPersist = copySnapshot();
                }

                try {
                    sink.persist(toPersist);
                } catch (RuntimeException failure) {
                    synchronized (this) {
                        if (persistenceFailure == null) {
                            persistenceFailure = failure;
                        }
                        if (flusherInProgress && flusherThread == currentThread) {
                            flusherInProgress = false;
                            flusherThread = null;
                        }
                        notifyAll();
                        throw persistenceFailure;
                    }
                }

                synchronized (this) {
                    if (toPersist.getCommittedRevision() > durableRevision) {
                        durableRevision = toPersist.getCommittedRevision();
                    }
                    notifyAll();
                }
            }
        } finally {
            synchronized (this) {
                if (flusherInProgress && flusherThread == currentThread) {
                    flusherInProgress = false;
                    flusherThread = null;
                }
                notifyAll();
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (this) {
            return copySnapshot();
        }
    }

    private void rejectMutationAfterFailure() {
        if (persistenceFailure != null) {
            throw new IllegalStateException(
                    "persistence has failed; mutations are disabled",
                    persistenceFailure);
        }
    }

    private ScheduleSnapshot copySnapshot() {
        long[] copiedIds = new long[reservationCount];
        long[] copiedStarts = new long[reservationCount];
        long[] copiedEnds = new long[reservationCount];

        for (int i = 0; i < reservationCount; i++) {
            copiedIds[i] = reservationIds[i];
            copiedStarts[i] = starts[i];
            copiedEnds[i] = ends[i];
        }

        return new ScheduleSnapshot(
                committedRevision,
                durableRevision,
                copiedIds,
                copiedStarts,
                copiedEnds,
                flusherInProgress,
                persistenceFailure);
    }

    private void ensureCapacity(int needed) {
        if (needed <= reservationIds.length) {
            return;
        }

        int newCapacity = reservationIds.length == 0 ? 4 : reservationIds.length;
        while (newCapacity < needed) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = needed;
                break;
            }
            newCapacity *= 2;
        }

        long[] newIds = new long[newCapacity];
        long[] newStarts = new long[newCapacity];
        long[] newEnds = new long[newCapacity];

        for (int i = 0; i < reservationCount; i++) {
            newIds[i] = reservationIds[i];
            newStarts[i] = starts[i];
            newEnds[i] = ends[i];
        }

        reservationIds = newIds;
        starts = newStarts;
        ends = newEnds;
    }
}
