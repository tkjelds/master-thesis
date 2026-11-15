@PersistedScheduleContext.ThreadSafe
public class PersistedScheduleContext {
    @interface ThreadSafe {}

    private long committedRevision = 0;
    private volatile long durableRevision = 0;
    private ScheduleSnapshot currentSnapshot;
    private RuntimeException persistenceFailure;
    private final Object monitor = new Object();
    private boolean isFlusher;

    public PersistedScheduleContext() {
        this.currentSnapshot = new ScheduleSnapshot(committedRevision, committedRevision, false, null);
    }

    public long reserve(long start, long end) {
        synchronized (monitor) {
            if (persistenceFailure != null) {
                throw new IllegalStateException(persistenceFailure);
            }
            if (start >= end || start < 0) {
                throw new IllegalArgumentException("Invalid reservation interval");
            }
            for (Long existingEnd : currentSnapshot.getReservations()) {
                if (end > existingStart && start < existingEnd) {
                    return -1; // Conflict
                }
            }
            long reservationId = committedRevision++;
            currentSnapshot.getReservations().put(reservationId, end - 1);
            return reservationId;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (monitor) {
            if (persistenceFailure != null) {
                throw new IllegalStateException(persistenceFailure);
            }
            boolean existed = currentSnapshot.getReservations().remove(reservationId) != null;
            if (existed) {
                committedRevision++;
            }
            return existed;
        }
    }

    public void flush() throws InterruptedException {
        synchronized (monitor) {
            if (persistenceFailure != null) {
                throw new IllegalStateException(persistenceFailure);
            }
            long targetRevision = committedRevision;
            while (durableRevision < targetRevision) {
                if (!isFlusher) {
                    isFlusher = true;
                    try {
                        long snapshotRevision = committedRevision;
                        ScheduleSnapshot snapshot = new ScheduleSnapshot(snapshotRevision, committedRevision, false, currentSnapshot.getReservations());
                        while (durableRevision < snapshotRevision) {
                            monitor.wait();
                        }
                    } catch (RuntimeException e) {
                        persistenceFailure = e;
                        notifyAll();
                        throw new IllegalStateException("Persistence error", e);
                    } finally {
                        isFlusher = false;
                        notifyAll();
                    }
                } else {
                    monitor.wait();
                }
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (monitor) {
            if (persistenceFailure != null) {
                throw new IllegalStateException(persistenceFailure);
            }
            return new ScheduleSnapshot(durableRevision, committedRevision, isFlusher, currentSnapshot.getReservations());
        }
    }

    private static class ScheduleSnapshot {
        private final long committedRevision;
        private final long durableRevision;
        private final boolean isFlusher;
        private final java.util.Map<Long, Long> reservations;

        public ScheduleSnapshot(long committedRevision, long durableRevision, boolean isFlusher, java.util.Map<Long, Long> reservations) {
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.isFlusher = isFlusher;
            this.reservations = new java.util.HashMap<>(reservations);
        }

        public long getCommittedRevision() {
            return committedRevision;
        }

        public long getDurableRevision() {
            return durableRevision;
        }

        public boolean isFlusher() {
            return isFlusher;
        }

        public java.util.Map<Long, Long> getReservations() {
            return reservations;
        }
    }
}
