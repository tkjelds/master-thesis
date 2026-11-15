@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}

    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class ScheduleSnapshot {
        private final long[] reservationIds;
        private final long[] starts;
        private final long[] ends;
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flushing;
        private final RuntimeException failure;

        private ScheduleSnapshot(
                long[] ids,
                long[] starts,
                long[] ends,
                int size,
                long committedRevision,
                long durableRevision,
                boolean flushing,
                RuntimeException failure) {
            this.reservationIds = new long[size];
            this.starts = new long[size];
            this.ends = new long[size];
            for (int i = 0; i < size; i++) {
                this.reservationIds[i] = ids[i];
                this.starts[i] = starts[i];
                this.ends[i] = ends[i];
            }
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flushing = flushing;
            this.failure = failure;
        }

        public int size() {
            return reservationIds.length;
        }

        public long reservationIdAt(int index) {
            return reservationIds[index];
        }

        public long startAt(int index) {
            return starts[index];
        }

        public long endAt(int index) {
            return ends[index];
        }

        public long[] reservationIds() {
            return copy(reservationIds);
        }

        public long[] starts() {
            return copy(starts);
        }

        public long[] ends() {
            return copy(ends);
        }

        public long committedRevision() {
            return committedRevision;
        }

        public long durableRevision() {
            return durableRevision;
        }

        public boolean isFlushing() {
            return flushing;
        }

        public RuntimeException failure() {
            return failure;
        }

        private static long[] copy(long[] source) {
            long[] result = new long[source.length];
            for (int i = 0; i < source.length; i++) {
                result[i] = source[i];
            }
            return result;
        }
    }

    private final Object state = new Object();
    private final ScheduleSink sink;

    // Reservations are kept in ascending start-time order.
    private long[] reservationIds = new long[8];
    private long[] starts = new long[8];
    private long[] ends = new long[8];
    private int size;

    private long nextReservationId = 1;
    private boolean reservationIdsExhausted;
    private long committedRevision;
    private long durableRevision;
    private boolean flushing;
    private Thread flusherThread;
    private RuntimeException persistenceFailure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        this.sink = sink;
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("A reservation must have start < end");
        }

        synchronized (state) {
            throwIfPersistenceFailed();

            int insertionPoint = 0;
            while (insertionPoint < size && starts[insertionPoint] < start) {
                insertionPoint++;
            }

            if (insertionPoint > 0 && ends[insertionPoint - 1] > start) {
                return -1;
            }
            if (insertionPoint < size && starts[insertionPoint] < end) {
                return -1;
            }

            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Committed revision counter exhausted");
            }
            if (reservationIdsExhausted) {
                throw new IllegalStateException("Reservation ID counter exhausted");
            }

            ensureCapacity(size + 1);

            long id = nextReservationId;
            for (int i = size; i > insertionPoint; i--) {
                reservationIds[i] = reservationIds[i - 1];
                starts[i] = starts[i - 1];
                ends[i] = ends[i - 1];
            }
            reservationIds[insertionPoint] = id;
            starts[insertionPoint] = start;
            ends[insertionPoint] = end;
            size++;

            if (nextReservationId == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId++;
            }
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (state) {
            throwIfPersistenceFailed();

            int index = 0;
            while (index < size && reservationIds[index] != reservationId) {
                index++;
            }
            if (index == size) {
                return false;
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Committed revision counter exhausted");
            }

            for (int i = index; i < size - 1; i++) {
                reservationIds[i] = reservationIds[i + 1];
                starts[i] = starts[i + 1];
                ends[i] = ends[i + 1];
            }
            size--;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        final long target;
        synchronized (state) {
            if (flushing && flusherThread == Thread.currentThread()) {
                throw new IllegalStateException("The sink must not recursively flush");
            }

            target = committedRevision;
            if (durableRevision >= target) {
                return;
            }
            if (persistenceFailure != null) {
                throw persistenceFailure;
            }

            while (flushing) {
                state.wait();
                if (durableRevision >= target) {
                    return;
                }
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
            }

            if (durableRevision >= target) {
                return;
            }
            if (persistenceFailure != null) {
                throw persistenceFailure;
            }

            flushing = true;
            flusherThread = Thread.currentThread();
        }

        boolean ownsFlusherRole = true;
        try {
            while (true) {
                ScheduleSnapshot pending;
                synchronized (state) {
                    if (durableRevision >= target) {
                        return;
                    }
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }
                    pending = new ScheduleSnapshot(
                            reservationIds,
                            starts,
                            ends,
                            size,
                            committedRevision,
                            durableRevision,
                            flushing,
                            persistenceFailure);
                }

                try {
                    sink.persist(pending);
                } catch (RuntimeException failure) {
                    RuntimeException retained;
                    synchronized (state) {
                        if (persistenceFailure == null) {
                            persistenceFailure = failure;
                        }
                        retained = persistenceFailure;
                        flushing = false;
                        flusherThread = null;
                        state.notifyAll();
                        ownsFlusherRole = false;
                    }
                    throw retained;
                }

                synchronized (state) {
                    if (pending.committedRevision() > durableRevision) {
                        durableRevision = pending.committedRevision();
                    }
                    state.notifyAll();
                    if (durableRevision >= target) {
                        return;
                    }
                }
            }
        } finally {
            if (ownsFlusherRole) {
                synchronized (state) {
                    flushing = false;
                    flusherThread = null;
                    state.notifyAll();
                }
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (state) {
            return new ScheduleSnapshot(
                    reservationIds,
                    starts,
                    ends,
                    size,
                    committedRevision,
                    durableRevision,
                    flushing,
                    persistenceFailure);
        }
    }

    private void ensureCapacity(int required) {
        if (required <= reservationIds.length) {
            return;
        }

        int newCapacity = reservationIds.length * 2;
        if (newCapacity < required) {
            newCapacity = required;
        }

        long[] newIds = new long[newCapacity];
        long[] newStarts = new long[newCapacity];
        long[] newEnds = new long[newCapacity];

        for (int i = 0; i < size; i++) {
            newIds[i] = reservationIds[i];
            newStarts[i] = starts[i];
            newEnds[i] = ends[i];
        }

        reservationIds = newIds;
        starts = newStarts;
        ends = newEnds;
    }

    private void throwIfPersistenceFailed() {
        if (persistenceFailure != null) {
            throw new IllegalStateException("Persistence has failed", persistenceFailure);
        }
    }
}
