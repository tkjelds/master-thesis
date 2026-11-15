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
    private int size;
    private long lastIssuedId;
    private long committedRevision;
    private long durableRevision;
    private boolean flushing;
    private Thread flusherThread;
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

            for (int i = 0; i < size; i++) {
                Reservation existing = reservations[i];
                if (start < existing.end && existing.start < end) {
                    return -1;
                }
            }

            if (lastIssuedId == Long.MAX_VALUE) {
                throw new IllegalStateException("reservation ID overflow");
            }
            if (committedRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("committed revision overflow");
            }

            ensureCapacity();
            long id = lastIssuedId + 1;
            Reservation reservation = new Reservation(id, start, end);

            reservations[size] = reservation;
            size++;
            lastIssuedId = id;
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
            for (int i = 0; i < size; i++) {
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

            for (int i = index; i < size - 1; i++) {
                reservations[i] = reservations[i + 1];
            }
            reservations[size - 1] = null;
            size--;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        boolean claimed = false;
        ScheduleSnapshot toPersist = null;

        try {
            synchronized (monitor) {
                if (flusherThread == Thread.currentThread()) {
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
                    if (!flushing) {
                        flushing = true;
                        flusherThread = Thread.currentThread();
                        claimed = true;
                        toPersist = copySnapshot();
                        break;
                    }
                    monitor.wait();
                }
            }

            try {
                sink.persist(toPersist);
            } catch (RuntimeException ex) {
                synchronized (monitor) {
                    failure = ex;
                }
                throw ex;
            }

            synchronized (monitor) {
                durableRevision = toPersist.committedRevision;
            }
        } finally {
            if (claimed) {
                synchronized (monitor) {
                    flushing = false;
                    flusherThread = null;
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

    // Called only while holding monitor.
    private void checkPersistenceFailure() {
        if (failure != null) {
            throw new IllegalStateException("persistence has failed", failure);
        }
    }

    // Called only while holding monitor, before changing logical state.
    private void ensureCapacity() {
        if (size < reservations.length) {
            return;
        }
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("reservation capacity exhausted");
        }

        int oldCapacity = reservations.length;
        int newCapacity;
        if (oldCapacity > Integer.MAX_VALUE / 2) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            newCapacity = oldCapacity * 2;
        }

        Reservation[] expanded = new Reservation[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = reservations[i];
        }
        reservations = expanded;
    }

    // Called only while holding monitor. Reservation objects are immutable.
    private ScheduleSnapshot copySnapshot() {
        Reservation[] copy = new Reservation[size];
        for (int i = 0; i < size; i++) {
            copy[i] = reservations[i];
        }
        return new ScheduleSnapshot(
                copy, committedRevision, durableRevision, flushing, failure);
    }
}
