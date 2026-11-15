@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int size;

 public ConcurrentStack(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be greater than zero");
     }
     elements = new Object[capacity];
 }

 public synchronized void push(T element) throws InterruptedException {
     if (element == null) {
         throw new NullPointerException("Stack elements cannot be null");
     }

     while (size == elements.length) {
         wait();
     }

     elements[size] = element;
     size++;
     notifyAll();
 }

 public synchronized T pop() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     size--;
     T element = (T) elements[size];
     elements[size] = null;
     notifyAll();
     return element;
 }

 public synchronized T peek() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     return (T) elements[size - 1];
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized boolean isFull() {
     return size == elements.length;
 }

 public int capacity() {
     return elements.length;
 }

}
