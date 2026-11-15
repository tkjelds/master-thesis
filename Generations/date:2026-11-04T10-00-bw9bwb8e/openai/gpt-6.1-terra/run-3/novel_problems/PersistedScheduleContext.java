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

    private Reservation[] reservations = new Reservation[8];
    private int reservationCount;
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
        synchronized (monitor) {
            checkPersistenceFailure();

            if (start >= end) {
                throw new IllegalArgumentException("start must be less than end");
            }

            for (int i = 0; i < reservationCount; i++) {
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

            long id = lastReservationId + 1;
            Reservation reservation = new Reservation(id, start, end);

            if (reservationCount == reservations.length) {
                if (reservations.length == Integer.MAX_VALUE) {
                    throw new IllegalStateException("reservation capacity exhausted");
                }
                long proposed = (long) reservations.length * 2;
                if (proposed > Integer.MAX_VALUE) {
                    proposed = Integer.MAX_VALUE;
                }
                Reservation[] expanded = new Reservation[(int) proposed];
                for (int i = 0; i < reservationCount; i++) {
                    expanded[i] = reservations[i];
                }
                reservations = expanded;
            }

            reservations[reservationCount] = reservation;
            reservationCount++;
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
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision overflow");
            }

            for (int i = index; i < reservationCount - 1; i++) {
                reservations[i] = reservations[i + 1];
            }
            reservationCount--;
            reservations[reservationCount] = null;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        boolean ownsFlusher = false;
        ScheduleSnapshot toPersist = null;

        try {
            synchronized (monitor) {
                Thread current = Thread.currentThread();
                if (flusher == current) {
                    throw new IllegalStateException("recursive flush is not permitted");
                }

                long target = committedRevision;
                while (true) {
                    if (durableRevision >= target) {
                        return;
                    }
                    if (failure != null) {
                        throw failure;
                    }
                    if (flusher == null) {
                        toPersist = copySnapshot(true);
                        flusher = current;
                        ownsFlusher = true;
                        break;
                    }
                    monitor.wait();
                }
            }

            try {
                sink.persist(toPersist);
            } catch (RuntimeException problem) {
                synchronized (monitor) {
                    failure = problem;
                }
                throw problem;
            }

            synchronized (monitor) {
                durableRevision = toPersist.committedRevision;
            }
        } finally {
            if (ownsFlusher) {
                synchronized (monitor) {
                    flusher = null;
                    monitor.notifyAll();
                }
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            return copySnapshot(flusher != null);
        }
    }

    // Called only while holding monitor.
    private void checkPersistenceFailure() {
        if (failure != null) {
            throw new IllegalStateException("persistence has failed", failure);
        }
    }

    // Called only while holding monitor. The resulting array is privately owned.
    private ScheduleSnapshot copySnapshot(boolean flushing) {
        Reservation[] copy = new Reservation[reservationCount];
        for (int i = 0; i < reservationCount; i++) {
            copy[i] = reservations[i];
        }
        return new ScheduleSnapshot(
                copy, committedRevision, durableRevision, flushing, failure);
    }
}
