@PersistedScheduleContext.ThreadSafe
class PersistedScheduleContext {
    @interface ThreadSafe {}

    private final ScheduleSink sink;
    private final Reservation[] reservations;
    private long committedRevision = 0;
    private volatile long durableRevision = 0;
    private boolean flusherClaimed = false;
    private PersistenceFailure persistenceFailure = null;

    public PersistedScheduleContext(ScheduleSink sink, int capacity) {
        this.sink = sink;
        this.reservations = new Reservation[capacity];
    }

    public long reserve(long start, long end) {
        synchronized (reservations) {
            if (start >= end || start < 0) {
                throw new IllegalArgumentException("Invalid reservation interval");
            }
            for (Reservation res : reservations) {
                if (res != null && Math.max(res.start, start) < Math.min(res.end, end)) {
                    return -1;
                }
            }
            long id = reserveId();
            reservations[(int) (id % reservations.length)] = new Reservation(id, start, end);
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (reservations) {
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i] != null && reservations[i].id == reservationId) {
                    reservations[i] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public void flush() throws InterruptedException {
        long targetRevision = committedRevision;
        synchronized (reservations) {
            if (!flusherClaimed) {
                flusherClaimed = true;
                while (durableRevision < targetRevision) {
                    if (persistenceFailure != null) {
                        flusherClaimed = false;
                        throw persistenceFailure.cause;
                    }
                    reservations.wait();
                }
                flusherClaimed = false;
            } else {
                while (durableRevision < targetRevision) {
                    if (persistenceFailure != null) {
                        throw persistenceFailure.cause;
                    }
                    reservations.wait();
                }
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (reservations) {
            return new ScheduleSnapshot(reservations.clone(), committedRevision, durableRevision, flusherClaimed, persistenceFailure);
        }
    }

    private synchronized long reserveId() {
        return ++committedRevision;
    }

    public static class ScheduleSnapshot {
        private final Reservation[] reservations;
        private final long committedRevision;
        private final long durableRevision;
        private final boolean flusherClaimed;
        private final PersistenceFailure persistenceFailure;

        public ScheduleSnapshot(Reservation[] reservations, long committedRevision, long durableRevision, boolean flusherClaimed, PersistenceFailure persistenceFailure) {
            this.reservations = reservations;
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherClaimed = flusherClaimed;
            this.persistenceFailure = persistenceFailure;
        }

        public Reservation[] getReservations() {
            return reservations;
        }

        public long getCommittedRevision() {
            return committedRevision;
        }

        public long getDurableRevision() {
            return durableRevision;
        }

        public boolean isFlusherClaimed() {
            return flusherClaimed;
        }

        public PersistenceFailure getPersistenceFailure() {
            return persistenceFailure;
        }
    }

    static class Reservation {
        long id, start, end;

        public Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    static class PersistenceFailure {
        Throwable cause;

        public PersistenceFailure(Throwable cause) {
            this.cause = cause;
        }
    }
}
