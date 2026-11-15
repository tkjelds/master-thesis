@DictionaryBag.ThreadSafe
public class DictionaryBag {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final EntryNode[] table;
    private static final int INITIAL_CAPACITY = 1024;

    private static class EntryNode {
        final Object key;
        ValueNode valueHead;
        EntryNode next;

        EntryNode(Object key, ValueNode head, EntryNode next) {
            this.key = key;
            this.valueHead = head;
            this.next = next;
        }
    }

    private static class ValueNode {
        final Object value;
        ValueNode next;

        ValueNode(Object value, ValueNode next) {
            this.value = value;
            this.next = next;
        }
    }

    public DictionaryBag() {
        table = new EntryNode[INITIAL_CAPACITY];
    }

    public void put(Object key, Object value) {
        if (key == null) {
            return;
        }
        synchronized (lock) {
            int index = Math.abs(key.hashCode() % INITIAL_CAPACITY);
            EntryNode current = table[index];

            while (current != null) {
                if (key.equals(current.key)) {
                    ValueNode newNode = new ValueNode(value, current.valueHead);
                    current.valueHead = newNode;
                    return;
                }
                current = current.next;
            }

            EntryNode newEntry = new EntryNode(key, new ValueNode(value, null), table[index]);
            table[index] = newEntry;
        }
    }

    public int getCount(Object key) {
        if (key == null) {
            return 0;
        }
        int count = 0;
        synchronized (lock) {
            int index = Math.abs(key.hashCode() % INITIAL_CAPACITY);
            EntryNode current = table[index];

            while (current != null) {
                if (key.equals(current.key)) {
                    ValueNode v = current.valueHead;
                    while (v != null) {
                        count++;
                        v = v.next;
                    }
                    return count;
                }
                current = current.next;
            }
        }
        return 0;
    }

    public boolean contains(Object key) {
        if (key == null) {
            return false;
        }
        synchronized (lock) {
            int index = Math.abs(key.hashCode() % INITIAL_CAPACITY);
            EntryNode current = table[index];

            while (current != null) {
                if (key.equals(current.key)) {
                    return current.valueHead != null;
                }
                current = current.next;
            }
        }
        return false;
    }

    public boolean remove(Object key, Object value) {
        if (key == null || value == null) {
            return false;
        }
        synchronized (lock) {
            int index = Math.abs(key.hashCode() % INITIAL_CAPACITY);
            EntryNode current = table[index];

            while (current != null) {
                if (key.equals(current.key)) {
                    ValueNode head = current.valueHead;
                    ValueNode prev = null;
                    ValueNode currV = head;

                    while (currV != null) {
                        if (value.equals(currV.value)) {
                            if (prev == null) {
                                current.valueHead = currV.next;
                            } else {
                                prev.next = currV.next;
                            }
                            return true;
                        }
                        prev = currV;
                        currV = currV.next;
                    }
                }
                current = current.next;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            for (int i = 0; i < INITIAL_CAPACITY; i++) {
                if (table[i] != null) {
                    return false;
                }
            }
            return true;
        }
    }
}
