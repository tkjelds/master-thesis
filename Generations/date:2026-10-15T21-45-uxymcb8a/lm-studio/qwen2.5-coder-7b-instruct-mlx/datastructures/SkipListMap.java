@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 16;
    private Node<K, V> head;
    private int level;

    public SkipListMap() {
        head = new Node<>(null, null, MAX_LEVEL);
        level = 1;
    }

    public V put(K key, V value) {
        Node<K, V> current = head;
        Node<K, V>[] update = new Node[MAX_LEVEL];
        
        synchronized (head) {
            for (int i = level - 1; i >= 0; --i) {
                while (current.forward[i] != null && current.forward[i].key.compareTo(key) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }
            
            Node<K, V> existingNode = current.forward[0];
            if (existingNode != null && existingNode.key.equals(key)) {
                V oldValue = existingNode.value;
                existingNode.value = value;
                return oldValue;
            }
            
            int newNodeLevel = randomLevel();
            if (newNodeLevel > level) {
                for (int i = level; i < newNodeLevel; ++i) {
                    update[i] = head;
                }
                level = newNodeLevel;
            }
            
            Node<K, V> newNode = new Node<>(key, value, newNodeLevel);
            for (int i = 0; i < newNodeLevel; ++i) {
                newNode.forward[i] = update[i].forward[i];
                update[i].forward[i] = newNode;
            }
        }
        
        return null;
    }

    public V get(K key) {
        Node<K, V> current = head;
        
        synchronized (head) {
            for (int i = level - 1; i >= 0; --i) {
                while (current.forward[i] != null && current.forward[i].key.compareTo(key) < 0) {
                    current = current.forward[i];
                }
            }
            
            Node<K, V> foundNode = current.forward[0];
            if (foundNode != null && foundNode.key.equals(key)) {
                return foundNode.value;
            }
        }
        
        return null;
    }

    public V remove(K key) {
        Node<K, V> current = head;
        Node<K, V>[] update = new Node[MAX_LEVEL];
        
        synchronized (head) {
            for (int i = level - 1; i >= 0; --i) {
                while (current.forward[i] != null && current.forward[i].key.compareTo(key) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }
            
            Node<K, V> removedNode = current.forward[0];
            if (removedNode != null && removedNode.key.equals(key)) {
                for (int i = 0; i < level; ++i) {
                    if (update[i].forward[i] != removedNode) {
                        break;
                    }
                    update[i].forward[i] = removedNode.forward[i];
                }
                
                while (level > 1 && head.forward[level - 1] == null) {
                    level--;
                }
                
                return removedNode.value;
            }
        }
        
        return null;
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && Math.random() < 0.5) {
            level++;
        }
        return level;
    }

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V>[] forward;

        @SuppressWarnings("unchecked")
        public Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            forward = new Node[level];
        }
    }
}
