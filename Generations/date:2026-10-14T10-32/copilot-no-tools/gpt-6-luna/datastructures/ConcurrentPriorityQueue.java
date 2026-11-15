public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    private static final int DEFAULT_CAPACITY = 16;

 private Object[] elements;
 private int size;

 public ConcurrentPriorityQueue() {
     this(DEFAULT_CAPACITY);
 }

 public ConcurrentPriorityQueue(int initialCapacity) {
     if (initialCapacity < 1) {
         throw new IllegalArgumentException("initialCapacity must be positive");
     }
     elements = new Object[initialCapacity];
 }

 /**
  * Inserts an element into this unbounded min-priority queue.
  * Null elements are not permitted.
  */
 public boolean offer(E element) {
     if (element == null) {
         throw new IllegalArgumentException("element must not be null");
     }

     synchronized (this) {
         int insertionIndex = size;
         int targetIndex = insertionIndex;

         while (targetIndex > 0) {
             int parentIndex = (targetIndex - 1) >>> 1;
             if (element.compareTo(elementAt(parentIndex)) >= 0) {
                 break;
             }
             targetIndex = parentIndex;
         }

         ensureCapacity(size + 1);

         int index = insertionIndex;
         while (index > targetIndex) {
             int parentIndex = (index - 1) >>> 1;
             elements[index] = elements[parentIndex];
             index = parentIndex;
         }

         elements[targetIndex] = element;
         size++;
         this.notifyAll();
         return true;
     }
 }

 /**
  * Inserts an element into this queue.
  * This unbounded queue never rejects an element except for null.
  */
 public boolean add(E element) {
     return offer(element);
 }

 /**
  * Retrieves and removes the least element, waiting until one is available.
  * If interrupted while waiting, this method throws InterruptedException.
  */
 public E take() throws InterruptedException {
     synchronized (this) {
         while (size == 0) {
             this.wait();
         }
         return removeMinimum();
     }
 }

 /**
  * Retrieves and removes the least element, or returns null if the queue is empty.
  */
 public E poll() {
     synchronized (this) {
         if (size == 0) {
             return null;
         }
         return removeMinimum();
     }
 }

 /**
  * Retrieves, but does not remove, the least element, or returns null if empty.
  */
 public E peek() {
     synchronized (this) {
         return size == 0 ? null : elementAt(0);
     }
 }

 public int size() {
     synchronized (this) {
         return size;
     }
 }

 public boolean isEmpty() {
     synchronized (this) {
         return size == 0;
     }
 }

 public void clear() {
     synchronized (this) {
         for (int i = 0; i < size; i++) {
             elements[i] = null;
         }
         size = 0;
     }
 }

 private E removeMinimum() {
     int newSize = size - 1;
     E minimum = elementAt(0);

     if (newSize == 0) {
         elements[0] = null;
         size = 0;
         return minimum;
     }

     E replacement = elementAt(newSize);
     int[] path = new int[32];
     int pathLength = 0;
     int index = 0;

     while (index <= (newSize - 2) / 2) {
         int leftChild = (index << 1) + 1;
         int rightChild = leftChild + 1;
         int smallerChild = leftChild;

         if (rightChild < newSize
                 && elementAt(rightChild).compareTo(elementAt(leftChild)) < 0) {
             smallerChild = rightChild;
         }

         if (replacement.compareTo(elementAt(smallerChild)) <= 0) {
             break;
         }

         path[pathLength++] = smallerChild;
         index = smallerChild;
     }

     for (int i = 0; i < pathLength; i++) {
         int childIndex = path[i];
         elements[(childIndex - 1) >>> 1] = elements[childIndex];
     }

     elements[index] = replacement;
     elements[newSize] = null;
     size = newSize;
     return minimum;
 }

 private void ensureCapacity(int requiredCapacity) {
     if (requiredCapacity <= elements.length) {
         return;
     }
     if (elements.length == Integer.MAX_VALUE) {
         throw new IllegalStateException("queue capacity exceeded");
     }

     int newCapacity = elements.length <= Integer.MAX_VALUE / 2
             ? elements.length * 2
             : Integer.MAX_VALUE;
     if (newCapacity < requiredCapacity) {
         newCapacity = requiredCapacity;
     }

     Object[] newElements = new Object[newCapacity];
     for (int i = 0; i < size; i++) {
         newElements[i] = elements[i];
     }
     elements = newElements;
 }

 @SuppressWarnings("unchecked")
 private E elementAt(int index) {
     return (E) elements[index];
 }

}
