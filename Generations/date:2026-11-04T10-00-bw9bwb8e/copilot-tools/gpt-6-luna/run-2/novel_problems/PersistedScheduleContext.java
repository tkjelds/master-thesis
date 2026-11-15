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
            this.reservations = new Reservation[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                this.reservations[i] = reservations[i];
            }
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flushing = flushing;
            this.failure = failure;
        }

        public int getReservationCount() {
            return reservations.length;
        }

        public Reservation getReservation(int index) {
            return reservations[index];
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
    private long committedRevision;
    private long durableRevision;
    private long nextReservationId = 1;
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
            throw new IllegalArgumentException("start must be less than end");
        }

        synchronized (monitor) {
            throwIfPersistenceFailed();

            int insertion = 0;
            while (insertion < reservations.length
                    && reservations[insertion].start < start) {
                insertion++;
            }

            if (insertion > 0 && reservations[insertion - 1].end > start) {
                return -1;
            }
            if (insertion < reservations.length
                    && end > reservations[insertion].start) {
                return -1;
            }

            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Committed revision counter exhausted");
            }
            if (nextReservationId == Long.MAX_VALUE) {
                throw new IllegalStateException("Reservation ID counter exhausted");
            }
            if (reservations.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many reservations");
            }

            long id = nextReservationId;
            Reservation[] updated = new Reservation[reservations.length + 1];
            for (int i = 0; i < insertion; i++) {
                updated[i] = reservations[i];
            }
            updated[insertion] = new Reservation(id, start, end);
            for (int i = insertion; i < reservations.length; i++) {
                updated[i + 1] = reservations[i];
            }

            reservations = updated;
            nextReservationId = id + 1;
            committedRevision++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("reservationId must be positive");
        }

        synchronized (monitor) {
            throwIfPersistenceFailed();

            int index = -1;
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i].id == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Committed revision counter exhausted");
            }

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

    public void flush() throws InterruptedException {
        Thread currentThread = Thread.currentThread();
        long targetRevision;

        synchronized (monitor) {
            if (flusherThread == currentThread) {
                throw new IllegalStateException("The sink must not recursively flush");
            }

            targetRevision = committedRevision;
            while (durableRevision < targetRevision) {
                throwIfPersistenceFailed();
                if (!flushing) {
                    flushing = true;
                    flusherThread = currentThread;
                    break;
                }
                monitor.wait();
            }

            if (durableRevision >= targetRevision) {
                return;
            }
        }

        try {
            while (true) {
                ScheduleSnapshot toPersist;

                synchronized (monitor) {
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                    throwIfPersistenceFailed();
                    toPersist = new ScheduleSnapshot(
                            reservations,
                            committedRevision,
                            durableRevision,
                            flushing,
                            persistenceFailure);
                }

                try {
                    sink.persist(toPersist);
                } catch (RuntimeException exception) {
                    RuntimeException retainedFailure;
                    synchronized (monitor) {
                        if (persistenceFailure == null) {
                            persistenceFailure = exception;
                        }
                        retainedFailure = persistenceFailure;
                    }
                    throw retainedFailure;
                }

                synchronized (monitor) {
                    if (toPersist.committedRevision > durableRevision) {
                        durableRevision = toPersist.committedRevision;
                    }
                    monitor.notifyAll();
                    if (durableRevision >= targetRevision) {
                        return;
                    }
                }
            }
        } finally {
            synchronized (monitor) {
                if (flusherThread == currentThread) {
                    flushing = false;
                    flusherThread = null;
                    monitor.notifyAll();
                }
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return new ScheduleSnapshot(
                    reservations,
                    committedRevision,
                    durableRevision,
                    flushing,
                    persistenceFailure);
        }
    }

    private void throwIfPersistenceFailed() {
        if (persistenceFailure != null) {
            throw new IllegalStateException(
                    "Persistence has failed", persistenceFailure);
        }
    }
}
