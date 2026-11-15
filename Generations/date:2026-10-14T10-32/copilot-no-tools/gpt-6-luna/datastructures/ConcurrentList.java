public final class ConcurrentList<T> {
    private final Object lock = new Object();
    private final Object[] elements;
    private int size;

 public ConcurrentList(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("capacity must be positive");
     }
     elements = new Object[capacity];
 }

 /**
  * Appends an element, waiting until space is available.
  * Throws InterruptedException if interrupted while waiting.
  */
 public void add(T element) throws InterruptedException {
     synchronized (lock) {
         while (size == elements.length) {
             lock.wait();
         }
         elements[size++] = element;
         lock.notifyAll();
     }
 }

 /**
  * Removes and returns the first element, waiting until one is available.
  * Throws InterruptedException if interrupted while waiting.
  */
 @SuppressWarnings("unchecked")
 public T removeFirst() throws InterruptedException {
     synchronized (lock) {
         while (size == 0) {
             lock.wait();
         }
         T element = (T) elements[0];
         int remaining = --size;
         if (remaining > 0) {
             System.arraycopy(elements, 1, elements, 0, remaining);
         }
         elements[size] = null;
         lock.notifyAll();
         return element;
     }
 }

 public T get(int index) {
     synchronized (lock) {
         checkIndex(index);
         @SuppressWarnings("unchecked")
         T element = (T) elements[index];
         return element;
     }
 }

 public T remove(int index) {
     synchronized (lock) {
         checkIndex(index);
         @SuppressWarnings("unchecked")
         T element = (T) elements[index];
         int remaining = --size - index;
         if (remaining > 0) {
             System.arraycopy(elements, index + 1, elements, index, remaining);
         }
         elements[size] = null;
         lock.notifyAll();
         return element;
     }
 }

 public int size() {
     synchronized (lock) {
         return size;
     }
 }

 public int capacity() {
     synchronized (lock) {
         return elements.length;
     }
 }

 public boolean isEmpty() {
     synchronized (lock) {
         return size == 0;
     }
 }

 private void checkIndex(int index) {
     if (index < 0 || index >= size) {
         throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
     }
 }

}
