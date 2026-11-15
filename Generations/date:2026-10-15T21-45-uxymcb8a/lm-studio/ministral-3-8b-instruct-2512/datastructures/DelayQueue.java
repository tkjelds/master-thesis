@DelayQueue.ThreadSafe
public class DelayQueue {
    @interface ThreadSafe {}

    private static class Node {
        volatile Object item;
        long delayTime; // Time in milliseconds when the item can be taken
        Node next;

        Node(Object item, long delayTime) {
            this.item = item;
            this.delayTime = System.currentTimeMillis() + delayTime;
        }
    }

    private final Node[] queue;
    private int head = 0, tail = 0;
    private int size = 0;

    public DelayQueue(int capacity) {
        this.queue = new Node[capacity];
    }

    public boolean offer(Object item, long delayTime) {
        synchronized (this) {
            if (size >= queue.length) {
                return false;
            }
            Node newNode = new Node(item, delayTime);
            queue[tail] = newNode;
            tail = (tail + 1) % queue.length;
            size++;
        }
        return true;
    }

    public Object poll() {
        synchronized (this) {
            while (size == 0 || peekDelayTime() > System.currentTimeMillis()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            Node node = queue[head];
            Object item = node.item;

            // Remove the node
            head = (head + 1) % queue.length;
            size--;

            // Check if the queue is empty after removal
            if (size == 0) {
                tail = head;
            }

            return item;
        }
    }

    public Object peek() {
        synchronized (this) {
            if (size == 0 || peekDelayTime() > System.currentTimeMillis()) {
                return null;
            }
            Node node = queue[head];
            return node.item;
        }
    }

    private long peekDelayTime() {
        if (size == 0) return Long.MAX_VALUE;
        Node node = queue[head];
        return node.delayTime;
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0 || peekDelayTime() > System.currentTimeMillis();
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    private void notifyWaitingThreads() {
        synchronized (this) {
            notifyAll();
        }
    }

    public void clear() {
        synchronized (this) {
            head = 0;
            tail = 0;
            size = 0;
        }
    }

    public void addDelayQueueListener(Runnable listener) {
        synchronized (this) {
            // This is a simplified approach; in practice, you'd need a more robust listener system.
            // Here we just notify all waiting threads when the queue changes.
        }
    }

    public void removeDelayQueueListener(Runnable listener) {
        // Not implemented for simplicity
    }
}
