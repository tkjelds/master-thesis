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

        private ScheduleSnapshot(Reservation[] reservations, long committedRevision,
                                 long durableRevision, boolean flushing,
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
    private long lastId;
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
            for (int i = 0; i < reservations.length; i++) {
                Reservation reservation = reservations[i];
                if (start < reservation.end && reservation.start < end) {
                    return -1;
                }
            }
            if (lastId == Long.MAX_VALUE || committedRevision == Long.MAX_VALUE
                    || reservations.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("schedule counter overflow");
            }
            long id = lastId + 1;
            Reservation[] updated = new Reservation[reservations.length + 1];
            for (int i = 0; i < reservations.length; i++) {
                updated[i] = reservations[i];
            }
            updated[reservations.length] = new Reservation(id, start, end);
            reservations = updated;
            lastId = id;
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
                throw new IllegalStateException("schedule revision overflow");
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
        boolean claimed = false;
        boolean persisted = false;
        RuntimeException sinkFailure = null;
        ScheduleSnapshot toPersist = null;
        try {
            synchronized (monitor) {
                long target = committedRevision;
                while (true) {
                    if (flusher == Thread.currentThread()) {
                        throw new IllegalStateException("recursive flush");
                    }
                    if (durableRevision >= target) {
                        return;
                    }
                    if (persistenceFailure != null) {
                        throw persistenceFailure;
                    }
                    if (flusher == null) {
                        flusher = Thread.currentThread();
                        claimed = true;
                        toPersist = copySnapshot();
                        break;
                    }
                    monitor.wait();
                }
            }
            try {
                sink.persist(toPersist);
                persisted = true;
            } catch (RuntimeException failure) {
                sinkFailure = failure;
                throw failure;
            }
        } finally {
            if (claimed) {
                synchronized (monitor) {
                    if (persisted) {
                        durableRevision = toPersist.committedRevision;
                    } else if (sinkFailure != null) {
                        persistenceFailure = sinkFailure;
                    }
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
        return new ScheduleSnapshot(reservations, committedRevision, durableRevision,
                                    flusher != null, persistenceFailure);
    }

    private void checkPersistenceFailure() {
        if (persistenceFailure != null) {
            throw new IllegalStateException("persistence has failed", persistenceFailure);
        }
    }
}
