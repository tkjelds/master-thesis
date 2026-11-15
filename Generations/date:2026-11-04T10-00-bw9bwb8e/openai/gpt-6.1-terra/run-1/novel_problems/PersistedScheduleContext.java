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
    private int size;
    private long lastIssuedId;
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
                throw new IllegalStateException("revision overflow");
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("reservation capacity exceeded");
            }

            long id = lastIssuedId + 1;
            Reservation reservation = new Reservation(id, start, end);
            ensureCapacity();

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
                throw new IllegalStateException("revision overflow");
            }

            int last = size - 1;
            reservations[index] = reservations[last];
            reservations[last] = null;
            size = last;
            committedRevision++;
            return true;
        }
    }

    public void flush() throws InterruptedException {
        long target;
        Thread current = Thread.currentThread();

        synchronized (monitor) {
            if (flusher == current) {
                throw new IllegalStateException("recursive flush is not allowed");
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
                    break;
                }
                monitor.wait();
            }
        }

        try {
            ScheduleSnapshot toPersist;
            synchronized (monitor) {
                toPersist = copySnapshot();
            }

            try {
                sink.persist(toPersist);
            } catch (RuntimeException ex) {
                synchronized (monitor) {
                    failure = ex;
                    monitor.notifyAll();
                }
                throw ex;
            }

            synchronized (monitor) {
                durableRevision = toPersist.committedRevision;
                monitor.notifyAll();
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

    private void checkPersistenceFailure() {
        if (failure != null) {
            throw new IllegalStateException("persistence has failed", failure);
        }
    }

    private void ensureCapacity() {
        if (size < reservations.length) {
            return;
        }

        int oldCapacity = reservations.length;
        int newCapacity;
        if (oldCapacity <= Integer.MAX_VALUE / 2) {
            newCapacity = oldCapacity * 2;
        } else {
            newCapacity = Integer.MAX_VALUE;
        }

        Reservation[] expanded = new Reservation[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = reservations[i];
        }
        reservations = expanded;
    }

    private ScheduleSnapshot copySnapshot() {
        Reservation[] copy = new Reservation[size];
        for (int i = 0; i < size; i++) {
            copy[i] = reservations[i];
        }
        return new ScheduleSnapshot(
                copy,
                committedRevision,
                durableRevision,
                flusher != null,
                failure);
    }
}
