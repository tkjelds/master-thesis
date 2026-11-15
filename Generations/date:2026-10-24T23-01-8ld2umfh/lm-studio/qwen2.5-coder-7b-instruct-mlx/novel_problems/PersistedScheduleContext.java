@PersistedScheduleContext.ThreadSafe
public class PersistedScheduleContext {
    @interface ThreadSafe {}

    private long committedRevision = 0;
    private long durableRevision = 0;
    private boolean flushing = false;
    private Exception persistenceFailure = null;
    private long nextReservationId = 1;

    public void reserve(long start, long end) {
        if (start >= end || start < 0 || nextReservationId <= 0) {
            throw new IllegalStateException("Invalid inputs or overflow");
        }

        synchronized (this) {
            while (flushing) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Interrupted while waiting for flush");
                }
            }

            long currentRevision = committedRevision;
            if (currentRevision != durableRevision) {
                throw new IllegalStateException("Durable state changed while waiting for flush");
            }

            if (checkConflicts(currentRevision, start, end)) {
                return;
            }

            long id = nextReservationId++;
            reserveImpl(id, start, end);
        }
    }

    public boolean cancel(long reservationId) {
        if (reservationId <= 0) {
            throw new IllegalStateException("Invalid reservation ID");
        }

        synchronized (this) {
            while (flushing) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Interrupted while waiting for flush");
                }
            }

            long currentRevision = committedRevision;
            if (currentRevision != durableRevision) {
                throw new IllegalStateException("Durable state changed while waiting for flush");
            }

            return cancelImpl(reservationId);
        }
    }

    public void flush() {
        long targetRevision = committedRevision;

        synchronized (this) {
            while (flushing) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Interrupted while waiting for flush");
                }
            }

            flushing = true;
        }

        try {
            ScheduleSnapshot snapshot = null;

            synchronized (this) {
                while (flushing) {
                    notifyAll();
                }

                if (persistenceFailure != null) {
                    throw persistenceFailure;
                }

                snapshot = new ScheduleSnapshot(committedRevision, committedReservationMap);
                durableRevision = targetRevision;
            }

            sink.persist(snapshot);
        } catch (Exception e) {
            persistenceFailure = e;
            throw new RuntimeException("Persistence failure", e);
        } finally {
            synchronized (this) {
                flushing = false;
                notifyAll();
            }
        }
    }

    public ScheduleSnapshot snapshot() {
        synchronized (this) {
            while (flushing) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Interrupted while waiting for flush");
                }
            }

            long currentRevision = committedRevision;
            if (currentRevision != durableRevision) {
                throw new IllegalStateException("Durable state changed while waiting for flush");
            }

            return new ScheduleSnapshot(currentRevision, committedReservationMap);
        }
    }

    private boolean checkConflicts(long revision, long start, long end) {
        for (ScheduleEntry entry : committedReservationMap.values()) {
            if (entry.end > start && entry.start < end) {
                return true;
            }
        }
        return false;
    }

    private void reserveImpl(long id, long start, long end) {
        committedReservationMap.put(id, new ScheduleEntry(start, end));
    }

    private boolean cancelImpl(long reservationId) {
        boolean existed = committedReservationMap.remove(reservationId) != null;
        return existed;
    }

    private static class ScheduleEntry {
        long start;
        long end;

        ScheduleEntry(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static class ScheduleSnapshot {
        long revision;
        Map<Long, ScheduleEntry> reservations;

        ScheduleSnapshot(long revision, Map<Long, ScheduleEntry> reservations) {
            this.revision = revision;
            this.reservations = new HashMap<>(reservations);
        }
    }

    private final Map<Long, ScheduleEntry> committedReservationMap = new HashMap<>();
    private final ScheduleSink sink;
}
