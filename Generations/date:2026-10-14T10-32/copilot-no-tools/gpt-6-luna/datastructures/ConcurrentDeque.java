public final class ConcurrentDeque<E> {
    private final Object[] elements;
    private final int capacity;

 // All accesses to head and size are guarded by this deque's monitor.
 private int head;
 private int size;

 /**
  * Creates a bounded deque with the given positive capacity.
  * Null elements are not permitted.
  */
 public ConcurrentDeque(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("capacity must be positive");
     }
     this.capacity = capacity;
     this.elements = new Object[capacity];
 }

 /**
  * Inserts an element at the front, waiting while the deque is full.
  */
 public void putFirst(E element) throws InterruptedException {
     requireElement(element);
     synchronized (this) {
         while (size == capacity) {
             wait();
         }
         head = previousIndex(head);
         elements[head] = element;
         size++;
         notifyAll();
     }
 }

 /**
  * Inserts an element at the back, waiting while the deque is full.
  */
 public void putLast(E element) throws InterruptedException {
     requireElement(element);
     synchronized (this) {
         while (size == capacity) {
             wait();
         }
         elements[indexAtOffset(size)] = element;
         size++;
         notifyAll();
     }
 }

 /**
  * Inserts an element at the front immediately.
  *
  * @return true if the element was inserted, or false if the deque is full
  */
 public boolean offerFirst(E element) {
     requireElement(element);
     synchronized (this) {
         if (size == capacity) {
             return false;
         }
         head = previousIndex(head);
         elements[head] = element;
         size++;
         notifyAll();
         return true;
     }
 }

 /**
  * Inserts an element at the back immediately.
  *
  * @return true if the element was inserted, or false if the deque is full
  */
 public boolean offerLast(E element) {
     requireElement(element);
     synchronized (this) {
         if (size == capacity) {
             return false;
         }
         elements[indexAtOffset(size)] = element;
         size++;
         notifyAll();
         return true;
     }
 }

 /**
  * Removes and returns the first element, waiting while the deque is empty.
  */
 public E takeFirst() throws InterruptedException {
     synchronized (this) {
         while (size == 0) {
             wait();
         }
         return removeFirst();
     }
 }

 /**
  * Removes and returns the last element, waiting while the deque is empty.
  */
 public E takeLast() throws InterruptedException {
     synchronized (this) {
         while (size == 0) {
             wait();
         }
         return removeLast();
     }
 }

 /**
  * Removes and returns the first element immediately, or null if empty.
  */
 public E pollFirst() {
     synchronized (this) {
         if (size == 0) {
             return null;
         }
         return removeFirst();
     }
 }

 /**
  * Removes and returns the last element immediately, or null if empty.
  */
 public E pollLast() {
     synchronized (this) {
         if (size == 0) {
             return null;
         }
         return removeLast();
     }
 }

 /**
  * Returns the first element without removing it, or null if empty.
  */
 public E peekFirst() {
     synchronized (this) {
         if (size == 0) {
             return null;
         }
         return elementAt(head);
     }
 }

 /**
  * Returns the last element without removing it, or null if empty.
  */
 public E peekLast() {
     synchronized (this) {
         if (size == 0) {
             return null;
         }
         return elementAt(indexAtOffset(size - 1));
     }
 }

 public int size() {
     synchronized (this) {
         return size;
     }
 }

 public int capacity() {
     synchronized (this) {
         return capacity;
     }
 }

 public int remainingCapacity() {
     synchronized (this) {
         return capacity - size;
     }
 }

 public boolean isEmpty() {
     synchronized (this) {
         return size == 0;
     }
 }

 public boolean isFull() {
     synchronized (this) {
         return size == capacity;
     }
 }

 /**
  * Removes all elements and wakes threads waiting to insert.
  */
 public void clear() {
     synchronized (this) {
         for (int i = 0; i < size; i++) {
             elements[indexAtOffset(i)] = null;
         }
         size = 0;
         head = 0;
         notifyAll();
     }
 }

 /**
  * Returns a snapshot of the elements in front-to-back order.
  */
 public Object[] toArray() {
     synchronized (this) {
         Object[] snapshot = new Object[size];
         for (int i = 0; i < size; i++) {
             snapshot[i] = elements[indexAtOffset(i)];
         }
         return snapshot;
     }
 }

 private E removeFirst() {
     E element = elementAt(head);
     elements[head] = null;
     size--;
     if (size == 0) {
         head = 0;
     } else {
         head = nextIndex(head);
     }
     notifyAll();
     return element;
 }

 private E removeLast() {
     int lastIndex = indexAtOffset(size - 1);
     E element = elementAt(lastIndex);
     elements[lastIndex] = null;
     size--;
     if (size == 0) {
         head = 0;
     }
     notifyAll();
     return element;
 }

 private int indexAtOffset(int offset) {
     int slotsToEnd = capacity - head;
     return offset >= slotsToEnd ? offset - slotsToEnd : head + offset;
 }

 private int nextIndex(int index) {
     return index == capacity - 1 ? 0 : index + 1;
 }

 private int previousIndex(int index) {
     return index == 0 ? capacity - 1 : index - 1;
 }

 @SuppressWarnings("unchecked")
 private E elementAt(int index) {
     return (E) elements[index];
 }

 private void requireElement(E element) {
     if (element == null) {
         throw new NullPointerException("null elements are not permitted");
     }
 }

}
