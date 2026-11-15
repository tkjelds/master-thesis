@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}

    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class Reservation {
        public final long id;
        public final long start;
        public final long end;

        private Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class ScheduleSnapshot {
        private final Reservation[] reservations;

        public final long committedRevision;
        public final long durableRevision;
        public final boolean flusherActive;
        public final RuntimeException failure;

        private ScheduleSnapshot(
                Reservation[] source,
                int size,
                long committedRevision,
                long durableRevision,
                boolean flusherActive,
                RuntimeException failure) {
            this.reservations = new Reservation[size];
            for (int i = 0; i < size; i++) {
                this.reservations[i] = source[i];
            }
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherActive = flusherActive;
            this.failure = failure;
        }

        public Reservation[] getReservations() {
            Reservation[] copy = new Reservation[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                copy[i] = reservations[i];
            }
            return copy;
        }
    }

    private final Object monitor = new Object();
    private final ScheduleSink sink;

    private Reservation[] reservations = new Reservation[8];
    private int size;
    private long lastReservationId;
    private long committedRevision;
    private long durableRevision;
    private Thread flusher;
    private RuntimeException persistenceFailure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        this.sink = sink;
    }

    public long reserve(long start, long end) {
        synchronized (monitor) {
            checkPersistenceFailure();
            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            for (int i = 0; i < size; i++) {
                Reservation existing = reservations[i];
                if (start < existing.end && existing.start < end) {
                    return -1;
                }
            }

            if (lastReservationId == Long.MAX_VALUE) {
                throw new IllegalStateException("reservation ID overflow");
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision overflow");
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("reservation capacity exhausted");
            }

            long id = lastReservationId + 1;
            Reservation reservation = new Reservation(id, start, end);
            if (size == reservations.length) {
                int newCapacity = reservations.length <= Integer.MAX_VALUE / 2
                        ? reservations.length * 2
                        : Integer.MAX_VALUE;
                Reservation[] expanded = new Reservation[newCapacity];
                for (int i = 0; i < size; i++) {
                    expanded[i] = reservations[i];
                }
                reservations = expanded;
            }

            reservations[size] = reservation;
            size++;
            lastReservationId = id;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (monitor) {
            checkPersistenceFailure();
            if (reservationId <= 0) {
                throw new IllegalArgumentException("reservation ID must be positive");
            }

            for (int i = 0; i < size; i++) {
                if (reservations[i].id == reservationId) {
                    if (committedRevision == Long.MAX_VALUE) {
                        throw new IllegalStateException("committed revision overflow");
                    }
                    for (int j = i + 1; j < size; j++) {
                        reservations[j - 1] = reservations[j];
                    }
                    reservations[--size] = null;
                    committedRevision++;
                    return true;
                }
            }
            return false;
        }
    }

    public void flush() throws InterruptedException {
        long target;
        synchronized (monitor) {
            if (flusher == Thread.currentThread()) {
                throw new IllegalStateException("recursive flush is not permitted");
            }

            target = committedRevision;
            while (durableRevision < target) {
                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }
                if (flusher == null) {
                    flusher = Thread.currentThread();
                    break;
                }
                monitor.wait();
            }

            if (durableRevision >= target) {
                return;
            }
        }

        try {
            ScheduleSnapshot toPersist;
            synchronized (monitor) {
                toPersist = copySnapshot();
            }

            try {
                sink.persist(toPersist);
            } catch (RuntimeException failure) {
                synchronized (monitor) {
                    persistenceFailure = failure;
                }
                throw failure;
            }

            synchronized (monitor) {
                durableRevision = toPersist.committedRevision;
            }
        } finally {
            synchronized (monitor) {
                flusher = null;
                monitor.notifyAll();
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return copySnapshot();
        }
    }

    private ScheduleSnapshot copySnapshot() {
        return new ScheduleSnapshot(
                reservations,
                size,
                committedRevision,
                durableRevision,
                flusher != null,
                persistenceFailure);
    }

    private void checkPersistenceFailure() {
        if (persistenceFailure != null) {
            throw new IllegalStateException("persistence has failed", persistenceFailure);
        }
    }
}
