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
            this.reservations = reservations;
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flushing = flushing;
            this.failure = failure;
        }

        public Reservation[] getReservations() {
            Reservation[] copy = new Reservation[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                copy[i] = reservations[i];
            }
            return copy;
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
    private int size;
    private long lastReservationId;
    private long committedRevision;
    private long durableRevision;
    private Thread flusher;
    private RuntimeException failure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        this.sink = sink;
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }

        synchronized (monitor) {
            checkPersistenceFailure();

            for (int i = 0; i < size; i++) {
                Reservation existing = reservations[i];
                if (start < existing.end && existing.start < end) {
                    return -1;
                }
            }

            if (lastReservationId == Long.MAX_VALUE) {
                throw new IllegalStateException("reservation ID overflow");
            }
            checkRevisionCapacity();

            long id = lastReservationId + 1;
            Reservation reservation = new Reservation(id, start, end);
            ensureCapacity();

            reservations[size] = reservation;
            size++;
            lastReservationId = id;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("reservation ID must be positive");
        }

        synchronized (monitor) {
            checkPersistenceFailure();

            int index = -1;
            for (int i = 0; i < size; i++) {
                if (reservations[i].id == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }

            checkRevisionCapacity();
            for (int i = index; i < size - 1; i++) {
                reservations[i] = reservations[i + 1];
            }
            reservations[--size] = null;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        boolean claimed = false;
        try {
            long target;
            ScheduleSnapshot toPersist;

            synchronized (monitor) {
                Thread current = Thread.currentThread();
                if (flusher == current) {
                    throw new IllegalStateException("recursive flush is not permitted");
                }

                target = committedRevision;
                while (true) {
                    if (durableRevision >= target) {
                        return;
                    }
                    if (failure != null) {
                        throw failure;
                    }
                    if (flusher == null) {
                        flusher = current;
                        claimed = true;
                        toPersist = copySnapshot();
                        break;
                    }
                    monitor.wait();
                }
            }

            try {
                sink.persist(toPersist);
            } catch (RuntimeException persistenceFailure) {
                synchronized (monitor) {
                    failure = persistenceFailure;
                    if (durableRevision < target) {
                        throw persistenceFailure;
                    }
                    return;
                }
            }

            synchronized (monitor) {
                durableRevision = toPersist.committedRevision;
            }
        } finally {
            if (claimed) {
                synchronized (monitor) {
                    flusher = null;
                    monitor.notifyAll();
                }
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return copySnapshot();
        }
    }

    private ScheduleSnapshot copySnapshot() {
        Reservation[] copy = new Reservation[size];
        for (int i = 0; i < size; i++) {
            copy[i] = reservations[i];
        }
        return new ScheduleSnapshot(
                copy, committedRevision, durableRevision, flusher != null, failure);
    }

    private void checkPersistenceFailure() {
        if (failure != null) {
            throw new IllegalStateException("persistence has failed", failure);
        }
    }

    private void checkRevisionCapacity() {
        if (committedRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("revision overflow");
        }
    }

    private void ensureCapacity() {
        if (size < reservations.length) {
            return;
        }
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("reservation capacity exhausted");
        }

        int newCapacity;
        if (reservations.length == 0) {
            newCapacity = 4;
        } else if (reservations.length > Integer.MAX_VALUE / 2) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            newCapacity = reservations.length * 2;
        }

        Reservation[] expanded = new Reservation[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = reservations[i];
        }
        reservations = expanded;
    }
}
