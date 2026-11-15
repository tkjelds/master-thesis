```java
class VersionedSlotManager {
    private static final class Slot {
        long start, end, id;
        Slot(long start, long end, long id) { this.start = start; this.end = end; this.id = id; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Slot)) return false;
            Slot slot = (Slot) o;
            return start == slot.start && end == slot.end && id == slot.id;
        }
    }

    private static final class Snapshot {
        long revision;
        Slot[] slots;
        Snapshot(long revision, Slot[] slots) { this.revision = revision; this.slots = slots; }
    }

    private static final class MutationResult {
        boolean success;
        long observedRevision;
        long reservedId = -1;

        MutationResult(boolean success, long observedRevision) { this.success = success; this.observedRevision = observedRevision; }
        MutationResult(boolean success, long observedRevision, long reservedId) { this.success = success; this.observedRevision = observedRevision; this.reservedId = reservedId; }
    }

    private long revision;
    private Slot[] slots;

    public VersionedSlotManager() {
        this.revision = 0;
        this.slots = new Slot[0];
    }

    public Snapshot getSnapshot() {
        synchronized (this) {
            return new Snapshot(revision, slots.clone());
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) throw new IllegalArgumentException("Invalid interval");
        if (expectedRevision < 0) throw new IllegalArgumentException("Negative expected revision");

        long observedRevision;
        synchronized (this) {
            if (revision != expectedRevision) return new MutationResult(false, observedRevision = revision);
            Slot[] newSlots = new Slot[slots.length + 1];
            System.arraycopy(slots, 0, newSlots, 0, slots.length);
            newSlots[slots.length] = new Slot(start, end, Long.MIN_VALUE); // Placeholder for reserved ID
            boolean conflict = false;
            for (Slot slot : slots) {
                if (start <= slot.end && end >= slot.start) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) return new MutationResult(false, observedRevision = revision);
            for (int i = 0; i < newSlots.length; i++) {
                if (newSlots[i].id == Long.MIN_VALUE) {
                    newSlots[i] = new Slot(start, end, ++revision);
                    break;
                }
            }
            slots = newSlots;
            return new MutationResult(true, observedRevision = revision, ++revision);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) throw new IllegalArgumentException("Negative expected revision");

        long observedRevision;
        synchronized (this) {
            if (revision != expectedRevision) return new MutationResult(false, observedRevision = revision);
            Slot[] newSlots = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].id == reservationId) continue;
                newSlots[i] = slots[i];
            }
            boolean removed = false;
            for (int i = 0; i < newSlots.length; ) {
                if (newSlots[i].id == Long.MIN_VALUE) {
                    System.arraycopy(newSlots, i + 1, newSlots, i, newSlots.length - i - 1);
                    removed = true;
                } else {
                    i++;
                }
            }
            if (!removed) return new MutationResult(false, observedRevision = revision);
            slots = newSlots;
            return new MutationResult(true, observedRevision = revision, ++revision);
        }
    }
}
```
