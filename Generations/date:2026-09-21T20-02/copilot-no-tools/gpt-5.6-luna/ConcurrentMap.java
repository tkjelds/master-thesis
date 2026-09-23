public final class ConcurrentMap<K, V> {
    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final float LOAD_FACTOR = 0.75f;

 private Entry<K, V>[] table;
 private int size;
 private int threshold;

 public ConcurrentMap() {
     this(DEFAULT_CAPACITY);
 }

 @SuppressWarnings("unchecked")
 public ConcurrentMap(int initialCapacity) {
     if (initialCapacity < 1) {
         throw new IllegalArgumentException("Initial capacity must be positive");
     }

     int capacity = 1;
     while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
         capacity <<= 1;
     }

     table = (Entry<K, V>[]) new Entry[capacity];
     threshold = (int) (capacity * LOAD_FACTOR);
     if (threshold < 1) {
         threshold = 1;
     }
 }

 public synchronized V put(K key, V value) {
     int hash = hash(key);
     int index = indexFor(hash, table.length);
     Entry<K, V> current = table[index];

     while (current != null) {
         if (current.hash == hash && keysEqual(current.key, key)) {
             V previous = current.value;
             current.value = value;
             return previous;
         }
         current = current.next;
     }

     table[index] = new Entry<K, V>(hash, key, value, table[index]);
     size++;

     if (size > threshold && table.length < MAXIMUM_CAPACITY) {
         resize();
     }

     return null;
 }

 public synchronized V putIfAbsent(K key, V value) {
     int hash = hash(key);
     int index = indexFor(hash, table.length);
     Entry<K, V> current = table[index];

     while (current != null) {
         if (current.hash == hash && keysEqual(current.key, key)) {
             return current.value;
         }
         current = current.next;
     }

     table[index] = new Entry<K, V>(hash, key, value, table[index]);
     size++;

     if (size > threshold && table.length < MAXIMUM_CAPACITY) {
         resize();
     }

     return null;
 }

 public synchronized V get(K key) {
     Entry<K, V> entry = findEntry(key);
     return entry == null ? null : entry.value;
 }

 public synchronized boolean containsKey(K key) {
     return findEntry(key) != null;
 }

 public synchronized boolean containsValue(V value) {
     for (int i = 0; i < table.length; i++) {
         Entry<K, V> current = table[i];
         while (current != null) {
             if (valuesEqual(current.value, value)) {
                 return true;
             }
             current = current.next;
         }
     }
     return false;
 }

 public synchronized V remove(K key) {
     int hash = hash(key);
     int index = indexFor(hash, table.length);
     Entry<K, V> previous = null;
     Entry<K, V> current = table[index];

     while (current != null) {
         if (current.hash == hash && keysEqual(current.key, key)) {
             if (previous == null) {
                 table[index] = current.next;
             } else {
                 previous.next = current.next;
             }
             size--;
             return current.value;
         }
         previous = current;
         current = current.next;
     }

     return null;
 }

 public synchronized boolean remove(K key, V value) {
     int hash = hash(key);
     int index = indexFor(hash, table.length);
     Entry<K, V> previous = null;
     Entry<K, V> current = table[index];

     while (current != null) {
         if (current.hash == hash
                 && keysEqual(current.key, key)
                 && valuesEqual(current.value, value)) {
             if (previous == null) {
                 table[index] = current.next;
             } else {
                 previous.next = current.next;
             }
             size--;
             return true;
         }
         previous = current;
         current = current.next;
     }

     return false;
 }

 public synchronized boolean replace(K key, V oldValue, V newValue) {
     Entry<K, V> entry = findEntry(key);
     if (entry != null && valuesEqual(entry.value, oldValue)) {
         entry.value = newValue;
         return true;
     }
     return false;
 }

 public synchronized V replace(K key, V value) {
     Entry<K, V> entry = findEntry(key);
     if (entry == null) {
         return null;
     }

     V previous = entry.value;
     entry.value = value;
     return previous;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 @SuppressWarnings("unchecked")
 public synchronized void clear() {
     table = (Entry<K, V>[]) new Entry[DEFAULT_CAPACITY];
     threshold = (int) (DEFAULT_CAPACITY * LOAD_FACTOR);
     size = 0;
 }

 private Entry<K, V> findEntry(K key) {
     int hash = hash(key);
     int index = indexFor(hash, table.length);
     Entry<K, V> current = table[index];

     while (current != null) {
         if (current.hash == hash && keysEqual(current.key, key)) {
             return current;
         }
         current = current.next;
     }

     return null;
 }

 @SuppressWarnings("unchecked")
 private void resize() {
     int oldCapacity = table.length;
     int newCapacity = oldCapacity << 1;

     if (newCapacity <= 0 || newCapacity > MAXIMUM_CAPACITY) {
         newCapacity = MAXIMUM_CAPACITY;
     }

     Entry<K, V>[] newTable = (Entry<K, V>[]) new Entry[newCapacity];

     for (int i = 0; i < oldCapacity; i++) {
         Entry<K, V> current = table[i];

         while (current != null) {
             Entry<K, V> next = current.next;
             int newIndex = indexFor(current.hash, newCapacity);
             current.next = newTable[newIndex];
             newTable[newIndex] = current;
             current = next;
         }
     }

     table = newTable;
     threshold = newCapacity == MAXIMUM_CAPACITY
             ? Integer.MAX_VALUE
             : (int) (newCapacity * LOAD_FACTOR);
 }

 private static int hash(Object key) {
     if (key == null) {
         return 0;
     }

     int hash = key.hashCode();
     return hash ^ (hash >>> 16);
 }

 private static int indexFor(int hash, int capacity) {
     return hash & (capacity - 1);
 }

 private static boolean keysEqual(Object first, Object second) {
     return first == second || (first != null && first.equals(second));
 }

 private static boolean valuesEqual(Object first, Object second) {
     return first == second || (first != null && first.equals(second));
 }

 private static final class Entry<K, V> {
     private final int hash;
     private final K key;
     private V value;
     private Entry<K, V> next;

     private Entry(int hash, K key, V value, Entry<K, V> next) {
         this.hash = hash;
         this.key = key;
         this.value = value;
         this.next = next;
     }
 }

}
