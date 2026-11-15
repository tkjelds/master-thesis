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
        public final long committedRevision;
        public final long durableRevision;
        public final boolean flushing;
        public final RuntimeException failure;

        private final Reservation[] reservations;

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

        public int size() {
            return reservations.length;
        }

        public Reservation getReservation(int index) {
            return reservations[index];
        }

        public Reservation[] getReservations() {
            Reservation[] copy = new Reservation[reservations.length];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = reservations[i];
            }
            return copy;
        }
    }

    private static final long MAX_COUNTER = 0x7fffffffffffffffL;
    private static final int MAX_ARRAY_LENGTH = 0x7fffffff;

    private final Object monitor = new Object();
    private final ScheduleSink sink;

    private Reservation[] reservations = new Reservation[0];
    private long lastReservationId;
    private long committedRevision;
    private long durableRevision;
    private Thread flusherThread;
    private RuntimeException persistenceFailure;

    public PersistedScheduleContext(ScheduleSink sink) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
    }

    public long reserve(long start, long end) {
        synchronized (monitor) {
            requireHealthyPersistence();

            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            int insertion = 0;
            while (insertion < reservations.length
                    && reservations[insertion].end <= start) {
                insertion++;
            }

            if (insertion < reservations.length
                    && reservations[insertion].start < end) {
                return -1;
            }

            requireRevisionCapacity();
            if (lastReservationId == MAX_COUNTER) {
                throw new IllegalStateException("Reservation ID overflow");
            }
            if (reservations.length == MAX_ARRAY_LENGTH) {
                throw new IllegalStateException("Reservation count overflow");
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
            requireHealthyPersistence();

            if (reservationId <= 0) {
                throw new IllegalArgumentException("reservationId must be positive");
            }

            int index = 0;
            while (index < reservations.length
                    && reservations[index].id != reservationId) {
                index++;
            }

            if (index == reservations.length) {
                return false;
            }

            requireRevisionCapacity();

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
     * Waits interruptibly when another thread is flushing. The sink runs on
     * the claiming caller's thread and must not recursively invoke flush().
     */
    public void flush() throws InterruptedException {
        Thread caller = Thread.currentThread();
        boolean claimed = false;

        try {
            ScheduleSnapshot pending;

            synchronized (monitor) {
                long target = committedRevision;

                if (flusherThread == caller) {
                    throw new IllegalStateException("Recursive flush is not permitted");
                }

                while (durableRevision < target) {
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }

                    if (flusherThread == null) {
                        claimed = true;
                        flusherThread = caller;
                        break;
                    }

                    monitor.wait();
                }

                if (durableRevision >= target) {
                    return;
                }

                pending = copySnapshotLocked();
            }

            try {
                sink.persist(pending);
            } catch (RuntimeException failure) {
                synchronized (monitor) {
                    persistenceFailure = failure;
                }
                throw failure;
            }

            synchronized (monitor) {
                // Commits made during persist are not included in this advance.
                durableRevision = pending.committedRevision;
                monitor.notifyAll();
            }
        } finally {
            if (claimed) {
                synchronized (monitor) {
                    flusherThread = null;
                    monitor.notifyAll();
                }
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return copySnapshotLocked();
        }
    }

    private void requireHealthyPersistence() {
        if (persistenceFailure != null) {
            throw new IllegalStateException(
                    "Persistence has failed", persistenceFailure);
        }
    }

    private void requireRevisionCapacity() {
        if (committedRevision == MAX_COUNTER) {
            throw new IllegalStateException("Committed revision overflow");
        }
    }

    private ScheduleSnapshot copySnapshotLocked() {
        Reservation[] copy = new Reservation[reservations.length];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = reservations[i];
        }

        return new ScheduleSnapshot(
                copy,
                committedRevision,
                durableRevision,
                flusherThread != null,
                persistenceFailure);
    }
}
