public final class ConcurrentDeque<E> {
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

 public ConcurrentDeque(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be greater than zero");
     }
     elements = new Object[capacity];
 }

 public synchronized int capacity() {
     return elements.length;
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

 public synchronized int remainingCapacity() {
     return elements.length - size;
 }

 public void putFirst(E element) throws InterruptedException {
     requireNonNull(element);

     synchronized (this) {
         while (size == elements.length) {
             wait();
         }

         head = previousIndex(head);
         elements[head] = element;
         size++;
         notifyAll();
     }
 }

 public void putLast(E element) throws InterruptedException {
     requireNonNull(element);

     synchronized (this) {
         while (size == elements.length) {
             wait();
         }

         elements[tail] = element;
         tail = nextIndex(tail);
         size++;
         notifyAll();
     }
 }

 public synchronized boolean offerFirst(E element) {
     requireNonNull(element);

     if (size == elements.length) {
         return false;
     }

     head = previousIndex(head);
     elements[head] = element;
     size++;
     notifyAll();
     return true;
 }

 public synchronized boolean offerLast(E element) {
     requireNonNull(element);

     if (size == elements.length) {
         return false;
     }

     elements[tail] = element;
     tail = nextIndex(tail);
     size++;
     notifyAll();
     return true;
 }

 public E takeFirst() throws InterruptedException {
     synchronized (this) {
         while (size == 0) {
             wait();
         }

         E element = elementAt(head);
         elements[head] = null;
         head = nextIndex(head);
         size--;
         notifyAll();
         return element;
     }
 }

 public E takeLast() throws InterruptedException {
     synchronized (this) {
         while (size == 0) {
             wait();
         }

         tail = previousIndex(tail);
         E element = elementAt(tail);
         elements[tail] = null;
         size--;
         notifyAll();
         return element;
     }
 }

 public synchronized E pollFirst() {
     if (size == 0) {
         return null;
     }

     E element = elementAt(head);
     elements[head] = null;
     head = nextIndex(head);
     size--;
     notifyAll();
     return element;
 }

 public synchronized E pollLast() {
     if (size == 0) {
         return null;
     }

     tail = previousIndex(tail);
     E element = elementAt(tail);
     elements[tail] = null;
     size--;
     notifyAll();
     return element;
 }

 public synchronized E peekFirst() {
     if (size == 0) {
         return null;
     }
     return elementAt(head);
 }

 public synchronized E peekLast() {
     if (size == 0) {
         return null;
     }
     return elementAt(previousIndex(tail));
 }

 public synchronized void clear() {
     for (int i = 0; i < elements.length; i++) {
         elements[i] = null;
     }

     head = 0;
     tail = 0;
     size = 0;
     notifyAll();
 }

 private int nextIndex(int index) {
     return index + 1 == elements.length ? 0 : index + 1;
 }

 private int previousIndex(int index) {
     return index == 0 ? elements.length - 1 : index - 1;
 }

 @SuppressWarnings("unchecked")
 private E elementAt(int index) {
     return (E) elements[index];
 }

 private void requireNonNull(E element) {
     if (element == null) {
         throw new NullPointerException("Null elements are not supported");
     }
 }

}
