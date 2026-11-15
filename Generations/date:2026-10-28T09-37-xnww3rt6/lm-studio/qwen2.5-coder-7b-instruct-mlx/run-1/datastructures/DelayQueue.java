@DelayQueue.ThreadSafe
public class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 10;

    private static class Node<E> {
        private E item;
        private long delay;

        Node(E item, long delay) {
            this.item = item;
            this.delay = System.currentTimeMillis() + delay;
        }

        public E getItem() {
            return item;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > delay;
        }
    }

    private Node<E>[] queue = (Node<E>[]) new Node[DEFAULT_CAPACITY];
    private int size = 0;
    private int head = 0;

    public boolean add(E item, long delay) {
        synchronized (this) {
            while (size == queue.length) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            int index = (head + size) % queue.length;
            queue[index] = new Node<>(item, delay);
            size++;
            this.notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (this) {
            while (isEmpty()) {
                this.wait();
            }
            E item = poll();
            if (item != null) {
                this.notifyAll();
            }
            return item;
        }
    }

    public E poll() {
        synchronized (this) {
            if (isEmpty()) {
                return null;
            }
            E item = null;
            while (item == null || !((Node<E>) item).isExpired()) {
                if (isEmpty()) {
                    break;
                }
                Node<E> node = queue[head];
                if (node.isExpired()) {
                    item = node.getItem();
                    queue[head] = null;
                    head = (head + 1) % queue.length;
                    size--;
                } else {
                    break;
                }
            }
            return item;
        }
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }
}
