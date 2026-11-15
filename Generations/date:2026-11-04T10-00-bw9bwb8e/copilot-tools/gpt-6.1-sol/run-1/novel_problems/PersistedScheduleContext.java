@PersistedScheduleContext.ThreadSafe
public final class PersistedScheduleContext {
    @interface ThreadSafe {}

    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static final class Reservation {
        public final long reservationId;
        public final long start;
        public final long end;

        private Reservation(long reservationId, long start, long end) {
            this.reservationId = reservationId;
            this.start = start;
            this.end = end;
        }
    }

    public static final class ScheduleSnapshot {
        public final long committedRevision;
        public final long durableRevision;
        public final boolean flushing;
        public final RuntimeException failure;
        private final Reservation[] reservations;

        private ScheduleSnapshot(Reservation[] reservations,
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
            return copyReservations(reservations);
        }
    }

    private final Object monitor = new Object();
    private final ScheduleSink sink;
    private Reservation[] reservations = new Reservation[0];
    private long committedRevision;
    private long durableRevision;
    private long lastReservationId;
    private Thread flusher;
    private RuntimeException failure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
    }

    // Storage grows on demand; allocation failure leaves the schedule unchanged.
    public long reserve(long start, long end) {
        synchronized (monitor) {
            checkHealthy();
            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            int insertion = 0;
            for (int i = 0; i < reservations.length; i++) {
                Reservation reservation = reservations[i];
                if (start < reservation.end && reservation.start < end) {
                    return -1;
                }
                if (reservation.start < start) {
                    insertion++;
                }
            }

            checkRevisionAvailable();
            if (lastReservationId == Long.MAX_VALUE) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }
            if (reservations.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("Reservation capacity exhausted");
            }

            long id = lastReservationId + 1;
            Reservation[] updated = new Reservation[reservations.length + 1];
            for (int i = 0; i < insertion; i++) {
                updated[i] = reservations[i];
            }
            updated[insertion] = new Reservation(id, start, end);
            for (int i = insertion; i < reservations.length; i++) {
                updated[i + 1] = reservations[i];
            }

            reservations = updated;
            lastReservationId = id;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (monitor) {
            checkHealthy();
            if (reservationId <= 0) {
                throw new IllegalArgumentException("reservationId must be positive");
            }

            int index = 0;
            while (index < reservations.length
                    && reservations[index].reservationId != reservationId) {
                index++;
            }
            if (index == reservations.length) {
                return false;
            }
            checkRevisionAvailable();

            Reservation[] updated = new Reservation[reservations.length - 1];
            for (int i = 0; i < index; i++) {
                updated[i] = reservations[i];
            }
            for (int i = index + 1; i < reservations.length; i++) {
                updated[i - 1] = reservations[i];
            }

            reservations = updated;
            committedRevision++;
            return true;
        }
    }

    /**
     * Waits interruptibly if another thread owns persistence.
     * The sink must not recursively invoke flush on this context.
     */
    public void flush() throws InterruptedException {
        Thread caller = Thread.currentThread();
        synchronized (monitor) {
            long target = committedRevision;
            if (flusher == caller) {
                throw new IllegalStateException("Recursive flush is not permitted");
            }
            while (durableRevision < target) {
                if (failure != null) {
                    throw failure;
                }
                if (flusher == null) {
                    flusher = caller;
                    break;
                }
                monitor.wait();
            }
            if (durableRevision >= target) {
                return;
            }
        }

        try {
            ScheduleSnapshot captured;
            synchronized (monitor) {
                captured = snapshotLocked();
            }

            try {
                sink.persist(captured);
            } catch (RuntimeException exception) {
                synchronized (monitor) {
                    failure = exception;
                }
                throw exception;
            }

            synchronized (monitor) {
                // Commits made during persist are deliberately not included.
                durableRevision = captured.committedRevision;
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
            return snapshotLocked();
        }
    }

    private void checkHealthy() {
        if (failure != null) {
            throw new IllegalStateException("Persistence has failed", failure);
        }
    }

    private void checkRevisionAvailable() {
        if (committedRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Committed revision exhausted");
        }
    }

    private ScheduleSnapshot snapshotLocked() {
        return new ScheduleSnapshot(copyReservations(reservations),
                committedRevision, durableRevision, flusher != null, failure);
    }

    private static Reservation[] copyReservations(Reservation[] source) {
        Reservation[] copy = new Reservation[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
