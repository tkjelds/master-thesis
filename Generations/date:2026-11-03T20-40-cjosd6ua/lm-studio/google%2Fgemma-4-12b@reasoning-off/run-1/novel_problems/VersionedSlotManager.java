public class VersionedSlotManager {

    private static class Slot {
        long start;
        long end;
        long id;

        Slot(long start, long end) {
            this.start = start;
            this.end = end;
        }

        Slot(long start, long end, long id) {
            this.start = start;
            this.end = end;
            this.id = id;
        }
    }

    public static class Snapshot {
        private final Slot[] slots;
        private final long revision;

        Snapshot(Slot[] slots, long revision) {
            this.slots = slots;
            this.revision = revision;
        }

        Slot[] getSlots() { return slots; }
        long getRevision() { return revision; }
    }

    public static class ReservationResult {
        public enum Status { SUCCESS, STALE_REVISION, CONFLICT }
        final Status status;
        final long revision;
        final long id;

        ReservationResult(Status status, long revision, long id) {
            this.status = status;
            this.revision = revision;
            this.id = id;
        }
    }

    public static class CancelResult {
        public enum Status { SUCCESS, STALE_REVISION, NOT_FOUND }
        final Status status;

        CancelResult(Status status) {
            this.status = status;
        }
    }

    private long revision = 0;
    private long nextId = 1;
    private Slot[] slots = new Slot[0];

    public Snapshot getSnapshot() {
        long currentRev;
        Slot[] currentSlots;
        synchronized (this) {
            currentRev = this.revision;
            currentSlots = this.slots;
        }
        
        Slot[] copy = new Slot[currentSlots.length];
        for (int i = 0; i < currentSlots.length; i++) {
            copy[i] = new Slot(currentSlots[i].start, currentSlots[i].end, currentSlots[i].id);
        }
        return new Snapshot(copy, currentRev);
    }

    public ReservationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) throw new IllegalArgumentException("Invalid interval");
        if (expectedRevision < 0) throw new IllegalArgumentException("Negative revision");

        synchronized (this) {
            if (expectedRevision != this.revision) {
                return new ReservationResult(ReservationResult.Status.STALE_REVISION, this.revision, -1);
            }

            for (int i = 0; i < slots.length; i++) {
                if (start < slots[i].end && end > slots[i].start) {
                    return new ReservationResult(ReservationResult.Status.CONFLICT, this.revision, -1);
                }
            }

            if (this.revision == Long.MAX_VALUE || nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("Overflow");
            }

            long id = nextId++;
            Slot newSlot = new Slot(start, end, id);
            
            // Check for ID overflow before incrementing (not actually possible given logic but per req)
            if (nextId == Long.MAX_VALUE && id == Long.MAX_VALUE) {
                 // This would be handled by the check before creation
            }

            Slot[] newSlots = new Slot[slots.length + 1];
            System.arraycopy(slots, 0, newSlots, 0, slots.length);
            newSlots[slots.length] = newSlot;

            this.slots = newSlots;
            this.revision++;
            return new ReservationResult(ReservationResult.Status.SUCCESS, this.revision, id);
        }
    }

    public CancelResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) throw new IllegalArgumentException("Negative revision");

        synchronized (this) {
            if (expectedRevision != this.revision) {
                return new CancelResult(CancelResult.Status.STALE_REVISION);
            }

            int index = -1;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].id == reservationId) {
                    index = i;
                    break;
                }
            }

            if (index == -1) {
                return new CancelResult(CancelResult.Status.NOT_FOUND);
            }

            Slot[] newSlots = new Slot[slots.length - 1];
            for (int i = 0, j = 0; i < slots.length; i++) {
                if (i != index) {
                    newSlots[j++] = slots[i];
                }
            }

            this.slots = newSlots;
            // Note: Revision is NOT decremented on cancel/deletion as per requirements.
            return new CancelResult(CancelResult.Status.SUCCESS);
        }
    }
}
