public final class LinkedDeque<E> {
    private static final class Node<E> {
        final E item;
        Node<E> previous;
        Node<E> next;

     Node(E item) {
         this.item = item;
     }
 }

 private final Object lock = new Object();
 private Node<E> first;
 private Node<E> last;
 private int size;

 public LinkedDeque() {
 }

 public void addFirst(E item) {
     requireNonNull(item);
     synchronized (lock) {
         ensureCapacity();
         Node<E> node = new Node<E>(item);
         Node<E> oldFirst = first;
         node.next = oldFirst;
         first = node;
         if (oldFirst == null) {
             last = node;
         } else {
             oldFirst.previous = node;
         }
         size++;
         lock.notifyAll();
     }
 }

 public void addLast(E item) {
     requireNonNull(item);
     synchronized (lock) {
         ensureCapacity();
         Node<E> node = new Node<E>(item);
         Node<E> oldLast = last;
         node.previous = oldLast;
         last = node;
         if (oldLast == null) {
             first = node;
         } else {
             oldLast.next = node;
         }
         size++;
         lock.notifyAll();
     }
 }

 public boolean offerFirst(E item) {
     addFirst(item);
     return true;
 }

 public boolean offerLast(E item) {
     addLast(item);
     return true;
 }

 public E peekFirst() {
     synchronized (lock) {
         return first == null ? null : first.item;
     }
 }

 public E peekLast() {
     synchronized (lock) {
         return last == null ? null : last.item;
     }
 }

 public E pollFirst() {
     synchronized (lock) {
         return unlinkFirst();
     }
 }

 public E pollLast() {
     synchronized (lock) {
         return unlinkLast();
     }
 }

 public E removeFirst() {
     synchronized (lock) {
         if (first == null) {
             throw new IllegalStateException("Deque is empty");
         }
         return unlinkFirst();
     }
 }

 public E removeLast() {
     synchronized (lock) {
         if (last == null) {
             throw new IllegalStateException("Deque is empty");
         }
         return unlinkLast();
     }
 }

 public E takeFirst() throws InterruptedException {
     synchronized (lock) {
         while (first == null) {
             lock.wait();
         }
         return unlinkFirst();
     }
 }

 public E takeLast() throws InterruptedException {
     synchronized (lock) {
         while (last == null) {
             lock.wait();
         }
         return unlinkLast();
     }
 }

 public int size() {
     synchronized (lock) {
         return size;
     }
 }

 public boolean isEmpty() {
     synchronized (lock) {
         return size == 0;
     }
 }

 public void clear() {
     synchronized (lock) {
         Node<E> node = first;
         while (node != null) {
             Node<E> next = node.next;
             node.previous = null;
             node.next = null;
             node = next;
         }
         first = null;
         last = null;
         size = 0;
     }
 }

 private E unlinkFirst() {
     Node<E> node = first;
     if (node == null) {
         return null;
     }

     Node<E> next = node.next;
     first = next;
     if (next == null) {
         last = null;
     } else {
         next.previous = null;
     }
     node.next = null;
     node.previous = null;
     size--;
     return node.item;
 }

 private E unlinkLast() {
     Node<E> node = last;
     if (node == null) {
         return null;
     }

     Node<E> previous = node.previous;
     last = previous;
     if (previous == null) {
         first = null;
     } else {
         previous.next = null;
     }
     node.next = null;
     node.previous = null;
     size--;
     return node.item;
 }

 private void ensureCapacity() {
     if (size == Integer.MAX_VALUE) {
         throw new IllegalStateException("Deque is too large");
     }
 }

 private static void requireNonNull(Object item) {
     if (item == null) {
         throw new NullPointerException("Deque does not accept null elements");
     }
 }

}
