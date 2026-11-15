@VersionedSlotManager.ThreadSafe
public class VersionedSlotManager {
    @interface ThreadSafe {}

    private static final long MAX_REVISION = Long.MAX_VALUE - 10;
    private static final long MAX_ID = Long.MAX_VALUE / 2;

    private volatile Slot[] slots;
    private volatile long revision;
    private volatile long nextId;

    public VersionedSlotManager() {
        this.slots = new Slot[1];
        this.revision = 0;
        this.nextId = 1;
    }

    public Snapshot getSnapshot() {
        synchronized (this) {
            Slot[] snapshotSlots = new Slot[slots.length];
            System.arraycopy(slots, 0, snapshotSlots, 0, slots.length);
            return new Snapshot(snapshotSlots, revision);
        }
    }

    public ReservationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end || start < 0 || end < 0) {
            throw new IllegalArgumentException("Invalid interval");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Negative expected revision");
        }

        Slot[] currentSlots;
        long observedRevision;

        synchronized (this) {
            if (revision != expectedRevision) {
                return new ReservationResult(STALE_REVISION, revision);
            }

            currentSlots = slots;
            observedRevision = revision;

            // Check for conflicts
            boolean conflict = false;
            for (Slot slot : currentSlots) {
                if (slot != null && !(end <= slot.start || start >= slot.end)) {
                    conflict = true;
                    break;
                }
            }

            if (conflict) {
                return new ReservationResult(CONFLICT, revision);
            }

            // Check for overflow
            if (nextId > MAX_ID) {
                throw new IllegalStateException("ID space exhausted");
            }

            // Prepare new slots array if needed
            Slot[] newSlots;
            int newCapacity = currentSlots.length * 2;
            if (currentSlots.length == newCapacity) {
                newSlots = new Slot[newCapacity];
                System.arraycopy(currentSlots, 0, newSlots, 0, currentSlots.length);
            } else {
                newSlots = currentSlots;
            }

            // Insert new slot
            Slot[] tempSlots = new Slot[newCapacity];
            int pos = 0;
            for (Slot slot : currentSlots) {
                if (slot != null && slot.start < start) {
                    tempSlots[pos++] = slot;
                } else if (slot != null && slot.start >= start) {
                    tempSlots[pos++] = new Slot(start, end);
                    while (pos < newCapacity && slot != null) {
                        tempSlots[pos++] = slot;
                        slot = currentSlots[pos];
                    }
                    break;
                } else {
                    tempSlots[pos++] = slot;
                }
            }

            // Update slots and revision
            slots = tempSlots;
            revision++;
            nextId++;

            return new ReservationResult(SUCCESS, observedRevision, nextId - 1);
        }
    }

    public CancellationResult cancel(long reservationId, long expectedRevision) {
        if (reservationId < 1 || expectedRevision < 0) {
            throw new IllegalArgumentException("Invalid arguments");
        }

        Slot[] currentSlots;
        long observedRevision;

        synchronized (this) {
            if (revision != expectedRevision) {
                return new CancellationResult(STALE_REVISION, revision);
            }

            currentSlots = slots;
            observedRevision = revision;

            // Find and remove the slot
            Slot[] newSlots = new Slot[currentSlots.length];
            int pos = 0;
            boolean found = false;

            for (Slot slot : currentSlots) {
                if (slot != null && slot.id == reservationId) {
                    found = true;
                    continue; // Skip this slot
                }
                newSlots[pos++] = slot;
            }

            if (!found) {
                return new CancellationResult(NOT_FOUND, revision);
            }

            // Update slots and revision
            slots = newSlots;
            revision++;
            return new CancellationResult(SUCCESS, observedRevision);
        }
    }

    private static class Slot {
        final long id;
        final long start;
        final long end;

        Slot(long start, long end) {
            this.id = -1; // Will be set by reserve
            this.start = start;
            this.end = end;
        }

        Slot(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static class Snapshot {
        private final Slot[] slots;
        private final long revision;

        Snapshot(Slot[] slots, long revision) {
            this.slots = slots;
            this.revision = revision;
        }

        public Slot[] getSlots() {
            return slots;
        }

        public long getRevision() {
            return revision;
        }
    }

    private static class ReservationResult {
        public static final int SUCCESS = 0;
        public static final int STALE_REVISION = -1;
        public static final int CONFLICT = -2;

        private final int status;
        private final long observedRevision;
        private final long id;

        ReservationResult(int status, long observedRevision) {
            this.status = status;
            this.observedRevision = observedRevision;
            this.id = -1;
        }

        ReservationResult(int status, long observedRevision, long id) {
            this.status = status;
            this.observedRevision = observedRevision;
            this.id = id;
        }

        public int getStatus() {
            return status;
        }

        public long getObservedRevision() {
            return observedRevision;
        }

        public long getId() {
            return id;
        }
    }

    private static class CancellationResult {
        public static final int SUCCESS = 0;
        public static final int STALE_REVISION = -1;
        public static final int NOT_FOUND = -2;

        private final int status;
        private final long observedRevision;

        CancellationResult(int status, long observedRevision) {
            this.status = status;
            this.observedRevision = observedRevision;
        }

        public int getStatus() {
            return status;
        }

        public long getObservedRevision() {
            return observedRevision;
        }
    }
}
