```java
public class DelayQueue<T> {
    private final Object[] queue;
    private int size = 0;

    public DelayQueue(int capacity) {
        queue = new Object[capacity];
    }

    public void put(T item, long delay) throws InterruptedException {
        synchronized (queue) {
            while (size == queue.length) {
                queue.wait();
            }
            insert(item, delay);
        }
    }

    public T take() throws InterruptedException {
        synchronized (queue) {
            while (size == 0) {
                queue.wait();
            }
            T item = extract();
            return item;
        }
    }

    private void insert(T item, long delay) {
        int index = size;
        while (index > 0 && ((Item<T>) queue[index - 1]).getDelay() > delay) {
            index--;
        }
        System.arraycopy(queue, index, queue, index + 1, size - index);
        queue[index] = new Item<>(item, System.currentTimeMillis() + delay);
        size++;
        queue.notifyAll();
    }

    private T extract() {
        T item = ((Item<T>) queue[0]).item;
        System.arraycopy(queue, 1, queue, 0, --size);
        if (size == queue.length / 2) {
            resize(queue.length / 2);
        }
        queue.notifyAll();
        return item;
    }

    private void resize(int newCapacity) {
        Object[] newArray = new Object[newCapacity];
        System.arraycopy(queue, 0, newArray, 0, size);
        queue = newArray;
    }

    private static class Item<T> {
        T item;
        long expiration;

        Item(T item, long expiration) {
            this.item = item;
            this.expiration = expiration;
        }

        long getDelay() {
            return expiration - System.currentTimeMillis();
        }
    }
}
```
