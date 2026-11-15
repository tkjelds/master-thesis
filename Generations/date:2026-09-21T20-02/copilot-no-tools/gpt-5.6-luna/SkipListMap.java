@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

 private final Node<K, V> head;
 private int size;
 private long randomState;

 /*
  * This map is unbounded, so insertion never blocks because of capacity.
  * Reads and removals on an empty map return null, and clear is non-blocking.
  */
 public SkipListMap() {
     head = new Node<K, V>(MAX_LEVEL, null, null);
     randomState = 0x9E3779B97F4A7C15L;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized V get(K key) {
     requireKey(key);

     Node<K, V> node = findNode(key);
     return node == null ? null : node.value;
 }

 public synchronized boolean containsKey(K key) {
     requireKey(key);
     return findNode(key) != null;
 }

 public synchronized V put(K key, V value) {
     requireKey(key);

     Node<K, V>[] update = newNodeArray(MAX_LEVEL);
     Node<K, V> current = head;

     for (int level = MAX_LEVEL - 1; level >= 0; level--) {
         Node<K, V> next = current.next[level];

         while (next != null && next.key.compareTo(key) < 0) {
             current = next;
             next = current.next[level];
         }

         update[level] = current;
     }

     Node<K, V> existing = current.next[0];
     if (existing != null && existing.key.compareTo(key) == 0) {
         V previous = existing.value;
         existing.value = value;
         return previous;
     }

     int nodeLevel = randomLevel();
     Node<K, V> inserted = new Node<K, V>(nodeLevel, key, value);

     for (int level = 0; level < nodeLevel; level++) {
         inserted.next[level] = update[level].next[level];
         update[level].next[level] = inserted;
     }

     size++;
     notifyAll();
     return null;
 }

 public synchronized V remove(K key) {
     requireKey(key);

     Node<K, V>[] update = newNodeArray(MAX_LEVEL);
     Node<K, V> current = head;

     for (int level = MAX_LEVEL - 1; level >= 0; level--) {
         Node<K, V> next = current.next[level];

         while (next != null && next.key.compareTo(key) < 0) {
             current = next;
             next = current.next[level];
         }

         update[level] = current;
     }

     Node<K, V> target = current.next[0];
     if (target == null || target.key.compareTo(key) != 0) {
         return null;
     }

     for (int level = 0; level < target.next.length; level++) {
         if (update[level].next[level] == target) {
             update[level].next[level] = target.next[level];
         }
     }

     size--;
     notifyAll();
     return target.value;
 }

 public synchronized void clear() {
     for (int level = 0; level < MAX_LEVEL; level++) {
         head.next[level] = null;
     }

     size = 0;
     notifyAll();
 }

 public synchronized K firstKey() {
     Node<K, V> first = head.next[0];
     return first == null ? null : first.key;
 }

 public synchronized K lastKey() {
     Node<K, V> current = head;

     for (int level = MAX_LEVEL - 1; level >= 0; level--) {
         Node<K, V> next = current.next[level];

         while (next != null) {
             current = next;
             next = current.next[level];
         }
     }

     return current == head ? null : current.key;
 }

 private Node<K, V> findNode(K key) {
     Node<K, V> current = head;

     for (int level = MAX_LEVEL - 1; level >= 0; level--) {
         Node<K, V> next = current.next[level];

         while (next != null && next.key.compareTo(key) < 0) {
             current = next;
             next = current.next[level];
         }
     }

     Node<K, V> result = current.next[0];
     return result != null && result.key.compareTo(key) == 0 ? result : null;
 }

 private int randomLevel() {
     long value = randomState;
     value ^= value << 13;
     value ^= value >>> 7;
     value ^= value << 17;
     randomState = value;

     int level = 1;
     while ((value & 1L) != 0L && level < MAX_LEVEL) {
         level++;
         value >>>= 1;
     }

     return level;
 }

 private static void requireKey(Object key) {
     if (key == null) {
         throw new NullPointerException("key");
     }
 }

 @SuppressWarnings("unchecked")
 private static <K extends Comparable<? super K>, V> Node<K, V>[] newNodeArray(int length) {
     return (Node<K, V>[]) new Node<?, ?>[length];
 }

 private static final class Node<K, V> {
     private final K key;
     private V value;
     private final Node<K, V>[] next;

     @SuppressWarnings("unchecked")
     private Node(int level, K key, V value) {
         this.key = key;
         this.value = value;
         this.next = (Node<K, V>[]) new Node<?, ?>[level];
     }
 }

}
