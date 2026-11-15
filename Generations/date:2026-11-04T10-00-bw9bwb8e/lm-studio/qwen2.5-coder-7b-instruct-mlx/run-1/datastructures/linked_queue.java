@LinkedQueue.ThreadSafe
class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static class Node<E> {
        E item;
        volatile Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Node<E> head = new Node<>(null); // Dummy header node
    private final Node<E> tail;

    public LinkedQueue() {
        tail = head;
    }

    public boolean enqueue(E item) {
        Node<E> newNode = new Node<>(item);
        while (true) {
            synchronized (tail) {
                if (tail.next == null) {
                    tail.next = newNode;
                    return true;
                }
            }
        }
    }

    public E dequeue() {
        while (true) {
            synchronized (head) {
                if (head.next == null) {
                    return null; // Queue is empty
                }
                Node<E> first = head.next;
                E item = first.item;
                if (item != null) {
                    first.item = null; // Clear the reference for GC
                    if (head.next != null) {
                        head.next = first.next; // Move the head to the next node
                    }
                    return item;
                }
            }
        }
    }

    public boolean isEmpty() {
        synchronized (head) {
            return head.next == null;
        }
    }

    public int size() {
        int count = 0;
        synchronized (head) {
            Node<E> current = head.next;
            while (current != null) {
                count++;
                current = current.next;
            }
        }
        return count;
    }
}
