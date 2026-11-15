@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    private static final int MAX_GENERATION = Integer.MAX_VALUE - 1;
    private final Installer installer;
    private final Object lock = new Object();
    private final User[] users;
    private int nextUserIndex;

    public UserSpaceProvisioner(Installer installer) {
        this.installer = installer;
        this.users = new User[16];
        this.nextUserIndex = 0;
    }

    public void register(String user, String[] configuration) {
        synchronized (lock) {
            for (int i = 0; i < nextUserIndex; i++) {
                if (users[i].name.equals(user)) {
                    throw new IllegalArgumentException("Duplicate user: " + user);
                }
            }

            if (user == null || user.isEmpty() || configuration == null || configuration.length == 0) {
                throw new IllegalArgumentException("Invalid user or configuration");
            }

            ensureCapacity(nextUserIndex + 1);
            users[nextUserIndex++] = new User(user, configuration, Generation.PENDING, null);
        }
    }

    public void activate(String user) throws IllegalStateException {
        User userObj = findUser(user);
        synchronized (userObj.monitor) {
            while (userObj.state != Generation.ACTIVE && userObj.state != Generation.VERIFIED) {
                try {
                    userObj.monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (userObj.state == Generation.ACTIVE) {
                userObj.monitor.notifyAll();
                throw new IllegalStateException("User is already active: " + user);
            }
        }

        synchronized (lock) {
            User newUser = new User(user, null, Generation.INSTALLING, userObj.configuration);
            int newPosition = findAvailableSlot();
            users[newPosition] = newUser;
            userObj.state = Generation.INSTALLING;

            synchronized (userObj.monitor) {
                userObj.monitor.notifyAll();
            }

            try {
                newUser.manifest = installer.install(user, newUser.configuration);
                newUser.state = Generation.ACTIVE;
            } catch (RuntimeException e) {
                newUser.state = Generation.FAILED;
                newUser.exception = e;

                synchronized (userObj.monitor) {
                    userObj.monitor.notifyAll();
                }

                throw e;
            }

            synchronized (userObj.monitor) {
                userObj.monitor.notifyAll();
            }
        }
    }

    public void reinstall(String user, String[] configuration) throws IllegalStateException {
        User userObj = findUser(user);
        synchronized (userObj.monitor) {
            while (userObj.state != Generation.ACTIVE && userObj.state != Generation.VERIFIED) {
                try {
                    userObj.monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (userObj.state == Generation.ACTIVE) {
                userObj.monitor.notifyAll();
                throw new IllegalStateException("Cannot reinstall active user: " + user);
            }
        }

        synchronized (lock) {
            userObj.configuration = configuration;
            int newPosition = findAvailableSlot();
            users[newPosition] = new User(user, null, Generation.INSTALLING, userObj.configuration);
            userObj.state = Generation.INSTALLING;

            synchronized (userObj.monitor) {
                userObj.monitor.notifyAll();
            }

            try {
                newUser.manifest = installer.install(user, newUser.configuration);
                newUser.state = Generation.ACTIVE;
            } catch (RuntimeException e) {
                newUser.state = Generation.FAILED;
                newUser.exception = e;

                synchronized (userObj.monitor) {
                    userObj.monitor.notifyAll();
                }

                throw e;
            }

            synchronized (userObj.monitor) {
                userObj.monitor.notifyAll();
            }
        }
    }

    public void verify(String user) {
        User userObj = findUser(user);
        synchronized (userObj.monitor) {
            while (userObj.state != Generation.ACTIVE && userObj.state != Generation.VERIFIED) {
                try {
                    userObj.monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (userObj.state == Generation.VERIFIED) {
                userObj.monitor.notifyAll();
                return;
            }
        }

        synchronized (lock) {
            User newUser = new User(user, null, Generation.VERIFIED, userObj.manifest);
            int newPosition = findAvailableSlot();
            users[newPosition] = newUser;
            userObj.state = Generation.VERIFIED;

            synchronized (userObj.monitor) {
                userObj.monitor.notifyAll();
            }
        }
    }

    public void snapshotMetrics() {
        int pendingCount = 0, installingCount = 0, activeCount = 0, failedCount = 0;
        synchronized (lock) {
            for (int i = 0; i < nextUserIndex; i++) {
                switch (users[i].state) {
                    case PENDING:
                        pendingCount++;
                        break;
                    case INSTALLING:
                        installingCount++;
                        break;
                    case ACTIVE:
                        activeCount++;
                        break;
                    case FAILED:
                        failedCount++;
                        break;
                }
            }

            System.out.println("Pending: " + pendingCount);
            System.out.println("Installing: " + installingCount);
            System.out.println("Active: " + activeCount);
            System.out.println("Failed: " + failedCount);
        }
    }

    private User findUser(String user) {
        for (int i = 0; i < nextUserIndex; i++) {
            if (users[i].name.equals(user)) {
                return users[i];
            }
        }

        throw new IllegalArgumentException("Unknown user: " + user);
    }

    private int findAvailableSlot() {
        for (int i = 0; i < nextUserIndex; i++) {
            if (users[i] == null) {
                return i;
            }
        }

        ensureCapacity(nextUserIndex + 1);
        return nextUserIndex++;
    }

    private void ensureCapacity(int capacity) {
        if (capacity > users.length) {
            User[] newUsers = new User[users.length * 2];
            System.arraycopy(users, 0, newUsers, 0, users.length);
            users = newUsers;
        }
    }

    private static class User {
        final String name;
        final Object[] monitor = new Object();
        final String[] configuration;
        Generation state;
        String manifest;
        RuntimeException exception;

        User(String name, String[] configuration, Generation state, String[] initialManifest) {
            this.name = name;
            this.configuration = configuration != null ? configuration.clone() : null;
            this.state = state;
            this.manifest = initialManifest != null ? initialManifest.clone() : null;
        }
    }

    private enum Generation {
        PENDING, INSTALLING, ACTIVE, FAILED
    }

    public interface Installer {
        String[] install(String user, String[] configuration);
    }
}
