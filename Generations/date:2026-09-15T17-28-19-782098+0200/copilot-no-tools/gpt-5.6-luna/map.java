public final class ConcurrentMap<K, V> {
    private final Node<K, V>[] buckets;
    private final int capacity;
    private int size;

 @SuppressWarnings("unchecked")
 public ConcurrentMap(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be positive");
     }

     this.capacity = capacity;
     this.buckets = (Node<K, V>[]) new Node[capacity];
 }

 public synchronized V put(K key, V value) throws InterruptedException {
     requireNonNull(key, "key");
     requireNonNull(value, "value");

     int index = indexFor(key);
     Node<K, V> current = buckets[index];

     while (current != null) {
         if (current.key.equals(key)) {
             V previous = current.value;
             current.value = value;
             return previous;
         }
         current = current.next;
     }

     while (size == capacity) {
         wait();

         current = buckets[index];
         while (current != null) {
             if (current.key.equals(key)) {
                 V previous = current.value;
                 current.value = value;
                 return previous;
             }
             current = current.next;
         }
     }

     buckets[index] = new Node<K, V>(key, value, buckets[index]);
     size++;
     notifyAll();
     return null;
 }

 public synchronized boolean tryPut(K key, V value) {
     requireNonNull(key, "key");
     requireNonNull(value, "value");

     int index = indexFor(key);
     Node<K, V> current = buckets[index];

     while (current != null) {
         if (current.key.equals(key)) {
             current.value = value;
             return true;
         }
         current = current.next;
     }

     if (size == capacity) {
         return false;
     }

     buckets[index] = new Node<K, V>(key, value, buckets[index]);
     size++;
     notifyAll();
     return true;
 }

 public synchronized V get(K key) {
     requireNonNull(key, "key");

     Node<K, V> current = buckets[indexFor(key)];
     while (current != null) {
         if (current.key.equals(key)) {
             return current.value;
         }
         current = current.next;
     }

     return null;
 }

 public synchronized boolean containsKey(K key) {
     requireNonNull(key, "key");

     Node<K, V> current = buckets[indexFor(key)];
     while (current != null) {
         if (current.key.equals(key)) {
             return true;
         }
         current = current.next;
     }

     return false;
 }

 public synchronized V remove(K key) {
     requireNonNull(key, "key");

     int index = indexFor(key);
     Node<K, V> current = buckets[index];
     Node<K, V> previous = null;

     while (current != null) {
         if (current.key.equals(key)) {
             if (previous == null) {
                 buckets[index] = current.next;
             } else {
                 previous.next = current.next;
             }

             size--;
             notifyAll();
             return current.value;
         }

         previous = current;
         current = current.next;
     }

     return null;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized int capacity() {
     return capacity;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized boolean isFull() {
     return size == capacity;
 }

 public synchronized void clear() {
     for (int i = 0; i < buckets.length; i++) {
         buckets[i] = null;
     }

     size = 0;
     notifyAll();
 }

 private int indexFor(K key) {
     int hash = key.hashCode();
     hash ^= hash >>> 16;
     return (hash & Integer.MAX_VALUE) % buckets.length;
 }

 private static void requireNonNull(Object value, String name) {
     if (value == null) {
         throw new IllegalArgumentException(name + " must not be null");
     }
 }

 private static final class Node<K, V> {
     private final K key;
     private V value;
     private Node<K, V> next;

     private Node(K key, V value, Node<K, V> next) {
         this.key = key;
         this.value = value;
         this.next = next;
     }
 }

}
