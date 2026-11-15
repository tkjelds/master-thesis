@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int size;

 public ConcurrentPriorityQueue(int capacity) {
     if (capacity <= 0) {
         throw new IllegalArgumentException("Capacity must be positive");
     }
     elements = new Object[capacity];
 }

 public synchronized void put(E element) throws InterruptedException {
     requireNonNull(element);

     while (size == elements.length) {
         wait();
     }

     elements[size] = element;
     siftUp(size);
     size++;
     notifyAll();
 }

 public synchronized boolean offer(E element) {
     requireNonNull(element);

     if (size == elements.length) {
         return false;
     }

     elements[size] = element;
     siftUp(size);
     size++;
     notifyAll();
     return true;
 }

 public synchronized E take() throws InterruptedException {
     while (size == 0) {
         wait();
     }

     E result = removeRoot();
     notifyAll();
     return result;
 }

 public synchronized E poll() {
     if (size == 0) {
         return null;
     }

     E result = removeRoot();
     notifyAll();
     return result;
 }

 public synchronized E peek() {
     if (size == 0) {
         return null;
     }

     return elementAt(0);
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized int remainingCapacity() {
     return elements.length - size;
 }

 public synchronized int capacity() {
     return elements.length;
 }

 public synchronized void clear() {
     for (int i = 0; i < size; i++) {
         elements[i] = null;
     }
     size = 0;
     notifyAll();
 }

 private void siftUp(int index) {
     E value = elementAt(index);

     while (index > 0) {
         int parent = (index - 1) / 2;
         E parentValue = elementAt(parent);

         if (value.compareTo(parentValue) >= 0) {
             break;
         }

         elements[index] = parentValue;
         index = parent;
     }

     elements[index] = value;
 }

 private void siftDown(int index) {
     E value = elementAt(index);

     while (true) {
         int left = index * 2 + 1;
         if (left >= size) {
             break;
         }

         int right = left + 1;
         int smallerChild = left;

         if (right < size
                 && elementAt(right).compareTo(elementAt(left)) < 0) {
             smallerChild = right;
         }

         E childValue = elementAt(smallerChild);
         if (value.compareTo(childValue) <= 0) {
             break;
         }

         elements[index] = childValue;
         index = smallerChild;
     }

     elements[index] = value;
 }

 private E removeRoot() {
     E result = elementAt(0);
     size--;

     if (size == 0) {
         elements[0] = null;
         return result;
     }

     elements[0] = elements[size];
     elements[size] = null;
     siftDown(0);
     return result;
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
