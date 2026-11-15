public final class SkipListMap<K extends Comparable<? super K>, V> {
    private static final int MAX_LEVEL = 32;

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

 private final Object lock = new Object();
 private final Node<K, V> head = new Node<>(null, null, MAX_LEVEL);
 private int currentLevel = 1;
 private int size;
 private int randomState = 0x6D2B79F5;

 public V get(K key) {
     requireKey(key);
     synchronized (lock) {
         Node<K, V> node = findNode(key);
         return node == null ? null : node.value;
     }
 }

 public boolean containsKey(K key) {
     requireKey(key);
     synchronized (lock) {
         return findNode(key) != null;
     }
 }

 public V put(K key, V value) {
     requireKey(key);
     requireValue(value);
     synchronized (lock) {
         Node<K, V>[] predecessors = newNodeArray();
         Node<K, V> node = head;

         for (int i = currentLevel - 1; i >= 0; i--) {
             while (node.next[i] != null
                     && node.next[i].key.compareTo(key) < 0) {
                 node = node.next[i];
             }
             predecessors[i] = node;
         }

         Node<K, V> existing = node.next[0];
         if (existing != null && existing.key.compareTo(key) == 0) {
             V previous = existing.value;
             existing.value = value;
             return previous;
         }

         int level = randomLevel();
         if (level > currentLevel) {
             for (int i = currentLevel; i < level; i++) {
                 predecessors[i] = head;
             }
             currentLevel = level;
         }

         Node<K, V> inserted = new Node<>(key, value, level);
         for (int i = 0; i < level; i++) {
             inserted.next[i] = predecessors[i].next[i];
             predecessors[i].next[i] = inserted;
         }
         size++;
         return null;
     }
 }

 public V remove(K key) {
     requireKey(key);
     synchronized (lock) {
         Node<K, V>[] predecessors = newNodeArray();
         Node<K, V> node = head;

         for (int i = currentLevel - 1; i >= 0; i--) {
             while (node.next[i] != null
                     && node.next[i].key.compareTo(key) < 0) {
                 node = node.next[i];
             }
             predecessors[i] = node;
         }

         Node<K, V> target = node.next[0];
         if (target == null || target.key.compareTo(key) != 0) {
             return null;
         }

         for (int i = 0; i < target.next.length; i++) {
             if (predecessors[i].next[i] == target) {
                 predecessors[i].next[i] = target.next[i];
             }
         }

         size--;
         while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
             currentLevel--;
         }
         return target.value;
     }
 }

 public int size() {
     synchronized (lock) {
         return size;
     }
 }

 public boolean isEmpty() {
     synchronized (lock) {
         return size == 0;
     }
 }

 public K firstKey() {
     synchronized (lock) {
         return head.next[0] == null ? null : head.next[0].key;
     }
 }

 public K lastKey() {
     synchronized (lock) {
         Node<K, V> node = head;
         for (int i = currentLevel - 1; i >= 0; i--) {
             while (node.next[i] != null) {
                 node = node.next[i];
             }
         }
         return node == head ? null : node.key;
     }
 }

 public void clear() {
     synchronized (lock) {
         for (int i = 0; i < MAX_LEVEL; i++) {
             head.next[i] = null;
         }
         currentLevel = 1;
         size = 0;
     }
 }

 private Node<K, V> findNode(K key) {
     Node<K, V> node = head;
     for (int i = currentLevel - 1; i >= 0; i--) {
         while (node.next[i] != null
                 && node.next[i].key.compareTo(key) < 0) {
             node = node.next[i];
         }
     }

     node = node.next[0];
     return node != null && node.key.compareTo(key) == 0 ? node : null;
 }

 @SuppressWarnings("unchecked")
 private Node<K, V>[] newNodeArray() {
     return (Node<K, V>[]) new Node[MAX_LEVEL];
 }

 private int randomLevel() {
     int bits = nextRandomInt();
     int level = 1;
     while ((bits & 1) == 0 && level < MAX_LEVEL) {
         level++;
         bits >>>= 1;
     }
     return level;
 }

 private int nextRandomInt() {
     int value = randomState;
     value ^= value << 13;
     value ^= value >>> 17;
     value ^= value << 5;
     randomState = value;
     return value;
 }

 private static void requireKey(Object key) {
     if (key == null) {
         throw new IllegalArgumentException("key must not be null");
     }
 }

 private static void requireValue(Object value) {
     if (value == null) {
         throw new IllegalArgumentException("value must not be null");
     }
 }

}
