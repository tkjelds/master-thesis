public final class LinkedDeque<E> {
    private static final class Node<E> {
        private E item;
        private Node<E> prev;
        private Node<E> next;

     private Node(E item) {
         this.item = item;
     }
 }

 private Node<E> first;
 private Node<E> last;
 private int size;

 public LinkedDeque() {
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized void addFirst(E element) {
     requireNonNull(element);

     Node<E> node = new Node<E>(element);
     node.next = first;

     if (first == null) {
         last = node;
     } else {
         first.prev = node;
     }

     first = node;
     size++;
     notifyAll();
 }

 public synchronized void addLast(E element) {
     requireNonNull(element);

     Node<E> node = new Node<E>(element);
     node.prev = last;

     if (last == null) {
         first = node;
     } else {
         last.next = node;
     }

     last = node;
     size++;
     notifyAll();
 }

 public synchronized boolean offerFirst(E element) {
     addFirst(element);
     return true;
 }

 public synchronized boolean offerLast(E element) {
     addLast(element);
     return true;
 }

 public synchronized E peekFirst() {
     return first == null ? null : first.item;
 }

 public synchronized E peekLast() {
     return last == null ? null : last.item;
 }

 public synchronized E pollFirst() {
     if (first == null) {
         return null;
     }

     return unlinkFirst();
 }

 public synchronized E pollLast() {
     if (last == null) {
         return null;
     }

     return unlinkLast();
 }

 public synchronized E takeFirst() throws InterruptedException {
     while (first == null) {
         wait();
     }

     return unlinkFirst();
 }

 public synchronized E takeLast() throws InterruptedException {
     while (last == null) {
         wait();
     }

     return unlinkLast();
 }

 public synchronized void clear() {
     Node<E> current = first;

     while (current != null) {
         Node<E> next = current.next;
         current.item = null;
         current.prev = null;
         current.next = null;
         current = next;
     }

     first = null;
     last = null;
     size = 0;
     notifyAll();
 }

 public synchronized Object[] toArray() {
     Object[] result = new Object[size];
     Node<E> current = first;
     int index = 0;

     while (current != null) {
         result[index++] = current.item;
         current = current.next;
     }

     return result;
 }

 public synchronized int drainTo(E[] destination) {
     if (destination == null) {
         throw new NullPointerException("destination");
     }

     int count = 0;
     while (count < destination.length && first != null) {
         destination[count++] = unlinkFirst();
     }

     return count;
 }

 private E unlinkFirst() {
     Node<E> node = first;
     Node<E> next = node.next;

     first = next;
     if (next == null) {
         last = null;
     } else {
         next.prev = null;
     }

     node.item = null;
     node.next = null;
     size--;
     notifyAll();
     return node.item;
 }

 private E unlinkLast() {
     Node<E> node = last;
     Node<E> previous = node.prev;
     E element = node.item;

     last = previous;
     if (previous == null) {
         first = null;
     } else {
         previous.next = null;
     }

     node.item = null;
     node.prev = null;
     size--;
     notifyAll();
     return element;
 }

 private static void requireNonNull(Object element) {
     if (element == null) {
         throw new NullPointerException("null elements are not supported");
     }
 }

}
