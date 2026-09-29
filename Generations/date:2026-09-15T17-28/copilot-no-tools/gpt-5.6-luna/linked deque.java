public final class ConcurrentLinkedDeque<E> {
    private static final class Node<E> {
        private E item;
        private Node<E> previous;
        private Node<E> next;

     private Node(E item) {
         this.item = item;
     }
 }

 private Node<E> head;
 private Node<E> tail;
 private int size;

 /*
  * This deque is unbounded, so insertion operations never block.
  * Poll and peek operations return null when the deque is empty.
  * Take operations wait until an element is available.
  */
 public ConcurrentLinkedDeque() {
 }

 public synchronized boolean offerFirst(E element) {
     requireNonNull(element);

     Node<E> node = new Node<E>(element);
     node.next = head;

     if (head == null) {
         tail = node;
     } else {
         head.previous = node;
     }

     head = node;
     size++;
     notifyAll();
     return true;
 }

 public synchronized boolean offerLast(E element) {
     requireNonNull(element);

     Node<E> node = new Node<E>(element);
     node.previous = tail;

     if (tail == null) {
         head = node;
     } else {
         tail.next = node;
     }

     tail = node;
     size++;
     notifyAll();
     return true;
 }

 public synchronized void addFirst(E element) {
     offerFirst(element);
 }

 public synchronized void addLast(E element) {
     offerLast(element);
 }

 public synchronized E pollFirst() {
     if (head == null) {
         return null;
     }

     return unlinkFirst();
 }

 public synchronized E pollLast() {
     if (tail == null) {
         return null;
     }

     return unlinkLast();
 }

 public synchronized E peekFirst() {
     return head == null ? null : head.item;
 }

 public synchronized E peekLast() {
     return tail == null ? null : tail.item;
 }

 public synchronized E takeFirst() throws InterruptedException {
     while (head == null) {
         wait();
     }

     return unlinkFirst();
 }

 public synchronized E takeLast() throws InterruptedException {
     while (tail == null) {
         wait();
     }

     return unlinkLast();
 }

 public synchronized boolean removeFirstOccurrence(Object value) {
     Node<E> current = head;

     while (current != null) {
         if (current.item.equals(value)) {
             unlink(current);
             return true;
         }
         current = current.next;
     }

     return false;
 }

 public synchronized boolean removeLastOccurrence(Object value) {
     Node<E> current = tail;

     while (current != null) {
         if (current.item.equals(value)) {
             unlink(current);
             return true;
         }
         current = current.previous;
     }

     return false;
 }

 public synchronized boolean contains(Object value) {
     Node<E> current = head;

     while (current != null) {
         if (current.item.equals(value)) {
             return true;
         }
         current = current.next;
     }

     return false;
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized void clear() {
     Node<E> current = head;

     while (current != null) {
         Node<E> next = current.next;
         current.item = null;
         current.previous = null;
         current.next = null;
         current = next;
     }

     head = null;
     tail = null;
     size = 0;
     notifyAll();
 }

 public synchronized Object[] toArray() {
     Object[] result = new Object[size];
     Node<E> current = head;
     int index = 0;

     while (current != null) {
         result[index++] = current.item;
         current = current.next;
     }

     return result;
 }

 @Override
 public synchronized String toString() {
     StringBuilder result = new StringBuilder();
     result.append('[');

     Node<E> current = head;
     while (current != null) {
         if (current != head) {
             result.append(", ");
         }
         result.append(current.item);
         current = current.next;
     }

     result.append(']');
     return result.toString();
 }

 private E unlinkFirst() {
     Node<E> node = head;
     Node<E> next = node.next;

     node.item = null;
     node.next = null;
     head = next;

     if (next == null) {
         tail = null;
     } else {
         next.previous = null;
     }

     size--;
     notifyAll();
     return node.item;
 }

 private E unlinkLast() {
     Node<E> node = tail;
     Node<E> previous = node.previous;

     E value = node.item;
     node.item = null;
     node.previous = null;
     tail = previous;

     if (previous == null) {
         head = null;
     } else {
         previous.next = null;
     }

     size--;
     notifyAll();
     return value;
 }

 private E unlink(Node<E> node) {
     Node<E> previous = node.previous;
     Node<E> next = node.next;
     E value = node.item;

     if (previous == null) {
         head = next;
     } else {
         previous.next = next;
     }

     if (next == null) {
         tail = previous;
     } else {
         next.previous = previous;
     }

     node.item = null;
     node.previous = null;
     node.next = null;
     size--;
     notifyAll();
     return value;
 }

 private static void requireNonNull(Object value) {
     if (value == null) {
         throw new NullPointerException("null elements are not supported");
     }
 }

}
