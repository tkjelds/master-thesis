@PersistedScheduleContext.ThreadSafe
public class PersistedScheduleContext {
    @interface ThreadSafe {}

    private class Reservation {
        long start;
        long end;

        Reservation(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private class Snapshot {
        List<Reservation> reservations;
        long committedRevision;
        long durableRevision;
        boolean flusherClaimed;
        Throwable persistenceFailure;

        Snapshot(List<Reservation> reservations, long committedRevision, long durableRevision, boolean flusherClaimed) {
            this.reservations = new ArrayList<>(reservations);
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherClaimed = flusherClaimed;
            this.persistenceFailure = null;
        }

        Snapshot(List<Reservation> reservations, long committedRevision, long durableRevision, boolean flusherClaimed, Throwable persistenceFailure) {
            this(reservations, committedRevision, durableRevision, flusherClaimed);
            this.persistenceFailure = persistenceFailure;
        }
    }

    private final List<Reservation> reservations = new ArrayList<>();
    private long committedRevision = 0;
    private long durableRevision = 0;
    private boolean flusherClaimed = false;
    private Throwable persistenceFailure = null;

    public synchronized long reserve(long start, long end) {
        if (start >= end || start < 0 || end < 0) {
            throw new IllegalArgumentException("Invalid reservation: " + start + "-" + end);
        }
        for (Reservation r : reservations) {
            if (!(r.end <= start || end <= r.start)) {
                return -1;
            }
        }
        long id = committedRevision + 1;
        reservations.add(new Reservation(start, end));
        committedRevision++;
        return id;
    }

    public synchronized boolean cancel(long reservationId) {
        if (reservationId <= committedRevision || reservationId < 0) {
            return false;
        }
        boolean existed = reservations.removeIf(r -> r.start == reservationId - 1);
        if (existed) {
            committedRevision++;
        }
        return existed;
    }

    public synchronized void flush() throws InterruptedException {
        long targetRevision = committedRevision;
        if (targetRevision <= durableRevision) {
            return;
        }
        while (!flusherClaimed && targetRevision > durableRevision) {
            this.wait();
        }
        if (persistenceFailure != null) {
            throw new IllegalStateException("Persistence failure: " + persistenceFailure.getMessage());
        }
        flusherClaimed = true;
        List<Reservation> snapshotReservations = new ArrayList<>(reservations);
        long snapshotRevision = committedRevision;
        flusherClaimed = false;
        notifyAll();
        try {
            scheduleSink.persist(new Snapshot(snapshotReservations, snapshotRevision, targetRevision, false));
        } catch (RuntimeException e) {
            persistenceFailure = e;
            flusherClaimed = false;
            notifyAll();
            throw new IllegalStateException("Persistence failure: " + e.getMessage());
        }
    }

    public synchronized void snapshot() {
        List<Reservation> reservationsSnapshot = new ArrayList<>(reservations);
        long committedSnapshotRevision = committedRevision;
        long durableSnapshotRevision = durableRevision;
        boolean flusherClaimedSnapshot = flusherClaimed;
        Throwable persistenceFailureSnapshot = persistenceFailure != null ? new RuntimeException(persistenceFailure) : null;

        ScheduleSnapshot snapshot = new ScheduleSnapshot(reservationsSnapshot, committedSnapshotRevision, durableSnapshotRevision, flusherClaimedSnapshot, persistenceFailureSnapshot);
        if (persistenceFailure != null) {
            throw new IllegalStateException("Persistence failure: " + persistenceFailure.getMessage());
        }
    }

    public interface ScheduleSink {
        void persist(ScheduleSnapshot snapshot);
    }

    public static class ScheduleSnapshot {
        List<Reservation> reservations;
        long committedRevision;
        long durableRevision;
        boolean flusherClaimed;
        Throwable persistenceFailure;

        ScheduleSnapshot(List<Reservation> reservations, long committedRevision, long durableRevision, boolean flusherClaimed) {
            this(reservations, committedRevision, durableRevision, flusherClaimed, null);
        }

        ScheduleSnapshot(List<Reservation> reservations, long committedRevision, long durableRevision, boolean flusherClaimed, Throwable persistenceFailure) {
            this.reservations = new ArrayList<>(reservations);
            this.committedRevision = committedRevision;
            this.durableRevision = durableRevision;
            this.flusherClaimed = flusherClaimed;
            this.persistenceFailure = persistenceFailure != null ? new RuntimeException(persistenceFailure) : null;
        }
    }

    private ScheduleSink scheduleSink;

    public PersistedScheduleContext(ScheduleSink scheduleSink) {
        this.scheduleSink = scheduleSink;
    }
}
