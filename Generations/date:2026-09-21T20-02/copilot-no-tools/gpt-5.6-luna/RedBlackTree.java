public final class RedBlackTree<K extends Comparable<? super K>, V> {
    private static final boolean RED = true;
    private static final boolean BLACK = false;

 private final class Node {
     private K key;
     private V value;
     private Node left;
     private Node right;
     private Node parent;
     private boolean color;

     private Node(K key, V value, boolean color, Node parent) {
         this.key = key;
         this.value = value;
         this.color = color;
         this.parent = parent;
     }
 }

 private Node root;
 private int size;

 public synchronized V put(K key, V value) {
     requireKey(key);

     if (root == null) {
         root = new Node(key, value, BLACK, null);
         size = 1;
         notifyAll();
         return null;
     }

     Node current = root;
     Node parent;

     do {
         parent = current;
         int comparison = key.compareTo(current.key);

         if (comparison == 0) {
             V previous = current.value;
             current.value = value;
             return previous;
         }

         current = comparison < 0 ? current.left : current.right;
     } while (current != null);

     Node inserted = new Node(key, value, RED, parent);
     if (key.compareTo(parent.key) < 0) {
         parent.left = inserted;
     } else {
         parent.right = inserted;
     }

     size++;
     fixAfterInsertion(inserted);
     notifyAll();
     return null;
 }

 public synchronized V putIfAbsent(K key, V value) {
     requireKey(key);

     Node existing = findNode(key);
     if (existing != null) {
         return existing.value;
     }

     put(key, value);
     return null;
 }

 public synchronized V get(K key) {
     requireKey(key);
     Node node = findNode(key);
     return node == null ? null : node.value;
 }

 public synchronized V getOrDefault(K key, V defaultValue) {
     requireKey(key);
     Node node = findNode(key);
     return node == null ? defaultValue : node.value;
 }

 public synchronized boolean containsKey(K key) {
     requireKey(key);
     return findNode(key) != null;
 }

 public synchronized boolean containsValue(V value) {
     return containsValue(root, value);
 }

 public synchronized V remove(K key) {
     requireKey(key);

     Node node = findNode(key);
     if (node == null) {
         return null;
     }

     V previous = node.value;
     deleteNode(node);
     notifyAll();
     return previous;
 }

 public synchronized boolean remove(K key, V value) {
     requireKey(key);

     Node node = findNode(key);
     if (node == null || !equalsValue(node.value, value)) {
         return false;
     }

     deleteNode(node);
     notifyAll();
     return true;
 }

 public synchronized void clear() {
     root = null;
     size = 0;
     notifyAll();
 }

 public synchronized int size() {
     return size;
 }

 public synchronized boolean isEmpty() {
     return size == 0;
 }

 public synchronized K firstKey() {
     if (root == null) {
         return null;
     }
     return minimum(root).key;
 }

 public synchronized K lastKey() {
     if (root == null) {
         return null;
     }
     return maximum(root).key;
 }

 public synchronized V firstValue() {
     if (root == null) {
         return null;
     }
     return minimum(root).value;
 }

 public synchronized V lastValue() {
     if (root == null) {
         return null;
     }
     return maximum(root).value;
 }

 public synchronized int height() {
     return height(root);
 }

 private void requireKey(K key) {
     if (key == null) {
         throw new IllegalArgumentException("null keys are not supported");
     }
 }

 private boolean equalsValue(V first, V second) {
     return first == second || (first != null && first.equals(second));
 }

 private boolean containsValue(Node node, V value) {
     if (node == null) {
         return false;
     }

     if (equalsValue(node.value, value)) {
         return true;
     }

     return containsValue(node.left, value) || containsValue(node.right, value);
 }

 private int height(Node node) {
     if (node == null) {
         return 0;
     }

     int leftHeight = height(node.left);
     int rightHeight = height(node.right);
     return 1 + (leftHeight > rightHeight ? leftHeight : rightHeight);
 }

 private Node findNode(K key) {
     Node current = root;

     while (current != null) {
         int comparison = key.compareTo(current.key);

         if (comparison == 0) {
             return current;
         }

         current = comparison < 0 ? current.left : current.right;
     }

     return null;
 }

 private Node minimum(Node node) {
     Node current = node;
     while (current.left != null) {
         current = current.left;
     }
     return current;
 }

 private Node maximum(Node node) {
     Node current = node;
     while (current.right != null) {
         current = current.right;
     }
     return current;
 }

 private boolean colorOf(Node node) {
     return node == null ? BLACK : node.color;
 }

 private void setColor(Node node, boolean color) {
     if (node != null) {
         node.color = color;
     }
 }

 private Node parentOf(Node node) {
     return node == null ? null : node.parent;
 }

 private Node leftOf(Node node) {
     return node == null ? null : node.left;
 }

 private Node rightOf(Node node) {
     return node == null ? null : node.right;
 }

 private void fixAfterInsertion(Node node) {
     node.color = RED;

     while (node != null && node != root && colorOf(parentOf(node)) == RED) {
         Node parent = parentOf(node);
         Node grandparent = parentOf(parent);

         if (parent == leftOf(grandparent)) {
             Node uncle = rightOf(grandparent);

             if (colorOf(uncle) == RED) {
                 setColor(parent, BLACK);
                 setColor(uncle, BLACK);
                 setColor(grandparent, RED);
                 node = grandparent;
             } else {
                 if (node == rightOf(parent)) {
                     node = parent;
                     rotateLeft(node);
                     parent = parentOf(node);
                     grandparent = parentOf(parent);
                 }

                 setColor(parent, BLACK);
                 setColor(grandparent, RED);
                 rotateRight(grandparent);
             }
         } else {
             Node uncle = leftOf(grandparent);

             if (colorOf(uncle) == RED) {
                 setColor(parent, BLACK);
                 setColor(uncle, BLACK);
                 setColor(grandparent, RED);
                 node = grandparent;
             } else {
                 if (node == leftOf(parent)) {
                     node = parent;
                     rotateRight(node);
                     parent = parentOf(node);
                     grandparent = parentOf(parent);
                 }

                 setColor(parent, BLACK);
                 setColor(grandparent, RED);
                 rotateLeft(grandparent);
             }
         }
     }

     setColor(root, BLACK);
 }

 private void deleteNode(Node node) {
     Node replacement;
     Node replacementParent;
     Node successor = node;
     boolean successorColor = successor.color;

     if (node.left == null) {
         replacement = node.right;
         replacementParent = node.parent;
         transplant(node, node.right);
     } else if (node.right == null) {
         replacement = node.left;
         replacementParent = node.parent;
         transplant(node, node.left);
     } else {
         successor = minimum(node.right);
         successorColor = successor.color;
         replacement = successor.right;

         if (successor.parent == node) {
             replacementParent = successor;
             if (replacement != null) {
                 replacement.parent = successor;
             }
         } else {
             replacementParent = successor.parent;
             transplant(successor, successor.right);
             successor.right = node.right;
             successor.right.parent = successor;
         }

         transplant(node, successor);
         successor.left = node.left;
         successor.left.parent = successor;
         successor.color = node.color;
     }

     size--;

     if (successorColor == BLACK) {
         fixAfterDeletion(replacement, replacementParent);
     }

     node.left = null;
     node.right = null;
     node.parent = null;
 }

 private void fixAfterDeletion(Node node, Node parent) {
     while (node != root && colorOf(node) == BLACK) {
         if (parent == null) {
             break;
         }

         if (node == parent.left) {
             Node sibling = parent.right;

             if (colorOf(sibling) == RED) {
                 setColor(sibling, BLACK);
                 setColor(parent, RED);
                 rotateLeft(parent);
                 sibling = parent.right;
             }

             if (colorOf(leftOf(sibling)) == BLACK
                     && colorOf(rightOf(sibling)) == BLACK) {
                 setColor(sibling, RED);
                 node = parent;
                 parent = node.parent;
             } else {
                 if (colorOf(rightOf(sibling)) == BLACK) {
                     setColor(leftOf(sibling), BLACK);
                     setColor(sibling, RED);
                     rotateRight(sibling);
                     sibling = parent.right;
                 }

                 setColor(sibling, colorOf(parent));
                 setColor(parent, BLACK);
                 setColor(rightOf(sibling), BLACK);
                 rotateLeft(parent);
                 node = root;
                 parent = null;
             }
         } else {
             Node sibling = parent.left;

             if (colorOf(sibling) == RED) {
                 setColor(sibling, BLACK);
                 setColor(parent, RED);
                 rotateRight(parent);
                 sibling = parent.left;
             }

             if (colorOf(rightOf(sibling)) == BLACK
                     && colorOf(leftOf(sibling)) == BLACK) {
                 setColor(sibling, RED);
                 node = parent;
                 parent = node.parent;
             } else {
                 if (colorOf(leftOf(sibling)) == BLACK) {
                     setColor(rightOf(sibling), BLACK);
                     setColor(sibling, RED);
                     rotateLeft(sibling);
                     sibling = parent.left;
                 }

                 setColor(sibling, colorOf(parent));
                 setColor(parent, BLACK);
                 setColor(leftOf(sibling), BLACK);
                 rotateRight(parent);
                 node = root;
                 parent = null;
             }
         }
     }

     setColor(node, BLACK);
 }

 private void transplant(Node oldNode, Node newNode) {
     if (oldNode.parent == null) {
         root = newNode;
     } else if (oldNode == oldNode.parent.left) {
         oldNode.parent.left = newNode;
     } else {
         oldNode.parent.right = newNode;
     }

     if (newNode != null) {
         newNode.parent = oldNode.parent;
     }
 }

 private void rotateLeft(Node node) {
     if (node == null) {
         return;
     }

     Node right = node.right;
     node.right = right.left;

     if (right.left != null) {
         right.left.parent = node;
     }

     right.parent = node.parent;

     if (node.parent == null) {
         root = right;
     } else if (node == node.parent.left) {
         node.parent.left = right;
     } else {
         node.parent.right = right;
     }

     right.left = node;
     node.parent = right;
 }

 private void rotateRight(Node node) {
     if (node == null) {
         return;
     }

     Node left = node.left;
     node.left = left.right;

     if (left.right != null) {
         left.right.parent = node;
     }

     left.parent = node.parent;

     if (node.parent == null) {
         root = left;
     } else if (node == node.parent.right) {
         node.parent.right = left;
     } else {
         node.parent.left = left;
     }

     left.right = node;
     node.parent = left;
 }

}
