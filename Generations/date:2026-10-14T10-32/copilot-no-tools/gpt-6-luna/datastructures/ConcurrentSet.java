public final class ConcurrentSet<E> {
    private static final class Node<T> {
        private final T value;
        private Node<T> next;

     private Node(T value, Node<T> next) {
         this.value = value;
         this.next = next;
     }
 }

 private final Object lock = new Object();
 private Node<E> head;
 private int size;

 public boolean add(E value) {
     if (value == null) {
         throw new NullPointerException("value");
     }

     synchronized (lock) {
         for (Node<E> current = head; current != null; current = current.next) {
             if (value.equals(current.value)) {
                 return false;
             }
         }

         if (size == Integer.MAX_VALUE) {
             throw new IllegalStateException("Set is too large");
         }

         head = new Node<E>(value, head);
         size++;
         return true;
     }
 }

 public boolean remove(E value) {
     if (value == null) {
         throw new NullPointerException("value");
     }

     synchronized (lock) {
         Node<E> previous = null;
         Node<E> current = head;

         while (current != null) {
             if (value.equals(current.value)) {
                 if (previous == null) {
                     head = current.next;
                 } else {
                     previous.next = current.next;
                 }
                 size--;
                 return true;
             }
             previous = current;
             current = current.next;
         }

         return false;
     }
 }

 public boolean contains(E value) {
     if (value == null) {
         throw new NullPointerException("value");
     }

     synchronized (lock) {
         for (Node<E> current = head; current != null; current = current.next) {
             if (value.equals(current.value)) {
                 return true;
             }
         }
         return false;
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
         head = null;
         size = 0;
     }
 }

}
