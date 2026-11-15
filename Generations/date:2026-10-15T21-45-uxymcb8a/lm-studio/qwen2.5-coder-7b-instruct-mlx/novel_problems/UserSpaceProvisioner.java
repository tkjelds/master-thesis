import java.util.Arrays;

@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    private static final int PENDING = 0;
    private static final int INSTALLING = 1;
    private static final int ACTIVE = 2;
    private static final int FAILED = 3;

    private static class User {
        final String user;
        final String[] configuration;
        int state = PENDING;
        volatile int generation = 0;
        private Object waitMonitor = new Object();
        String manifest;

        User(String user, String[] configuration) {
            this.user = user;
            this.configuration = Arrays.copyOf(configuration, configuration.length);
        }
    }

    private User[] users;
    private volatile int nextUserIndex = 0;

    public void register(String user, String[] configuration) {
        synchronized (this) {
            if (user == null || user.isEmpty() || configuration == null || configuration.length == 0) {
                throw new IllegalArgumentException("Invalid user or configuration");
            }
            for (User u : users) {
                if (u != null && u.user.equals(user)) {
                    throw new IllegalStateException("User already registered");
                }
            }
            users[nextUserIndex] = new User(user, configuration);
            nextUserIndex++;
        }
    }

    public void activate(String user) {
        User u = findUser(user);
        if (u.state != PENDING) {
            throw new IllegalStateException("Cannot activate a non-PENDING user");
        }
        synchronized (u.waitMonitor) {
            if (u.state != PENDING) {
                return;
            }
            u.state = INSTALLING;
        }

        try {
            String[] manifest = null;
            synchronized (this) {
                manifest = u.configuration.clone();
            }
            install(u.user, manifest);

            synchronized (u.waitMonitor) {
                u.state = ACTIVE;
                u.manifest = manifest;
                u.waitMonitor.notifyAll();
            }
        } catch (Exception e) {
            synchronized (u.waitMonitor) {
                u.state = FAILED;
                u.manifest = null;
                u.waitMonitor.notifyAll();
            }
            throw e;
        }
    }

    public void reinstall(String user, String[] configuration) {
        User u = findUser(user);
        synchronized (this) {
            if (configuration == null || configuration.length == 0) {
                throw new IllegalArgumentException("Invalid configuration");
            }
            u.configuration = Arrays.copyOf(configuration, configuration.length);
        }

        synchronized (u.waitMonitor) {
            u.state = INSTALLING;
        }

        try {
            String[] manifest = null;
            synchronized (this) {
                manifest = u.configuration.clone();
            }
            install(u.user, manifest);

            synchronized (u.waitMonitor) {
                u.state = ACTIVE;
                u.manifest = manifest;
                u.waitMonitor.notifyAll();
            }
        } catch (Exception e) {
            synchronized (u.waitMonitor) {
                u.state = FAILED;
                u.manifest = null;
                u.waitMonitor.notifyAll();
            }
            throw e;
        }
    }

    public void verify(String user) {
        User u = findUser(user);
        synchronized (u.waitMonitor) {
            while (u.state == INSTALLING) {
                try {
                    u.waitMonitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Verification interrupted");
                }
            }
        }

        if (u.state == ACTIVE) {
            return;
        } else if (u.state == FAILED) {
            throw new IllegalStateException("User verification failed");
        }
    }

    public String[] snapshotMetrics() {
        int pending = 0;
        int installing = 0;
        int active = 0;
        int failed = 0;

        for (User u : users) {
            if (u != null) {
                switch (u.state) {
                    case PENDING:
                        pending++;
                        break;
                    case INSTALLING:
                        installing++;
                        break;
                    case ACTIVE:
                        active++;
                        break;
                    case FAILED:
                        failed++;
                        break;
                }
            }
        }

        return new String[] {
            "PENDING=" + pending,
            "INSTALLING=" + installing,
            "ACTIVE=" + active,
            "FAILED=" + failed
        };
    }

    private User findUser(String user) {
        for (User u : users) {
            if (u != null && u.user.equals(user)) {
                return u;
            }
        }
        throw new IllegalStateException("Unknown user");
    }

    private void install(String user, String[] manifest) throws Exception {
        // Dummy installation logic
        Thread.sleep(1000);
    }
}
