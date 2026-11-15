import java.lang.ref.WeakReference;

@Order.ThreadSafe
class Order<T> {
    @interface ThreadSafe {}

    int compare(T left, T right);
}

interface Handle<T> {}

@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}

    private static final class Node<T> implements Handle<T> {
        T item;
        int low, high;
        boolean isMin;

        Node(T item, int low, int high, boolean isMin) {
            this.item = item;
            this.low = low;
            this.high = high;
            this.isMin = isMin;
        }
    }

    private T[] elements;
    private Node<T>[] nodes;
    private Order<T> order;
    private int size;

    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
        elements = (T[]) new Object[10];
        nodes = new Node[10];
    }

    @SuppressWarnings("unchecked")
    private void grow() {
        int newSize = elements.length * 2;
        T[] newElements = (T[]) new Object[newSize];
        Node<T>[] newNodes = new Node[newSize];
        System.arraycopy(elements, 0, newElements, 0, size);
        System.arraycopy(nodes, 0, newNodes, 0, size);
        elements = newElements;
        nodes = newNodes;
    }

    private int parent(int i) {
        return (i - 1) / 2;
    }

    private int left(int i) {
        return 2 * i + 1;
    }

    private int right(int i) {
        return 2 * i + 2;
    }

    private void swapMin(int i, int j) {
        Node<T> temp = nodes[i];
        nodes[i] = nodes[j];
        nodes[j] = temp;
        if (nodes[i].isMin) {
            elements[nodes[i].low] = nodes[j].item;
        } else {
            elements[nodes[i].high] = nodes[j].item;
        }
        if (nodes[j].isMin) {
            elements[nodes[j].low] = nodes[i].item;
        } else {
            elements[nodes[j].high] = nodes[i].item;
        }
    }

    private void swapMax(int i, int j) {
        Node<T> temp = nodes[i];
        nodes[i] = nodes[j];
        nodes[j] = temp;
        if (nodes[i].isMin) {
            elements[nodes[i].low] = nodes[j].item;
        } else {
            elements[nodes[i].high] = nodes[j].item;
        }
        if (nodes[j].isMin) {
            elements[nodes[j].low] = nodes[i].item;
        } else {
            elements[nodes[j].high] = nodes[i].item;
        }
    }

    private void minHeapify(int i) {
        int left = left(i);
        int right = right(i);
        int smallest = i;
        if (left < size && order.compare(elements[left], elements[smallest]) < 0) {
            smallest = left;
        }
        if (right < size && order.compare(elements[right], elements[smallest]) < 0) {
            smallest = right;
        }
        if (smallest != i) {
            swapMin(i, smallest);
            minHeapify(smallest);
        }
    }

    private void maxHeapify(int i) {
        int left = left(i);
        int right = right(i);
        int largest = i;
        if (left < size && order.compare(elements[left], elements[largest]) > 0) {
            largest = left;
        }
        if (right < size && order.compare(elements[right], elements[largest]) > 0) {
            largest = right;
        }
        if (largest != i) {
            swapMax(i, largest);
            maxHeapify(largest);
        }
    }

    public synchronized Handle<T> add(T item) {
        if (item == null) throw new NullPointerException();
        if (size >= elements.length) grow();
        int low = size, high = size;
        nodes[size++] = new Node<>(item, low, high, true);
        elements[low] = item;
        minHeapify(0);
        return nodes[size - 1];
    }

    public synchronized T findMin() {
        if (size == 0) throw new IllegalStateException();
        return elements[nodes[0].low];
    }

    public synchronized T findMax() {
        if (size == 0) throw new IllegalStateException();
        return elements[nodes[0].high];
    }

    public synchronized T deleteMin() {
        if (size == 0) throw new IllegalStateException();
        T result = elements[nodes[0].low];
        nodes[0] = nodes[--size];
        minHeapify(0);
        nodes[size] = null;
        return result;
    }

    public synchronized T deleteMax() {
        if (size == 0) throw new IllegalStateException();
        T result = elements[nodes[0].high];
        nodes[0] = nodes[--size];
        maxHeapify(0);
        nodes[size] = null;
        return result;
    }

    @SuppressWarnings("unchecked")
    public synchronized T delete(Handle<T> handle) {
        if (!(handle instanceof Node)) throw new IllegalArgumentException();
        Node<T> node = (Node<T>) handle;
        if (node == null || node.item == null) throw new IllegalArgumentException();
        T result = node.item;
        int lowIndex = left(node.low);
        if (lowIndex < size) {
            swapMin(lowIndex, size - 1);
            nodes[size - 1] = null;
            minHeapify(lowIndex);
        }
        int highIndex = left(node.high);
        if (highIndex < size) {
            swapMax(highIndex, 0);
            nodes[0] = null;
            maxHeapify(0);
        }
        return result;
    }

    public synchronized T replace(Handle<T> handle, T item) {
        if (!(handle instanceof Node)) throw new IllegalArgumentException();
        Node<T> node = (Node<T>) handle;
        if (node == null || node.item == null) throw new IllegalArgumentException();
        T oldItem = node.item;
        if (item == null) throw new NullPointerException();
        node.item = item;
        if (order.compare(item, oldItem) < 0) {
            minHeapify(node.low);
        } else if (order.compare(item, oldItem) > 0) {
            maxHeapify(node.low);
        }
        return oldItem;
    }

    public synchronized int size() {
        return size;
    }
}
