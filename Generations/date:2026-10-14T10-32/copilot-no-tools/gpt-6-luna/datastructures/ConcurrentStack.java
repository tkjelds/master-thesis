public final class ConcurrentStack<T> {
    private final Object monitor = new Object();
    private final Object[] elements;
    private int size;

 public ConcurrentStack(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be positive");
     }
     elements = new Object[capacity];
 }

 public void push(T element) throws InterruptedException {
     if (element == null) {
         throw new NullPointerException("Stack does not accept null elements");
     }

     synchronized (monitor) {
         while (size == elements.length) {
             monitor.wait();
         }
         elements[size++] = element;
         monitor.notifyAll();
     }
 }

 @SuppressWarnings("unchecked")
 public T pop() throws InterruptedException {
     synchronized (monitor) {
         while (size == 0) {
             monitor.wait();
         }
         int index = --size;
         T element = (T) elements[index];
         elements[index] = null;
         monitor.notifyAll();
         return element;
     }
 }

 public int size() {
     synchronized (monitor) {
         return size;
     }
 }

 public boolean isEmpty() {
     synchronized (monitor) {
         return size == 0;
     }
 }

 public boolean isFull() {
     synchronized (monitor) {
         return size == elements.length;
     }
 }

 public int capacity() {
     return elements.length;
 }

}
