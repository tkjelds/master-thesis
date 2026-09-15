public final class ConcurrentSet<T> {
    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

 private Node<T>[] buckets;
 private int size;
 private int resizeThreshold;

 public ConcurrentSet() {
     this(DEFAULT_CAPACITY);
 }

 public ConcurrentSet(int initialCapacity) {
     if (initialCapacity < 1) {
         throw new IllegalArgumentException("Initial capacity must be positive");
     }

     int capacity = 1;
     while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
         capacity <<= 1;
     }

     buckets = createBuckets(capacity);
     resizeThreshold = threshold(capacity);
 }

 public synchronized boolean add(T value) {
     int hash = hash(value);
     int index = indexFor(hash, buckets.length);
     Node<T> current = buckets[index];

     while (current != null) {
         if (current.hash == hash && equals(current.value, value)) {
             return false;
         }
         current = current.next;
     }

     buckets[index] = new Node<T>(value, hash, buckets[index]);
     size++;

     if (size > resizeThreshold && buckets.length < MAXIMUM_CAPACITY) {
         resize();
     }

     return true;
 }

 public synchronized boolean remove(T value) {
     int hash = hash(value);
     int index = indexFor(hash, buckets.length);
     Node<T> current = buckets[index];
     Node<T> previous = null;

     while (current != null) {
         if (current.hash == hash && equals(current.value, value)) {
             if (previous == null) {
                 buckets[index] = current.next;
             } else {
                 previous.next = current.next;
             }
             size--;
             notifyAll();
             return true;
         }

         previous = current;
         current = current.next;
     }

     return false;
 }

 public synchronized boolean contains(T value) {
     int hash = hash(value);
     int index = indexFor(hash, buckets.length);
     Node<T> current = buckets[index];

     while (current != null) {
         if (current.hash == hash && equals(current.value, value)) {
             return true;
         }
         current = current.next;
     }

     return false;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized void clear() {
     buckets = createBuckets(DEFAULT_CAPACITY);
     resizeThreshold = threshold(DEFAULT_CAPACITY);
     size = 0;
     notifyAll();
 }

 public synchronized Object[] toArray() {
     Object[] result = new Object[size];
     int position = 0;

     for (int i = 0; i < buckets.length; i++) {
         Node<T> current = buckets[i];
         while (current != null) {
             result[position++] = current.value;
             current = current.next;
         }
     }

     return result;
 }

 private void resize() {
     int newCapacity = buckets.length << 1;
     if (newCapacity <= 0 || newCapacity > MAXIMUM_CAPACITY) {
         newCapacity = MAXIMUM_CAPACITY;
     }

     Node<T>[] newBuckets = createBuckets(newCapacity);

     for (int i = 0; i < buckets.length; i++) {
         Node<T> current = buckets[i];

         while (current != null) {
             Node<T> next = current.next;
             int newIndex = indexFor(current.hash, newCapacity);
             current.next = newBuckets[newIndex];
             newBuckets[newIndex] = current;
             current = next;
         }
     }

     buckets = newBuckets;
     resizeThreshold = threshold(newCapacity);
 }

 private static int threshold(int capacity) {
     int result = (int) (capacity * LOAD_FACTOR);
     return result < 1 ? 1 : result;
 }

 @SuppressWarnings("unchecked")
 private static <E> Node<E>[] createBuckets(int capacity) {
     return (Node<E>[]) new Node<?>[capacity];
 }

 private static int hash(Object value) {
     if (value == null) {
         return 0;
     }

     int hash = value.hashCode();
     return hash ^ (hash >>> 16);
 }

 private static int indexFor(int hash, int length) {
     return hash & (length - 1);
 }

 private static boolean equals(Object first, Object second) {
     return first == second || (first != null && first.equals(second));
 }

 private static final class Node<E> {
     private final E value;
     private final int hash;
     private Node<E> next;

     private Node(E value, int hash, Node<E> next) {
         this.value = value;
         this.hash = hash;
         this.next = next;
     }
 }

}
