@RedBlackTree.ThreadSafe
class RedBlackTree {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    class Node {
        int key;
        Node left, right, parent;
        boolean color;

        Node(int item) {
            key = item;
            left = right = parent = null;
            color = RED;
        }
    }

    Node root;
    Node TNULL;

    public RedBlackTree() {
        TNULL = new Node(0);
        TNULL.color = BLACK;
        root = TNULL;
    }

    private Node searchTreeHelper(Node node, int key) {
        if (node == TNULL || key == node.key)
            return node;

        if (key < node.key)
            return searchTreeHelper(node.left, key);
        return searchTreeHelper(node.right, key);
    }

    public Node search(int key) {
        return searchTreeHelper(this.root, key);
    }

    private void fixDelete(Node x) {
        Node s;
        while (x != root && x.color == BLACK) {
            if (x == x.parent.left) {
                s = x.parent.right;
                if (s.color == RED) {
                    s.color = BLACK;
                    x.parent.color = RED;
                    leftRotate(x.parent);
                    s = x.parent.right;
                }

                if (s.left.color == BLACK && s.right.color == BLACK) {
                    s.color = RED;
                    x = x.parent;
                } else {
                    if (s.right.color == BLACK) {
                        s.left.color = BLACK;
                        s.color = RED;
                        rightRotate(s);
                        s = x.parent.right;
                    }
                    s.color = x.parent.color;
                    x.parent.color = BLACK;
                    s.right.color = BLACK;
                    leftRotate(x.parent);
                    x = root;
                }
            } else {
                s = x.parent.left;
                if (s.color == RED) {
                    s.color = BLACK;
                    x.parent.color = RED;
                    rightRotate(x.parent);
                    s = x.parent.left;
                }

                if (s.right.color == BLACK && s.left.color == BLACK) {
                    s.color = RED;
                    x = x.parent;
                } else {
                    if (s.left.color == BLACK) {
                        s.right.color = BLACK;
                        s.color = RED;
                        leftRotate(s);
                        s = x.parent.left;
                    }
                    s.color = x.parent.color;
                    x.parent.color = BLACK;
                    s.left.color = BLACK;
                    rightRotate(x.parent);
                    x = root;
                }
            }
        }
        x.color = BLACK;
    }

    private void singleRotate(Node z, boolean left) {
        Node y;
        if (left) {
            y = z.right;
            z.right = y.left;
            if (y.left != TNULL) {
                y.left.parent = z;
            }
            y.parent = z.parent;
            if (z.parent == null) {
                this.root = y;
            } else if (z == z.parent.right) {
                z.parent.right = y;
            } else {
                z.parent.left = y;
            }
            y.left = z;
            z.parent = y;
        } else {
            y = z.left;
            z.left = y.right;
            if (y.right != TNULL) {
                y.right.parent = z;
            }
            y.parent = z.parent;
            if (z.parent == null) {
                this.root = y;
            } else if (z == z.parent.right) {
                z.parent.right = y;
            } else {
                z.parent.left = y;
            }
            y.right = z;
            z.parent = y;
        }
    }

    private void leftRotate(Node x) {
        singleRotate(x, true);
    }

    private void rightRotate(Node x) {
        singleRotate(x, false);
    }

    public void insert(int key) {
        Node node = new Node(key);
        node.parent = null;
        node.data = key;
        node.left = TNULL;
        node.right = TNULL;
        node.color = RED;

        Node y = null;
        Node x = this.root;

        while (x != TNULL) {
            y = x;
            if (node.data < x.data)
                x = x.left;
            else
                x = x.right;
        }

        node.parent = y;
        if (y == null)
            root = node;
        else if (node.data < y.data)
            y.left = node;
        else
            y.right = node;

        if (node.parent == null) {
            node.color = BLACK;
            return;
        }

        if (node.parent.parent == null)
            return;

        fixInsert(node);
    }

    private void fixInsert(Node k) {
        Node u;
        while (k.parent.color == RED) {
            if (k.parent == k.parent.parent.right) {
                u = k.parent.parent.left;
                if (u.color == RED) {
                    u.color = BLACK;
                    k.parent.color = BLACK;
                    k.parent.parent.color = RED;
                    k = k.parent.parent;
                } else {
                    if (k == k.parent.left) {
                        k = k.parent;
                        rightRotate(k);
                    }
                    k.parent.color = BLACK;
                    k.parent.parent.color = RED;
                    leftRotate(k.parent.parent);
                }
            } else {
                u = k.parent.parent.right;

                if (u.color == RED) {
                    u.color = BLACK;
                    k.parent.color = BLACK;
                    k.parent.parent.color = RED;
                    k = k.parent.parent;
                } else {
                    if (k == k.parent.right) {
                        k = k.parent;
                        leftRotate(k);
                    }
                    k.parent.color = BLACK;
                    k.parent.parent.color = RED;
                    rightRotate(k.parent.parent);
                }
            }

            if (k == root) {
                break;
            }
        }
        root.color = BLACK;
    }

    public void deleteNode(int data) {
        deleteNodeHelper(this.root, data);
    }

    private Node minimum(Node node) {
        while (node.left != TNULL) {
            node = node.left;
        }
        return node;
    }

    private void deleteNodeHelper(Node root, int key) {
        Node z = TNULL;
        Node x, y;
        while (root != TNULL) {
            if (root.data == key) {
                z = root;
            }

            if (root.data <= key) {
                root = root.right;
            } else {
                root = root.left;
            }
        }

        if (z == TNULL) {
            System.out.println("Couldn't find key in the tree");
            return;
        }

        y = z;
        int y_original_color = y.color;
        if (z.left == TNULL) {
            x = z.right;
            transplant(z, z.right);
        } else if (z.right == TNULL) {
            x = z.left;
            transplant(z, z.left);
        } else {
            y = minimum(z.right);
            y_original_color = y.color;
            x = y.right;
            if (y.parent == z) {
                x.parent = y;
            } else {
                transplant(y, y.right);
                y.right = z.right;
                y.right.parent = y;
            }

            transplant(z, y);
            y.left = z.left;
            y.left.parent = y;
            y.color = z.color;
        }

        if (y_original_color == BLACK) {
            fixDelete(x);
        }
    }

    private void transplant(Node u, Node v) {
        if (u.parent == null) {
            root = v;
        } else if (u == u.parent.left) {
            u.parent.left = v;
        } else
            u.parent.right = v;
        v.parent = u.parent;
    }

    public void printTree() {
        if (root != null)
            printHelper(this.root, "", true);
    }

    private void printHelper(Node currPtr, String indent, boolean last) {
        if (currPtr != TNULL) {
            System.out.print(indent);
            if (last) {
                System.out.print("R----");
                indent += "     ";
            } else {
                System.out.print("L----");
                indent += "|    ";
            }

            String s_color = (currPtr.color == RED) ? "RED" : "BLACK";
            System.out.println(currPtr.data + "(" + s_color + ")");
            printHelper(currPtr.left, indent, false);
            printHelper(currPtr.right, indent, true);
        }
    }

    public static void main(String[] args) {
        RedBlackTree tree = new RedBlackTree();
        tree.insert(55);
        tree.insert(40);
        tree.insert(65);
        tree.insert(60);
        tree.insert(75);
        tree.insert(57);

        System.out.println("Inorder traversal of the constructed Red Black tree is");
        tree.printTree();
    }
}
