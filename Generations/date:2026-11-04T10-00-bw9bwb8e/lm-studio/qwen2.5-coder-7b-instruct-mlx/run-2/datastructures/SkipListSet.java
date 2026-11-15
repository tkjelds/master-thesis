import java.util.concurrent.atomic.AtomicReference;

@SkipListSet.ThreadSafe
public class SkipListSet<E extends Comparable<E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 16;
    private Node<E> head;

    public SkipListSet() {
        head = new Node<>(null, MAX_LEVEL);
    }

    public boolean add(E element) {
        while (true) {
            Node<E> pred = head;
            Node<E> curr = pred.next[0];
            int level = MAX_LEVEL - 1;
            while (true) {
                if (curr != null && curr.element.compareTo(element) < 0) {
                    pred = curr;
                    curr = pred.next[level];
                } else {
                    if (level == 0) {
                        boolean added = insert(pred, curr, level, element);
                        if (added) return true;
                    } else {
                        pred = advance(pred, level, element);
                    }
                }
                if (level > 0) {
                    level--;
                } else {
                    break;
                }
            }
        }
    }

    private Node<E> advance(Node<E> pred, int level, E element) {
        while (true) {
            Node<E> curr = pred.next[level];
            if (curr != null && curr.element.compareTo(element) < 0) {
                pred = curr;
                curr = pred.next[level];
            } else {
                return pred;
            }
        }
    }

    private boolean insert(Node<E> pred, Node<E> curr, int level, E element) {
        while (true) {
            if (curr == null || curr.element.compareTo(element) > 0) {
                Node<E> newNode = new Node<>(element, level);
                if (CAS(pred.next[level], curr, newNode)) {
                    return true;
                } else {
                    pred = advance(pred, level, element);
                }
            } else if (curr.element.compareTo(element) == 0) {
                return false;
            }
        }
    }

    private boolean CAS(AtomicReference<Node<E>> target, Node<E> expected, Node<E> update) {
        return target.compareAndSet(expected, update);
    }

    private static class Node<E> {
        E element;
        AtomicReference<Node<E>>[] next;

        @SuppressWarnings("unchecked")
        public Node(E element, int level) {
            this.element = element;
            next = (AtomicReference<Node<E>>[]) new AtomicReference[level + 1];
        }
    }

    public boolean contains(E element) {
        Node<E> pred = head;
        for (int level = MAX_LEVEL - 1; level >= 0; level--) {
            while (true) {
                Node<E> curr = pred.next[level];
                if (curr != null && curr.element.compareTo(element) < 0) {
                    pred = curr;
                } else {
                    break;
                }
            }
        }
        Node<E> curr = pred.next[0];
        return curr != null && curr.element.compareTo(element) == 0;
    }

    public boolean remove(E element) {
        Node<E> pred = head;
        while (true) {
            Node<E> curr = pred.next[0];
            int level = MAX_LEVEL - 1;
            while (true) {
                if (curr != null && curr.element.compareTo(element) < 0) {
                    pred = curr;
                    curr = pred.next[level];
                } else {
                    if (curr != null && curr.element.compareTo(element) == 0) {
                        boolean removed = delete(pred, curr, level);
                        if (removed) return true;
                    } else {
                        if (level == 0) {
                            return false;
                        } else {
                            pred = advance(pred, level, element);
                        }
                    }
                }
                if (level > 0) {
                    level--;
                } else {
                    break;
                }
            }
        }
    }

    private boolean delete(Node<E> pred, Node<E> curr, int level) {
        while (true) {
            if (curr != null && curr.element.compareTo(pred.next[level].get().element) == 0) {
                pred.next[level].set(curr.next[level].get());
                CAS(pred.next[level], curr, curr.next[level].get());
                return true;
            } else {
                pred = advance(pred, level, curr.element);
            }
        }
    }

    public boolean isEmpty() {
        return !contains(null);
    }

    public int size() {
        int count = 0;
        Node<E> curr = head.next[0];
        while (curr != null) {
            count++;
            curr = curr.next[0].get();
        }
        return count;
    }
}
