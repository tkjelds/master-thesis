@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}

    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class Reservation {
        private final long id;
        private final long start;
        private final long end;

        private Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public long getId() {
            return id;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }
    }

    public static final class ScheduleSnapshot {
        private final Reservation[] reservations;
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flushing;
        private final RuntimeException failure;

        private ScheduleSnapshot(
                Reservation[] reservations,
                long committedRevision,
                long durableRevision,
                boolean flushing,
                RuntimeException failure) {
            this.reservations = reservations.clone();
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flushing = flushing;
            this.failure = failure;
        }

        public Reservation[] getReservations() {
            return reservations.clone();
        }

        public long getCommittedRevision() {
            return committedRevision;
        }

        public long getDurableRevision() {
            return durableRevision;
        }

        public boolean isFlushing() {
            return flushing;
        }

        public RuntimeException getFailure() {
            return failure;
        }
    }

    private final Object monitor = new Object();
    private final ScheduleSink sink;
    private Reservation[] reservations = new Reservation[0];
    private int reservationCount;
    private long committedRevision;
    private long durableRevision;
    private long nextReservationId = 1L;
    private boolean reservationIdsExhausted;
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
        synchronized (monitor) {
            throwIfPersistenceFailed();
            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            for (int i = 0; i < reservationCount; i++) {
                Reservation existing = reservations[i];
                if (start < existing.end && existing.start < end) {
                    return -1L;
                }
            }

            ensureRevisionCanAdvance();
            if (reservationIdsExhausted) {
                throw new IllegalStateException("reservation ID counter exhausted");
            }
            ensureReservationCapacity();

            long id = nextReservationId;
            reservations[reservationCount++] = new Reservation(id, start, end);
            committedRevision++;
            if (id == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId = id + 1L;
            }
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (monitor) {
            throwIfPersistenceFailed();
            if (reservationId <= 0L) {
                throw new IllegalArgumentException("reservationId must be positive");
            }

            int index = -1;
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].id == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }

            ensureRevisionCanAdvance();
            int elementsToMove = reservationCount - index - 1;
            for (int i = 0; i < elementsToMove; i++) {
                reservations[index + i] = reservations[index + i + 1];
            }
            reservations[--reservationCount] = null;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        final long targetRevision;
        synchronized (monitor) {
            targetRevision = committedRevision;
            if (durableRevision >= targetRevision) {
                return;
            }
            throwRetainedPersistenceFailure();

            while (flushing) {
                if (flusherThread == Thread.currentThread()) {
                    throw new IllegalStateException("flush cannot be called recursively by the sink");
                }
                monitor.wait();
                if (durableRevision >= targetRevision) {
                    return;
                }
                throwRetainedPersistenceFailure();
            }

            flushing = true;
            flusherThread = Thread.currentThread();
        }

        try {
            while (true) {
                final ScheduleSnapshot toPersist;
                synchronized (monitor) {
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                    throwRetainedPersistenceFailure();
                    toPersist = copySnapshot(reservations, reservationCount,
                            committedRevision, durableRevision, flushing, persistenceFailure);
                }

                try {
                    sink.persist(toPersist);
                } catch (RuntimeException exception) {
                    synchronized (monitor) {
                        if (persistenceFailure == null) {
                            persistenceFailure = exception;
                        }
                        monitor.notifyAll();
                        throw persistenceFailure;
                    }
                }

                synchronized (monitor) {
                    long persistedRevision = toPersist.getCommittedRevision();
                    if (persistedRevision > durableRevision) {
                        durableRevision = persistedRevision;
                    }
                    monitor.notifyAll();
                }
            }
        } finally {
            synchronized (monitor) {
                flushing = false;
                flusherThread = null;
                monitor.notifyAll();
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return copySnapshot(reservations, reservationCount, committedRevision,
                    durableRevision, flushing, persistenceFailure);
        }
    }

    private void throwIfPersistenceFailed() {
        if (persistenceFailure != null) {
            throw new IllegalStateException("persistence has failed", persistenceFailure);
        }
    }

    private void throwRetainedPersistenceFailure() {
        if (persistenceFailure != null) {
            throw persistenceFailure;
        }
    }

    private void ensureRevisionCanAdvance() {
        if (committedRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("committed revision counter exhausted");
        }
    }

    private void ensureReservationCapacity() {
        if (reservationCount < reservations.length) {
            return;
        }
        if (reservationCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("reservation capacity exhausted");
        }

        int newCapacity;
        if (reservations.length == 0) {
            newCapacity = 1;
        } else if (reservations.length > Integer.MAX_VALUE / 2) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            newCapacity = reservations.length * 2;
        }
        if (newCapacity <= reservationCount) {
            throw new IllegalStateException("reservation capacity exhausted");
        }

        Reservation[] expanded = new Reservation[newCapacity];
        for (int i = 0; i < reservationCount; i++) {
            expanded[i] = reservations[i];
        }
        reservations = expanded;
    }

    private static ScheduleSnapshot copySnapshot(
            Reservation[] source,
            int count,
            long committedRevision,
            long durableRevision,
            boolean flushing,
            RuntimeException failure) {
        Reservation[] copy = new Reservation[count];
        for (int i = 0; i < count; i++) {
            Reservation reservation = source[i];
            copy[i] = new Reservation(reservation.id, reservation.start, reservation.end);
        }
        return new ScheduleSnapshot(copy, committedRevision, durableRevision, flushing, failure);
    }
}
