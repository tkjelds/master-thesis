public final class SkipListSet<E extends Comparable<? super E>> {
    private static final int MAX_LEVEL = 32;

 private static final class Node {
     final Object value;
     final Node[] next;

     Node(Object value, int level) {
         this.value = value;
         this.next = new Node[level];
     }
 }

 private final Object mutex = new Object();
 private final Node head = new Node(null, MAX_LEVEL);
 private int size;
 private long randomState;

 public SkipListSet() {
     long seed = System.nanoTime();
     this.randomState = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
 }

 public boolean add(E value) {
     if (value == null) {
         throw new NullPointerException("value");
     }

     synchronized (mutex) {
         Node[] update = new Node[MAX_LEVEL];
         Node current = head;

         for (int level = MAX_LEVEL - 1; level >= 0; level--) {
             while (current.next[level] != null
                     && compare(current.next[level].value, value) < 0) {
                 current = current.next[level];
             }
             update[level] = current;
         }

         Node candidate = update[0].next[0];
         if (candidate != null && compare(candidate.value, value) == 0) {
             return false;
         }
         if (size == Integer.MAX_VALUE) {
             throw new IllegalStateException("SkipListSet is full");
         }

         int level = randomLevel();
         Node node = new Node(value, level);
         for (int i = 0; i < level; i++) {
             node.next[i] = update[i].next[i];
             update[i].next[i] = node;
         }
         size++;
         return true;
     }
 }

 public boolean contains(E value) {
     if (value == null) {
         return false;
     }

     synchronized (mutex) {
         Node current = head;
         for (int level = MAX_LEVEL - 1; level >= 0; level--) {
             while (current.next[level] != null
                     && compare(current.next[level].value, value) < 0) {
                 current = current.next[level];
             }
         }

         Node candidate = current.next[0];
         return candidate != null && compare(candidate.value, value) == 0;
     }
 }

 public boolean remove(E value) {
     if (value == null) {
         return false;
     }

     synchronized (mutex) {
         Node[] update = new Node[MAX_LEVEL];
         Node current = head;

         for (int level = MAX_LEVEL - 1; level >= 0; level--) {
             while (current.next[level] != null
                     && compare(current.next[level].value, value) < 0) {
                 current = current.next[level];
             }
             update[level] = current;
         }

         Node candidate = update[0].next[0];
         if (candidate == null || compare(candidate.value, value) != 0) {
             return false;
         }

         for (int level = 0; level < candidate.next.length; level++) {
             if (update[level].next[level] == candidate) {
                 update[level].next[level] = candidate.next[level];
             }
         }
         size--;
         return true;
     }
 }

 public int size() {
     synchronized (mutex) {
         return size;
     }
 }

 public boolean isEmpty() {
     synchronized (mutex) {
         return size == 0;
     }
 }

 public void clear() {
     synchronized (mutex) {
         for (int level = 0; level < MAX_LEVEL; level++) {
             head.next[level] = null;
         }
         size = 0;
     }
 }

 public Object[] toArray() {
     synchronized (mutex) {
         Object[] result = new Object[size];
         Node current = head.next[0];
         int index = 0;
         while (current != null) {
             result[index++] = current.value;
             current = current.next[0];
         }
         return result;
     }
 }

 @SuppressWarnings("unchecked")
 private int compare(Object left, E right) {
     return ((E) left).compareTo(right);
 }

 private int randomLevel() {
     long value = randomState;
     value ^= value << 13;
     value ^= value >>> 7;
     value ^= value << 17;
     randomState = value;

     int level = 1;
     while (level < MAX_LEVEL && (value & 1L) == 0L) {
         level++;
         value >>>= 1;
     }
     return level;
 }

}
