```java
public class ThreadSafeDictionary {
    private final Node head;

    public ThreadSafeDictionary() {
        head = new Node(null);
    }

    public void insert(String key, String value) {
        synchronized (head) {
            Node current = head;
            while (current.next != null && !current.next.key.equals(key)) {
                current = current.next;
            }
            if (current.next == null) {
                current.next = new Node(key, value);
            } else {
                current.next.value = value;
            }
        }
    }

    public String search(String key) {
        synchronized (head) {
            Node current = head;
            while (current.next != null && !current.next.key.equals(key)) {
                current = current.next;
            }
            return current.next == null ? null : current.next.value;
        }
    }

    public void remove(String key) {
        synchronized (head) {
            Node current = head;
            while (current.next != null && !current.next.key.equals(key)) {
                current = current.next;
            }
            if (current.next != null) {
                current.next = current.next.next;
            }
        }
    }

    private static class Node {
        final String key;
        String value;
        Node next;

        Node(String key) {
            this(key, null);
        }

        Node(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
```
