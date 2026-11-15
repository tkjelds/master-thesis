public final class ConcurrentQueue<E> {
    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

 public ConcurrentQueue(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("capacity must be positive");
     }
     elements = new Object[capacity];
 }

 /**
  * Adds an element unless the queue is full. Null elements are not allowed.
  *
  * @return true if the element was added; false if the queue was full
  */
 public boolean offer(E element) {
     if (element == null) {
         throw new NullPointerException("element");
     }

     synchronized (lock) {
         if (size == elements.length) {
             return false;
         }
         enqueue(element);
         lock.notifyAll();
         return true;
     }
 }

 /**
  * Adds an element, waiting while the queue is full.
  */
 public void put(E element) throws InterruptedException {
     if (element == null) {
         throw new NullPointerException("element");
     }

     synchronized (lock) {
         while (size == elements.length) {
             lock.wait();
         }
         enqueue(element);
         lock.notifyAll();
     }
 }

 /**
  * Removes and returns an element, or returns null if the queue is empty.
  */
 public E poll() {
     synchronized (lock) {
         if (size == 0) {
             return null;
         }
         E element = dequeue();
         lock.notifyAll();
         return element;
     }
 }

 /**
  * Removes and returns an element, waiting while the queue is empty.
  */
 public E take() throws InterruptedException {
     synchronized (lock) {
         while (size == 0) {
             lock.wait();
         }
         E element = dequeue();
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
     return elements.length;
 }

 public boolean isEmpty() {
     synchronized (lock) {
         return size == 0;
     }
 }

 public boolean isFull() {
     synchronized (lock) {
         return size == elements.length;
     }
 }

 private void enqueue(E element) {
     elements[tail] = element;
     tail = (tail + 1) % elements.length;
     size++;
 }

 @SuppressWarnings("unchecked")
 private E dequeue() {
     E element = (E) elements[head];
     elements[head] = null;
     head = (head + 1) % elements.length;
     size--;
     return element;
 }

}
