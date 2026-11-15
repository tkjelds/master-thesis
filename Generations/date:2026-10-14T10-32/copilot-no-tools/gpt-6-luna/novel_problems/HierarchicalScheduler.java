public final class HierarchicalScheduler {
    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;

 private HierarchicalScheduler[] children;
 private int childCount;

 private Entry[] entries;
 private int entryCount;

 public HierarchicalScheduler() {
     this.coordinator = new Coordinator();
     this.parent = null;
     this.name = null;
     this.path = "/";
     this.children = new HierarchicalScheduler[0];
     this.entries = new Entry[0];
 }

 private HierarchicalScheduler(
         Coordinator coordinator,
         HierarchicalScheduler parent,
         String name,
         String path) {
     this.coordinator = coordinator;
     this.parent = parent;
     this.name = name;
     this.path = path;
     this.children = new HierarchicalScheduler[0];
     this.entries = new Entry[0];
 }

 public HierarchicalScheduler child(String name) {
     if (name == null || name.length() == 0) {
         throw new IllegalArgumentException("Child name must be nonempty");
     }

     synchronized (coordinator.monitor) {
         for (int i = 0; i < childCount; i++) {
             if (children[i].name.equals(name)) {
                 return children[i];
             }
         }

         String escapedName = name.replace("%", "%25").replace("/", "%2F");
         String childPath = path.equals("/") ? path + escapedName : path + "/" + escapedName;
         HierarchicalScheduler child =
                 new HierarchicalScheduler(coordinator, this, name, childPath);

         if (childCount == children.length) {
             children = grow(children);
         }
         children[childCount++] = child;
         return child;
     }
 }

 public long reserve(long start, long end) {
     if (start >= end) {
         throw new IllegalArgumentException("Reservation interval must have start < end");
     }

     synchronized (coordinator.monitor) {
         for (HierarchicalScheduler node = parent; node != null; node = node.parent) {
             if (node.hasOverlappingEntry(start, end)) {
                 return -1L;
             }
         }

         if (hasOverlapInSubtree(this, start, end)) {
             return -1L;
         }

         long id = coordinator.nextId;
         if (id <= 0L) {
             throw new IllegalStateException("Reservation ID space exhausted");
         }

         Entry entry = new Entry(id, start, end);
         if (entryCount == entries.length) {
             entries = grow(entries);
         }
         entries[entryCount++] = entry;

         coordinator.nextId = id == Long.MAX_VALUE ? 0L : id + 1L;
         return id;
     }
 }

 public boolean cancel(long reservationId) {
     synchronized (coordinator.monitor) {
         for (int i = 0; i < entryCount; i++) {
             if (entries[i].id == reservationId) {
                 int remaining = entryCount - i - 1;
                 if (remaining > 0) {
                     System.arraycopy(entries, i + 1, entries, i, remaining);
                 }
                 entries[--entryCount] = null;
                 return true;
             }
         }
         return false;
     }
 }

 public Snapshot snapshot() {
     synchronized (coordinator.monitor) {
         int nodeCount = 0;
         int reservationCount = 0;
         HierarchicalScheduler[] stack = new HierarchicalScheduler[16];
         int stackSize = 1;
         stack[0] = this;

         while (stackSize > 0) {
             HierarchicalScheduler node = stack[--stackSize];
             if (nodeCount == Integer.MAX_VALUE
                     || reservationCount > Integer.MAX_VALUE - node.entryCount) {
                 throw new IllegalStateException("Snapshot is too large");
             }
             nodeCount++;
             reservationCount += node.entryCount;

             for (int i = 0; i < node.childCount; i++) {
                 if (stackSize == stack.length) {
                     stack = grow(stack);
                 }
                 stack[stackSize++] = node.children[i];
             }
         }

         String[] nodePaths = new String[nodeCount];
         Reservation[] reservations = new Reservation[reservationCount];
         stack = new HierarchicalScheduler[nodeCount];
         stackSize = 1;
         stack[0] = this;
         int pathIndex = 0;
         int reservationIndex = 0;

         while (stackSize > 0) {
             HierarchicalScheduler node = stack[--stackSize];
             nodePaths[pathIndex++] = node.path;

             for (int i = 0; i < node.entryCount; i++) {
                 Entry entry = node.entries[i];
                 reservations[reservationIndex++] =
                         new Reservation(entry.id, entry.start, entry.end, node.path);
             }

             for (int i = 0; i < node.childCount; i++) {
                 stack[stackSize++] = node.children[i];
             }
         }

         return new Snapshot(nodePaths, reservations);
     }
 }

 private boolean hasOverlappingEntry(long start, long end) {
     for (int i = 0; i < entryCount; i++) {
         Entry entry = entries[i];
         if (start < entry.end && entry.start < end) {
             return true;
         }
     }
     return false;
 }

 private static boolean hasOverlapInSubtree(
         HierarchicalScheduler startNode,
         long start,
         long end) {
     HierarchicalScheduler[] stack = new HierarchicalScheduler[16];
     int stackSize = 1;
     stack[0] = startNode;

     while (stackSize > 0) {
         HierarchicalScheduler node = stack[--stackSize];
         if (node.hasOverlappingEntry(start, end)) {
             return true;
         }

         for (int i = 0; i < node.childCount; i++) {
             if (stackSize == stack.length) {
                 stack = grow(stack);
             }
             stack[stackSize++] = node.children[i];
         }
     }
     return false;
 }

 private static HierarchicalScheduler[] grow(HierarchicalScheduler[] array) {
     if (array.length >= Integer.MAX_VALUE / 2) {
         throw new IllegalStateException("Tree is too large");
     }
     HierarchicalScheduler[] grown =
             new HierarchicalScheduler[array.length == 0 ? 1 : array.length * 2];
     System.arraycopy(array, 0, grown, 0, array.length);
     return grown;
 }

 private static Entry[] grow(Entry[] array) {
     if (array.length >= Integer.MAX_VALUE / 2) {
         throw new IllegalStateException("Too many reservations");
     }
     Entry[] grown = new Entry[array.length == 0 ? 1 : array.length * 2];
     System.arraycopy(array, 0, grown, 0, array.length);
     return grown;
 }

 public static final class Reservation {
     public final long id;
     public final long start;
     public final long end;
     public final String nodePath;

     private Reservation(long id, long start, long end, String nodePath) {
         this.id = id;
         this.start = start;
         this.end = end;
         this.nodePath = nodePath;
     }
 }

 public static final class Snapshot {
     public final String[] nodePaths;
     public final Reservation[] reservations;

     private Snapshot(String[] nodePaths, Reservation[] reservations) {
         this.nodePaths = nodePaths;
         this.reservations = reservations;
     }
 }

 private static final class Coordinator {
     private final Object monitor = new Object();
     private long nextId = 1L;
 }

 private static final class Entry {
     private final long id;
     private final long start;
     private final long end;

     private Entry(long id, long start, long end) {
         this.id = id;
         this.start = start;
         this.end = end;
     }
 }

}
