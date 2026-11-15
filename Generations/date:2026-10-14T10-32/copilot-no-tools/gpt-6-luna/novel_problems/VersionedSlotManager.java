public final class VersionedSlotManager {
    public enum Status {
        SUCCESS,
        STALE_REVISION,
        CONFLICT,
        NOT_FOUND
    }

 public static final class Slot {
     public final long reservationId;
     public final long start;
     public final long end;

     public Slot(long reservationId, long start, long end) {
         this.reservationId = reservationId;
         this.start = start;
         this.end = end;
     }
 }

 public static final class Snapshot {
     public final long revision;
     public final Slot[] slots;

     private Snapshot(long revision, Slot[] slots) {
         this.revision = revision;
         this.slots = slots;
     }
 }

 public static final class MutationResult {
     public final Status status;
     public final long revision;
     public final long reservationId;

     private MutationResult(Status status, long revision, long reservationId) {
         this.status = status;
         this.revision = revision;
         this.reservationId = reservationId;
     }
 }

 private final Object mutex = new Object();
 private Slot[] slots = new Slot[0];
 private long revision = 0;
 private long nextReservationId = 1;
 private boolean reservationIdsExhausted = false;

 public Snapshot getSnapshot() {
     synchronized (mutex) {
         Slot[] copiedSlots = new Slot[slots.length];
         for (int i = 0; i < slots.length; i++) {
             Slot slot = slots[i];
             copiedSlots[i] = new Slot(slot.reservationId, slot.start, slot.end);
         }
         return new Snapshot(revision, copiedSlots);
     }
 }

 public MutationResult reserve(long start, long end, long expectedRevision) {
     if (start >= end) {
         throw new IllegalArgumentException("start must be less than end");
     }
     if (expectedRevision < 0) {
         throw new IllegalArgumentException("expectedRevision must be nonnegative");
     }

     synchronized (mutex) {
         if (expectedRevision != revision) {
             return new MutationResult(Status.STALE_REVISION, revision, 0);
         }

         for (int i = 0; i < slots.length; i++) {
             Slot slot = slots[i];
             if (start <= slot.end && slot.start <= end) {
                 return new MutationResult(Status.CONFLICT, revision, 0);
             }
         }

         if (revision == Long.MAX_VALUE) {
             throw new IllegalStateException("revision exhausted");
         }
         if (reservationIdsExhausted) {
             throw new IllegalStateException("reservation IDs exhausted");
         }

         long reservationId = nextReservationId;
         Slot newSlot = new Slot(reservationId, start, end);
         Slot[] updatedSlots = new Slot[slots.length + 1];
         for (int i = 0; i < slots.length; i++) {
             updatedSlots[i] = slots[i];
         }
         updatedSlots[slots.length] = newSlot;

         slots = updatedSlots;
         if (reservationId == Long.MAX_VALUE) {
             reservationIdsExhausted = true;
         } else {
             nextReservationId = reservationId + 1;
         }
         revision++;

         return new MutationResult(Status.SUCCESS, revision, reservationId);
     }
 }

 public MutationResult cancel(long reservationId, long expectedRevision) {
     if (expectedRevision < 0) {
         throw new IllegalArgumentException("expectedRevision must be nonnegative");
     }

     synchronized (mutex) {
         if (expectedRevision != revision) {
             return new MutationResult(Status.STALE_REVISION, revision, 0);
         }

         int slotIndex = -1;
         for (int i = 0; i < slots.length; i++) {
             if (slots[i].reservationId == reservationId) {
                 slotIndex = i;
                 break;
             }
         }
         if (slotIndex < 0) {
             return new MutationResult(Status.NOT_FOUND, revision, 0);
         }

         if (revision == Long.MAX_VALUE) {
             throw new IllegalStateException("revision exhausted");
         }

         Slot[] updatedSlots = new Slot[slots.length - 1];
         for (int i = 0, j = 0; i < slots.length; i++) {
             if (i != slotIndex) {
                 updatedSlots[j++] = slots[i];
             }
         }

         slots = updatedSlots;
         revision++;

         return new MutationResult(Status.SUCCESS, revision, reservationId);
     }
 }

}
