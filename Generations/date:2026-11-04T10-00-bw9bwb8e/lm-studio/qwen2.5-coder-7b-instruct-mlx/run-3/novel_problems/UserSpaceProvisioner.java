@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Installer installer;
    private final UserState[] users;

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    private static class UserState {
        volatile int state;
        volatile String[] configuration;
        volatile String[] manifest;
        volatile int generation;
        volatile Thread installingThread;

        UserState(int state, String[] configuration, String[] manifest, int generation) {
            this.state = state;
            this.configuration = configuration.clone();
            this.manifest = manifest.clone();
            this.generation = generation;
        }

        boolean isPending() {
            return state == 0;
        }

        boolean isInstalling() {
            return state == 1;
        }

        boolean isActive() {
            return state == 2;
        }

        boolean isFailed() {
            return state == 3;
        }
    }

    public UserSpaceProvisioner(int maxUsers, Installer installer) {
        this.users = new UserState[maxUsers];
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        synchronized (lock) {
            int index = findUserIndex(user);
            if (index != -1 || user == null || configuration == null || configuration.length == 0) {
                throw new IllegalArgumentException("Invalid user or configuration");
            }
            int newIndex = findFirstAvailableSlot();
            if (newIndex == -1) {
                throw new IllegalStateException("No available slots");
            }
            users[newIndex] = new UserState(0, configuration, null, 1);
        }
    }

    public void activate(String user) throws InterruptedException {
        int index = findUserIndex(user);
        if (index == -1 || !users[index].isPending()) {
            throw new IllegalArgumentException("Unknown or non-PENDING user");
        }
        synchronized (lock) {
            if (!users[index].isPending()) {
                return;
            }
            users[index].installingThread = Thread.currentThread();
        }
        String[] manifest = null;
        try {
            synchronized (users[index]) {
                users[index].state = 1;
                Thread.sleep(100); // Simulate slow install
                manifest = installer.install(user, users[index].configuration);
            }
        } catch (RuntimeException e) {
            synchronized (users[index]) {
                users[index].state = 3;
                users[index].installingThread.notifyAll();
            }
            throw e;
        } finally {
            synchronized (lock) {
                users[index].installingThread = null;
            }
        }
        if (manifest == null || manifest.length == 0) {
            synchronized (users[index]) {
                users[index].state = 3;
                users[index].installingThread.notifyAll();
            }
        } else {
            synchronized (users[index]) {
                users[index].state = 2;
                users[index].manifest = manifest.clone();
            }
        }
    }

    public void reinstall(String user, String[] configuration) throws IllegalArgumentException {
        int index = findUserIndex(user);
        if (index == -1 || !users[index].isPending()) {
            throw new IllegalArgumentException("Unknown or non-PENDING user");
        }
        synchronized (lock) {
            if (!users[index].isPending()) {
                return;
            }
            int generation = users[index].generation + 1;
            if (generation < 0) {
                throw new IllegalStateException("Revision overflow");
            }
            users[index].configuration = configuration.clone();
            users[index].generation = generation;
        }
    }

    public String[] verify(String user) {
        int index = findUserIndex(user);
        if (index == -1 || !users[index].isActive()) {
            return null;
        }
        synchronized (lock) {
            if (!users[index].isActive()) {
                return null;
            }
        }
        return users[index].manifest.clone();
    }

    public void snapshotMetrics() {
        int pending = 0, installing = 0, active = 0, failed = 0;
        for (UserState user : users) {
            if (user != null) {
                switch (user.state) {
                    case 0:
                        pending++;
                        break;
                    case 1:
                        installing++;
                        break;
                    case 2:
                        active++;
                        break;
                    case 3:
                        failed++;
                        break;
                }
            }
        }
        System.out.println("Pending: " + pending);
        System.out.println("Installing: " + installing);
        System.out.println("Active: " + active);
        System.out.println("Failed: " + failed);
    }

    private int findUserIndex(String user) {
        for (int i = 0; i < users.length; i++) {
            if (users[i] != null && user.equals(users[i].configuration[0])) {
                return i;
            }
        }
        return -1;
    }

    private int findFirstAvailableSlot() {
        for (int i = 0; i < users.length; i++) {
            if (users[i] == null) {
                return i;
            }
        }
        return -1;
    }
}
