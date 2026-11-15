@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    private static final String[] EMPTY_ARRAY = new String[0];
    private final Object lock = new Object();
    private final Object stateLock = new Object();
    private final String[][] users;
    private final boolean[][] states;
    private final Throwable[][] failures;
    private int nextGeneration = 0;

    public UserSpaceProvisioner(int capacity) {
        users = new String[capacity][];
        states = new boolean[capacity][4]; // PENDING, INSTALLING, ACTIVE, FAILED
        failures = new Throwable[capacity][4];
    }

    public String[] register(String user, String[] configuration) {
        synchronized (lock) {
            if (user == null || user.isEmpty() || configuration == null || configuration.length == 0) {
                throw new IllegalArgumentException("Invalid user or configuration");
            }
            int index = findUser(user);
            if (index != -1) {
                throw new IllegalStateException("Duplicate user");
            }
            int generation = nextGeneration++;
            users[index] = configuration.clone();
            states[index][0] = true;
            return EMPTY_ARRAY; // Installation will happen in the background
        }
    }

    public String[] activate(String user) {
        int index = findUser(user);
        if (index == -1) {
            throw new IllegalStateException("Unknown user");
        }
        synchronized (lock) {
            if (!states[index][0]) {
                throw new IllegalStateException("User not registered");
            }
            while (states[index][1] || states[index][3]) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        install(index, user);
        return states[index][2] ? users[index].clone() : EMPTY_ARRAY;
    }

    public String[] reinstall(String user, String[] configuration) {
        int index = findUser(user);
        if (index == -1) {
            throw new IllegalStateException("Unknown user");
        }
        synchronized (lock) {
            if (!states[index][0]) {
                throw new IllegalStateException("User not registered");
            }
        }
        synchronized (stateLock) {
            states[index][1] = true;
        }
        install(index, user);
        return states[index][2] ? users[index].clone() : EMPTY_ARRAY;
    }

    public String[] verify(String user) {
        int index = findUser(user);
        if (index == -1) {
            throw new IllegalStateException("Unknown user");
        }
        synchronized (lock) {
            if (!states[index][0]) {
                throw new IllegalStateException("User not registered");
            }
        }
        return states[index][2] ? users[index].clone() : EMPTY_ARRAY;
    }

    public void snapshotMetrics(int[] out) {
        synchronized (lock) {
            for (int i = 0; i < states.length; i++) {
                int pending = 0, installing = 0, active = 0, failed = 0;
                if (states[i][0]) {
                    pending++;
                }
                if (states[i][1]) {
                    installing++;
                }
                if (states[i][2]) {
                    active++;
                }
                if (states[i][3]) {
                    failed++;
                }
                out[0] += pending;
                out[1] += installing;
                out[2] += active;
                out[3] += failed;
            }
        }
    }

    private int nextAvailableIndex() {
        for (int i = 0; i < users.length; i++) {
            if (!states[i][0]) {
                return i;
            }
        }
        throw new IllegalStateException("No available slots");
    }

    private int findUser(String user) {
        for (int i = 0; i < users.length; i++) {
            if (users[i] != null && users[i][0].equals(user)) {
                return i;
            }
        }
        return -1;
    }

    private void install(int index, String user) {
        try {
            Runnable installer = () -> {
                states[index][1] = true;
                try {
                    // Simulate installation
                    String[] manifest = users[index].clone();
                    states[index][2] = true;
                } catch (Throwable t) {
                    failures[index][1] = t;
                    states[index][3] = true;
                } finally {
                    synchronized (stateLock) {
                        states[index][1] = false;
                        lock.notifyAll();
                    }
                }
            };
            installer.run(); // Simulating synchronous for simplicity
        } catch (Throwable t) {
            synchronized (stateLock) {
                failures[index][1] = t;
                states[index][3] = true;
            }
        }
    }
}
