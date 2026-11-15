@skip_list_set.ThreadSafe
public final class skip_list_set<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int DEFAULT_MAX_LEVEL = 32;

 private final int maxLevel;
 private final Node<E> head;
 private int currentLevel;
 private int size;
 private long randomState;

 private static final class Node<T> {
     private final T value;
     private final Node<T>[] next;

     @SuppressWarnings("unchecked")
     private Node(T value, int level) {
         this.value = value;
         this.next = (Node<T>[]) new Node<?>[level];
     }
 }

 public skip_list_set() {
     this(DEFAULT_MAX_LEVEL);
 }

 public skip_list_set(int maxLevel) {
     if (maxLevel < 1) {
         throw new IllegalArgumentException("maxLevel must be positive");
     }

     this.maxLevel = maxLevel;
     this.head = new Node<E>(null, maxLevel);
     this.currentLevel = 1;
     this.size = 0;
     this.randomState = initialRandomState();
 }

 public synchronized boolean add(E value) {
     requireNonNull(value);

     Node<E>[] predecessors = findPredecessors(value);
     Node<E> candidate = predecessors[0].next[0];

     if (candidate != null && candidate.value.compareTo(value) == 0) {
         return false;
     }

     int level = randomLevel();
     if (level > currentLevel) {
         for (int i = currentLevel; i < level; i++) {
             predecessors[i] = head;
         }
         currentLevel = level;
     }

     Node<E> node = new Node<E>(value, level);
     for (int i = 0; i < level; i++) {
         node.next[i] = predecessors[i].next[i];
         predecessors[i].next[i] = node;
     }

     size++;
     notifyAll();
     return true;
 }

 public synchronized boolean remove(E value) {
     requireNonNull(value);

     Node<E>[] predecessors = findPredecessors(value);
     Node<E> candidate = predecessors[0].next[0];

     if (candidate == null || candidate.value.compareTo(value) != 0) {
         return false;
     }

     for (int i = 0; i < currentLevel; i++) {
         if (predecessors[i].next[i] != candidate) {
             break;
         }
         predecessors[i].next[i] = candidate.next[i];
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

     Node<E>[] predecessors = findPredecessors(value);
     Node<E> candidate = predecessors[0].next[0];

     return candidate != null && candidate.value.compareTo(value) == 0;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized void clear() {
     for (int i = 0; i < currentLevel; i++) {
         head.next[i] = null;
     }

     currentLevel = 1;
     size = 0;
     notifyAll();
 }

 public synchronized E first() {
     Node<E> node = head.next[0];
     return node == null ? null : node.value;
 }

 public synchronized E last() {
     Node<E> node = head;
     for (int level = currentLevel - 1; level >= 0; level--) {
         while (node.next[level] != null) {
             node = node.next[level];
         }
     }

     return node == head ? null : node.value;
 }

 public synchronized Object[] toArray() {
     Object[] result = new Object[size];
     Node<E> node = head.next[0];
     int index = 0;

     while (node != null) {
         result[index++] = node.value;
         node = node.next[0];
     }

     return result;
 }

 public synchronized void awaitNonEmpty() throws InterruptedException {
     while (size == 0) {
         wait();
     }
 }

 @SuppressWarnings("unchecked")
 private Node<E>[] findPredecessors(E value) {
     Node<E>[] predecessors = (Node<E>[]) new Node<?>[maxLevel];
     Node<E> current = head;

     for (int level = currentLevel - 1; level >= 0; level--) {
         while (current.next[level] != null
                 && current.next[level].value.compareTo(value) < 0) {
             current = current.next[level];
         }
         predecessors[level] = current;
     }

     return predecessors;
 }

 private int randomLevel() {
     int level = 1;
     long state = randomState;

     while (level < maxLevel && (state & 1L) == 0L) {
         level++;
         state = nextRandom(state);
     }

     randomState = state;
     return level;
 }

 private static long nextRandom(long state) {
     state ^= state << 13;
     state ^= state >>> 7;
     state ^= state << 17;
     return state;
 }

 private static long initialRandomState() {
     long value = System.nanoTime();
     return value == 0L ? 1L : value;
 }

 private static void requireNonNull(Object value) {
     if (value == null) {
         throw new NullPointerException("null values are not supported");
     }
 }

}
