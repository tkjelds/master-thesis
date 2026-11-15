@B_Tres.ThreadSafe
<final_answer>
/Users/tore/master-thesis/Generations/date:2026-10-15T21-45-uxymcb8a/lm-studio/google%2Fgemma-4-12b/datastructures/B_Tres.java:1-260
/Users/tore/master-thesis/Generations/date:2026-10-15T21-45-uxymcb8a/lm-studio/qwen%2Fqwen3.5-9b@reasoning-off/datastructures/B_Tres.java:1-260
/Users/tore/master-thesis/Generations/date:2026-10-19T16-41-fo5imwnt/lm-studio/qwen2.5-coder-7b-instruct-mlx/datastructures/b_tres.java:1-260
/Users/tore/master-thesis/Generations/date:2026-19-09-21T20-02/copilot-no-tools/gpt-5.6-luna/b_treslinked_list.java:1-260
/pipeline/datastructures.py:1-40
/pipeline/base_prompt.py:20-60
</final_answer>
public class B_Tres<T> {
    @interface ThreadSafe {}

    private final Object[] items;
    private int head;
    private int tail;
    private int count;

    public B_Tres(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero.");
        }
        items = new Object[capacity];
    }

    public void put(T item) throws InterruptedException {
        synchronized (this) {
            while (count == items.length) {
                wait();
            }

            items[tail] = item;
            tail = (tail + 1) % items.length;
            count++;
            notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }

            T item = (T) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            notifyAll();
            return item;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public int getCapacity() {
        synchronized (this) {
            return items.length;
        }
    }

    public int capacity() {
        synchronized (this) {
            return items.length;
        }
    }
}
