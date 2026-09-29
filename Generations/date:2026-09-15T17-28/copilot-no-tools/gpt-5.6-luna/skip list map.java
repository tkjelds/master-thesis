public final class ConcurrentSkipListMap<K extends Comparable<? super K>, V> {
    private static final int MAX_LEVEL = 32;

 private final Node<K, V> head;
 private int currentLevel;
 private int size;
 private int randomState = 0x6D2B79F5;

 private static final class Node<K, V> {
     final K key;
     V value;
     final Node<K, V>[] next;

     @SuppressWarnings("unchecked")
     Node(K key, V value, int level) {
         this.key = key;
         this.value = value;
         this.next = (Node<K, V>[]) new Node[level];
     }
 }

 public static final class Entry<K, V> {
     private final K key;
     private final V value;

     private Entry(K key, V value) {
         this.key = key;
         this.value = value;
     }

     public K getKey() {
         return key;
     }

     public V getValue() {
         return value;
     }
 }

 public ConcurrentSkipListMap() {
     head = new Node<K, V>(null, null, MAX_LEVEL);
     currentLevel = 1;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized boolean containsKey(K key) {
     return findNode(requireKey(key)) != null;
 }

 public synchronized V get(K key) {
     Node<K, V> node = findNode(requireKey(key));
     return node == null ? null : node.value;
 }

 public synchronized V put(K key, V value) {
     requireKey(key);
     requireValue(value);

     @SuppressWarnings("unchecked")
     Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];

     Node<K, V> predecessor = head;
     for (int level = currentLevel - 1; level >= 0; level--) {
         while (predecessor.next[level] != null
                 && predecessor.next[level].key.compareTo(key) < 0) {
             predecessor = predecessor.next[level];
         }
         update[level] = predecessor;
     }

     Node<K, V> existing = predecessor.next[0];
     if (existing != null && existing.key.compareTo(key) == 0) {
         V previous = existing.value;
         existing.value = value;
         return previous;
     }

     int nodeLevel = randomLevel();
     if (nodeLevel > currentLevel) {
         for (int level = currentLevel; level < nodeLevel; level++) {
             update[level] = head;
         }
         currentLevel = nodeLevel;
     }

     Node<K, V> node = new Node<K, V>(key, value, nodeLevel);
     for (int level = 0; level < nodeLevel; level++) {
         node.next[level] = update[level].next[level];
         update[level].next[level] = node;
     }

     size++;
     return null;
 }

 public synchronized V putIfAbsent(K key, V value) {
     requireKey(key);
     requireValue(value);

     Node<K, V> existing = findNode(key);
     if (existing != null) {
         return existing.value;
     }

     put(key, value);
     return null;
 }

 public synchronized V remove(K key) {
     requireKey(key);

     @SuppressWarnings("unchecked")
     Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];

     Node<K, V> predecessor = head;
     for (int level = currentLevel - 1; level >= 0; level--) {
         while (predecessor.next[level] != null
                 && predecessor.next[level].key.compareTo(key) < 0) {
             predecessor = predecessor.next[level];
         }
         update[level] = predecessor;
     }

     Node<K, V> target = predecessor.next[0];
     if (target == null || target.key.compareTo(key) != 0) {
         return null;
     }

     for (int level = 0; level < currentLevel; level++) {
         if (update[level].next[level] != target) {
             break;
         }
         update[level].next[level] = target.next[level];
     }

     while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
         currentLevel--;
     }

     size--;
     return target.value;
 }

 public synchronized boolean remove(K key, V value) {
     requireKey(key);
     requireValue(value);

     Node<K, V> node = findNode(key);
     if (node == null || !node.value.equals(value)) {
         return false;
     }

     remove(key);
     return true;
 }

 public synchronized boolean replace(K key, V oldValue, V newValue) {
     requireKey(key);
     requireValue(oldValue);
     requireValue(newValue);

     Node<K, V> node = findNode(key);
     if (node == null || !node.value.equals(oldValue)) {
         return false;
     }

     node.value = newValue;
     return true;
 }

 public synchronized V replace(K key, V value) {
     requireKey(key);
     requireValue(value);

     Node<K, V> node = findNode(key);
     if (node == null) {
         return null;
     }

     V previous = node.value;
     node.value = value;
     return previous;
 }

 public synchronized Entry<K, V> firstEntry() {
     Node<K, V> node = head.next[0];
     return node == null ? null : new Entry<K, V>(node.key, node.value);
 }

 public synchronized Entry<K, V> lastEntry() {
     Node<K, V> node = head;
     for (int level = currentLevel - 1; level >= 0; level--) {
         while (node.next[level] != null) {
             node = node.next[level];
         }
     }
     return node == head ? null : new Entry<K, V>(node.key, node.value);
 }

 public synchronized Entry<K, V> pollFirstEntry() {
     Node<K, V> node = head.next[0];
     if (node == null) {
         return null;
     }

     head.next[0] = node.next[0];
     for (int level = 1; level < currentLevel; level++) {
         if (head.next[level] == node) {
             head.next[level] = node.next[level];
         }
     }

     while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
         currentLevel--;
     }

     size--;
     return new Entry<K, V>(node.key, node.value);
 }

 public synchronized Entry<K, V> pollLastEntry() {
     if (size == 0) {
         return null;
     }

     @SuppressWarnings("unchecked")
     Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];

     Node<K, V> predecessor = head;
     for (int level = currentLevel - 1; level >= 0; level--) {
         while (predecessor.next[level] != null
                 && predecessor.next[level].next.length > level
                 && predecessor.next[level].next[level] != null) {
             predecessor = predecessor.next[level];
         }
         update[level] = predecessor;
     }

     Node<K, V> target = update[0].next[0];
     for (int level = 0; level < currentLevel; level++) {
         if (update[level].next[level] == target) {
             update[level].next[level] = target.next[level];
         }
     }

     while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
         currentLevel--;
     }

     size--;
     return new Entry<K, V>(target.key, target.value);
 }

 public synchronized void clear() {
     for (int level = 0; level < MAX_LEVEL; level++) {
         head.next[level] = null;
     }
     currentLevel = 1;
     size = 0;
 }

 @SuppressWarnings("unchecked")
 public synchronized Entry<K, V>[] snapshotEntries() {
     Entry<K, V>[] entries = (Entry<K, V>[]) new Entry[size];
     Node<K, V> node = head.next[0];
     int index = 0;

     while (node != null) {
         entries[index++] = new Entry<K, V>(node.key, node.value);
         node = node.next[0];
     }

     return entries;
 }

 private Node<K, V> findNode(K key) {
     Node<K, V> predecessor = head;

     for (int level = currentLevel - 1; level >= 0; level--) {
         while (predecessor.next[level] != null
                 && predecessor.next[level].key.compareTo(key) < 0) {
             predecessor = predecessor.next[level];
         }
     }

     Node<K, V> result = predecessor.next[0];
     return result != null && result.key.compareTo(key) == 0 ? result : null;
 }

 private int randomLevel() {
     randomState ^= randomState << 13;
     randomState ^= randomState >>> 17;
     randomState ^= randomState << 5;

     int level = 1;
     while (level < MAX_LEVEL && (randomState & 1) == 0) {
         level++;
         randomState >>>= 1;
     }
     return level;
 }

 private static <T> T requireKey(T value) {
     if (value == null) {
         throw new NullPointerException("key");
     }
     return value;
 }

 private static <T> T requireValue(T value) {
     if (value == null) {
         throw new NullPointerException("value");
     }
     return value;
 }

}
