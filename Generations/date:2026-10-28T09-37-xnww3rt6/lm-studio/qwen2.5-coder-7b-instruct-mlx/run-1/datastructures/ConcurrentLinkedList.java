@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
            this.next = null;
        }
    }

    private Node<T> head;

    public ConcurrentLinkedList() {
        this.head = new Node<>(null);
    }

    public void add(T value) {
        synchronized (head) {
            Node<T> newNode = new Node<>(value);
            Node<T> current = head;
            while (current.next != null) {
                current = current.next;
            }
            current.next = newNode;
        }
    }

    public boolean remove(T value) {
        synchronized (head) {
            Node<T> current = head;
            while (current.next != null) {
                if (current.next.value.equals(value)) {
                    current.next = current.next.next;
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public boolean contains(T value) {
        synchronized (head) {
            Node<T> current = head.next;
            while (current != null) {
                if (current.value.equals(value)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public int size() {
        synchronized (head) {
            Node<T> current = head.next;
            int count = 0;
            while (current != null) {
                count++;
                current = current.next;
            }
            return count;
        }
    }

    public void clear() {
        synchronized (head) {
            head.next = null;
        }
    }

    public boolean isEmpty() {
        synchronized (head) {
            return head.next == null;
        }
    }

    public T get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
        }
        synchronized (head) {
            Node<T> current = head.next;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            return current.value;
        }
    }

    public void set(int index, T value) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
        }
        synchronized (head) {
            Node<T> current = head.next;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            current.value = value;
        }
    }

    public void add(int index, T value) {
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
        }
        synchronized (head) {
            Node<T> newNode = new Node<>(value);
            Node<T> current = head;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            newNode.next = current.next;
            current.next = newNode;
        }
    }

    public T remove(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
        }
        synchronized (head) {
            Node<T> current = head;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            Node<T> removedNode = current.next;
            current.next = removedNode.next;
            return removedNode.value;
        }
    }

    public int indexOf(T value) {
        synchronized (head) {
            Node<T> current = head.next;
            int index = 0;
            while (current != null) {
                if (current.value.equals(value)) {
                    return index;
                }
                current = current.next;
                index++;
            }
            return -1;
        }
    }

    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ConcurrentLinkedList)) return false;
        ConcurrentLinkedList<?> other = (ConcurrentLinkedList<?>) obj;
        synchronized (head) {
            Node<T> current1 = head.next;
            Node<?> current2 = other.head.next;
            while (current1 != null && current2 != null) {
                if (!current1.value.equals(current2.value)) return false;
                current1 = current1.next;
                current2 = current2.next;
            }
            return current1 == null && current2 == null;
        }
    }

    public int hashCode() {
        int result = 17;
        synchronized (head) {
            Node<T> current = head.next;
            while (current != null) {
                result = 31 * result + current.value.hashCode();
                current = current.next;
            }
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        synchronized (head) {
            Node<T> current = head.next;
            while (current != null) {
                sb.append(current.value);
                if (current.next != null) sb.append(", ");
                current = current.next;
            }
        }
        return "[" + sb.toString() + "]";
    }
}
