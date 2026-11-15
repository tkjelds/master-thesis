public final class ArrayQueue<E> {
    private final Object[] elements;
    private final int capacity;
    private int head;
    private int tail;
    private int count;

 public ArrayQueue(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("capacity must be positive");
     }
     this.capacity = capacity;
     this.elements = new Object[capacity];
 }

 public void put(E element) throws InterruptedException {
     if (element == null) {
         throw new NullPointerException("element");
     }

     synchronized (this) {
         while (count == capacity) {
             wait();
         }

         elements[tail] = element;
         tail = (tail + 1) % capacity;
         count++;
         notifyAll();
     }
 }

 public E take() throws InterruptedException {
     synchronized (this) {
         while (count == 0) {
             wait();
         }

         E element = elementAt(head);
         elements[head] = null;
         head = (head + 1) % capacity;
         count--;
         notifyAll();
         return element;
     }
 }

 public boolean offer(E element) {
     if (element == null) {
         throw new NullPointerException("element");
     }

     synchronized (this) {
         if (count == capacity) {
             return false;
         }

         elements[tail] = element;
         tail = (tail + 1) % capacity;
         count++;
         notifyAll();
         return true;
     }
 }

 public E poll() {
     synchronized (this) {
         if (count == 0) {
             return null;
         }

         E element = elementAt(head);
         elements[head] = null;
         head = (head + 1) % capacity;
         count--;
         notifyAll();
         return element;
     }
 }

 public E peek() {
     synchronized (this) {
         return count == 0 ? null : elementAt(head);
     }
 }

 public int size() {
     synchronized (this) {
         return count;
     }
 }

 public int capacity() {
     return capacity;
 }

 public boolean isEmpty() {
     synchronized (this) {
         return count == 0;
     }
 }

 public boolean isFull() {
     synchronized (this) {
         return count == capacity;
     }
 }

 @SuppressWarnings("unchecked")
 private E elementAt(int index) {
     return (E) elements[index];
 }

}
