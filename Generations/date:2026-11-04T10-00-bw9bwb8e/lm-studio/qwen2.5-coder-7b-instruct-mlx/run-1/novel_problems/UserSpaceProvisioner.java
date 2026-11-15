@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    private final Installer installer;
    private final Object lock = new Object();
    private final UserMap users = new UserMap();

    public UserSpaceProvisioner(Installer installer) {
        this.installer = installer;
    }

    public void register(String user, String[] configuration) throws Exception {
        synchronized (lock) {
            if (user == null || user.isEmpty() || configuration == null || configuration.length == 0) {
                throw new IllegalArgumentException("Invalid user or configuration");
            }
            if (users.containsKey(user)) {
                throw new IllegalStateException("User already registered");
            }

            User newUser = new User(user, configuration);
            users.put(newUser);
        }
    }

    public String activate(String user) throws Exception {
        synchronized (lock) {
            User existingUser = users.get(user);
            if (existingUser == null || !existingUser.isPending()) {
                throw new IllegalArgumentException("Unknown or non-pending user");
            }

            String[] manifest;
            Exception exception = null;

            synchronized (existingUser) {
                if (!existingUser.isPending()) {
                    throw new IllegalStateException("User has been superseded");
                }

                try {
                    manifest = installer.install(user, existingUser.configuration);
                } catch (Exception e) {
                    exception = e;
                }
            }

            if (exception != null) {
                synchronized (lock) {
                    existingUser.markFailed(exception);
                }
            } else if (!existingUser.tryActivate(manifest)) {
                synchronized (lock) {
                    existingUser.markFailed(new IllegalStateException("Superseded"));
                }
            }

            return existingUser.getManifest();
        }
    }

    public void reinstall(String user, String[] configuration) throws Exception {
        synchronized (lock) {
            User existingUser = users.get(user);
            if (existingUser == null || existingUser.isPending()) {
                throw new IllegalArgumentException("Unknown or pending user");
            }

            int nextGeneration = existingUser.nextGeneration();
            users.put(new User(user, configuration, nextGeneration));
        }
    }

    public String verify(String user) {
        synchronized (lock) {
            User existingUser = users.get(user);
            if (existingUser == null || existingUser.isPending()) {
                throw new IllegalArgumentException("Unknown or non-pending user");
            }

            return existingUser.getManifest();
        }
    }

    public Metrics snapshotMetrics() {
        int totalUsers = 0;
        int pendingCount = 0;
        int installingCount = 0;
        int activeCount = 0;
        int failedCount = 0;

        synchronized (lock) {
            for (User user : users.values()) {
                totalUsers++;
                switch (user.state) {
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
        }

        return new Metrics(totalUsers, pendingCount, installingCount, activeCount, failedCount);
    }

    private static class User {
        final String user;
        String[] configuration;
        int generation;
        State state;
        String[] manifest;

        User(String user, String[] configuration) {
            this.user = user;
            this.configuration = configuration.clone();
            this.generation = 0;
            this.state = State.PENDING;
        }

        User(String user, String[] configuration, int generation) {
            this.user = user;
            this.configuration = configuration.clone();
            this.generation = generation;
            this.state = State.PENDING;
        }

        boolean isPending() {
            return state == State.PENDING;
        }

        boolean isInstalling() {
            return state == State.INSTALLING;
        }

        boolean isActive() {
            return state == State.ACTIVE;
        }

        boolean isFailed() {
            return state == State.FAILED;
        }

        void markFailed(Exception exception) {
            this.state = State.FAILED;
            this.manifest = null;
            notifyAll();
        }

        boolean tryActivate(String[] newManifest) {
            synchronized (this) {
                if (state == State.INSTALLING && generation == this.generation) {
                    state = State.ACTIVE;
                    manifest = newManifest.clone();
                    notifyAll();
                    return true;
                }
            }
            return false;
        }

        String getManifest() {
            synchronized (this) {
                while (state == State.INSTALLING || state == State.FAILED) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Thread interrupted");
                    }
                }
            }

            if (state == State.ACTIVE) {
                return manifest.clone();
            } else {
                throw new IllegalStateException("User is in an invalid state");
            }
        }

        int nextGeneration() {
            synchronized (this) {
                generation++;
                return generation;
            }
        }

        enum State {
            PENDING,
            INSTALLING,
            ACTIVE,
            FAILED
        }
    }

    private static class UserMap {
        private final Map<String, User> map = new HashMap<>();

        private void put(User user) {
            synchronized (map) {
                map.put(user.user, user);
            }
        }

        private User get(String user) {
            synchronized (map) {
                return map.get(user);
            }
        }

        private Collection<User> values() {
            synchronized (map) {
                return new ArrayList<>(map.values());
            }
        }
    }

    public static class Metrics {
        final int totalUsers;
        final int pendingCount;
        final int installingCount;
        final int activeCount;
        final int failedCount;

        Metrics(int totalUsers, int pendingCount, int installingCount, int activeCount, int failedCount) {
            this.totalUsers = totalUsers;
            this.pendingCount = pendingCount;
            this.installingCount = installingCount;
            this.activeCount = activeCount;
            this.failedCount = failedCount;
        }
    }

    public interface Installer {
        String[] install(String user, String[] configuration) throws Exception;
    }
}
