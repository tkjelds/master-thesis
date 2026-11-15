@LinkedDeque.ThreadSafe
public class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static class Node<E> {
        E item;
        Node<E> next, prev;

        Node(E x) { item = x; }
    }

    private Node<E> first, last;
    private int size;

    public void addFirst(E e) {
        final Node<E> f = first;
        final Node<E> newNode = new Node<>(e);
        newNode.next = f;
        first = newNode;
        if (f == null)
            last = newNode;
        else
            f.prev = newNode;
        size++;
        notifyAll();
    }

    public void addLast(E e) {
        final Node<E> l = last;
        final Node<E> newNode = new Node<>(e);
        newNode.prev = l;
        last = newNode;
        if (l == null)
            first = newNode;
        else
            l.next = newNode;
        size++;
        notifyAll();
    }

    public E removeFirst() throws InterruptedException {
        for (;;) {
            Node<E> f, p;
            synchronized (this) {
                while (first == null)
                    wait();
                f = first;
                p = f.prev;
                if (f == first) {
                    first = p;
                    if (p == null)
                        last = null;
                    else
                        p.next = null;
                }
            }
            if (p != null) {
                lock.unlink(p, f);
            }
            return f.item;
        }
    }

    public E removeLast() throws InterruptedException {
        for (;;) {
            Node<E> l, p;
            synchronized (this) {
                while (last == null)
                    wait();
                l = last;
                p = l.next;
                if (l == last) {
                    last = p;
                    if (p == null)
                        first = null;
                    else
                        p.prev = null;
                }
            }
            if (p != null) {
                lock.unlink(p, l);
            }
            return l.item;
        }
    }

    public E getFirst() {
        final Node<E> f = first;
        if (f == null)
            throw new NoSuchElementException();
        return f.item;
    }

    public E getLast() {
        final Node<E> l = last;
        if (l == null)
            throw new NoSuchElementException();
        return l.item;
    }

    public int size() {
        return size;
    }
}
