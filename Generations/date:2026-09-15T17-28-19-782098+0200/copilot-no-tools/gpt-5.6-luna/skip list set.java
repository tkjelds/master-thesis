public final class ConcurrentSkipListSet<E extends Comparable<? super E>> {
    private static final int DEFAULT_MAX_LEVEL = 32;

 private final int maxLevel;
 private final Node<E> head;

 private int currentLevel;
 private int size;
 private int randomState;

 private static final class Node<T> {
     private final T value;
     private final Node<T>[] next;

     @SuppressWarnings("unchecked")
     private Node(T value, int level) {
         this.value = value;
         this.next = (Node<T>[]) new Node<?>[level];
     }
 }

 public ConcurrentSkipListSet() {
     this(DEFAULT_MAX_LEVEL);
 }

 public ConcurrentSkipListSet(int maxLevel) {
     if (maxLevel < 1) {
         throw new IllegalArgumentException("maxLevel must be positive");
     }

     this.maxLevel = maxLevel;
     this.head = new Node<E>(null, maxLevel);
     this.currentLevel = 1;
     this.randomState = 0x6D2B79F5;
 }

 public synchronized boolean add(E value) {
     requireNonNull(value);

     Node<E>[] update = findPredecessors(value);
     Node<E> candidate = update[0].next[0];

     if (candidate != null && candidate.value.compareTo(value) == 0) {
         return false;
     }

     int nodeLevel = randomLevel();

     if (nodeLevel > currentLevel) {
         for (int i = currentLevel; i < nodeLevel; i++) {
             update[i] = head;
         }
         currentLevel = nodeLevel;
     }

     Node<E> node = new Node<E>(value, nodeLevel);

     for (int i = 0; i < nodeLevel; i++) {
         node.next[i] = update[i].next[i];
         update[i].next[i] = node;
     }

     size++;
     notifyAll();
     return true;
 }

 public synchronized boolean remove(E value) {
     requireNonNull(value);

     Node<E>[] update = findPredecessors(value);
     Node<E> candidate = update[0].next[0];

     if (candidate == null || candidate.value.compareTo(value) != 0) {
         return false;
     }

     for (int i = 0; i < currentLevel; i++) {
         if (update[i].next[i] != candidate) {
             break;
         }
         update[i].next[i] = candidate.next[i];
     }

     while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
         currentLevel--;
     }

     size--;
     notifyAll();
     return true;
 }

 public synchronized boolean contains(E value) {
     requireNonNull(value);

     Node<E> current = head;

     for (int level = currentLevel - 1; level >= 0; level--) {
         while (current.next[level] != null
                 && current.next[level].value.compareTo(value) < 0) {
             current = current.next[level];
         }
     }

     current = current.next[0];
     return current != null && current.value.compareTo(value) == 0;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized void clear() {
     for (int i = 0; i < maxLevel; i++) {
         head.next[i] = null;
     }

     currentLevel = 1;
     size = 0;
     notifyAll();
 }

 public synchronized E first() {
     Node<E> first = head.next[0];
     return first == null ? null : first.value;
 }

 public synchronized E last() {
     Node<E> current = head;

     for (int level = currentLevel - 1; level >= 0; level--) {
         while (current.next[level] != null) {
             current = current.next[level];
         }
     }

     return current == head ? null : current.value;
 }

 public synchronized Object[] toArray() {
     Object[] result = new Object[size];
     Node<E> current = head.next[0];
     int index = 0;

     while (current != null) {
         result[index++] = current.value;
         current = current.next[0];
     }

     return result;
 }

 public synchronized E[] toArray(E[] destination) {
     requireNonNull(destination);

     E[] result = destination;
     if (destination.length < size) {
         @SuppressWarnings("unchecked")
         E[] replacement = (E[]) new Comparable[size];
         result = replacement;
     }

     Node<E> current = head.next[0];
     int index = 0;

     while (current != null) {
         result[index++] = current.value;
         current = current.next[0];
     }

     if (result.length > size) {
         result[size] = null;
     }

     return result;
 }

 public synchronized boolean addAll(E[] values) {
     requireNonNull(values);

     boolean changed = false;
     for (int i = 0; i < values.length; i++) {
         if (values[i] == null) {
             throw new NullPointerException("null values are not supported");
         }
         if (addWithoutLock(values[i])) {
             changed = true;
         }
     }

     if (changed) {
         notifyAll();
     }

     return changed;
 }

 private boolean addWithoutLock(E value) {
     Node<E>[] update = findPredecessors(value);
     Node<E> candidate = update[0].next[0];

     if (candidate != null && candidate.value.compareTo(value) == 0) {
         return false;
     }

     int nodeLevel = randomLevel();

     if (nodeLevel > currentLevel) {
         for (int i = currentLevel; i < nodeLevel; i++) {
             update[i] = head;
         }
         currentLevel = nodeLevel;
     }

     Node<E> node = new Node<E>(value, nodeLevel);

     for (int i = 0; i < nodeLevel; i++) {
         node.next[i] = update[i].next[i];
         update[i].next[i] = node;
     }

     size++;
     return true;
 }

 @SuppressWarnings("unchecked")
 private Node<E>[] findPredecessors(E value) {
     Node<E>[] update = (Node<E>[]) new Node<?>[maxLevel];
     Node<E> current = head;

     for (int level = currentLevel - 1; level >= 0; level--) {
         while (current.next[level] != null
                 && current.next[level].value.compareTo(value) < 0) {
             current = current.next[level];
         }
         update[level] = current;
     }

     return update;
 }

 private int randomLevel() {
     int x = randomState;
     x ^= x << 13;
     x ^= x >>> 17;
     x ^= x << 5;
     randomState = x;

     int level = 1;
     while (level < maxLevel && (x & 1) != 0) {
         level++;
         x >>>= 1;
     }

     return level;
 }

 private static void requireNonNull(Object value) {
     if (value == null) {
         throw new NullPointerException("null values are not supported");
     }
 }

}
