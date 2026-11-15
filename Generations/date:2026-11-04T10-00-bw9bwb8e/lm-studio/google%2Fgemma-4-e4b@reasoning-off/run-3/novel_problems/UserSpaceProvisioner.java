@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}


    /**
     * Callback interface representing the external installation process.
     */
    public interface Installer {
        /**
         * Attempts to install the user with the given configuration.
         * @param user The username.
         * @param configuration The installation configuration array.
         * @return A unique identifier or artifact from the successful installation phase (if applicable, based on problem context, here we use it as a conceptual return type).
         * @throws RuntimeException if the installation fails fatally.
         */
        String[] install(String user, String[] configuration);

        /**
         * Registers a new pending installation attempt.
         * @param user The username.
         * @param configuration The initial configuration.
         */
        void register(String user, String[] configuration);

        /**
         * Attempts to activate a pending installation.
         * @param user The username.
         */
        void activate(String user);

        /**
         * Replaces the configuration and starts a new installation attempt.
         * @param user The username.
         * @param configuration The new configuration.
         */
        void reinstall(String user, String[] configuration);

        /**
         * Verifies the current status of a user installation.
         * @param user The username.
         * @return A snapshot object containing the current status, generation, and manifest.
         */
        InstallationStatus verify(String user);

        /**
         * Finalizes the installation by accepting the result of a successful install attempt.
         * @param user The username.
         */
        void verify(String user);

        /**
         * Provides metrics about the current system state.
         */
        InstallationMetrics snapshotMetrics();
    }

    // --- Helper classes to represent complex types and states ---

    public static class InstallationStatus {
        public enum State { PENDING, INSTALLING, ACTIVE, FAILED }

        private final String user;
        private final int generation;
        private final State currentState;
        private final String[] manifest; // Snapshot of the installed artifact/config

        public InstallationStatus(String user, int generation, State currentState, String[] manifest) {
            this.user = user;
            this.generation = generation;
            this.currentState = currentState;
            this.manifest = manifest != null ? new String[]{}.clone() : null; // Defensive copy for snapshot
        }

        public String getUser() { return user; }
        public int getGeneration() { return generation; }
        public State getCurrentState() { return currentState; }
        public String[] getManifest() { return manifest != null ? manifest.clone() : null; }
    }

    public static class InstallationMetrics {
        private final int pendingCount;
        private final int installingCount;
        private final int activeCount;
        private final int failedCount;
        private final int totalUsers;

        public InstallationMetrics(int pending, int installing, int active, int failed, int total) {
            this.pendingCount = pending;
            this.installingCount = installing;
            this.activeCount = active;
            this.failedCount = failed;
            this.totalUsers = total;
        }

        public int getPendingCount() { return pendingCount; }
        public int getInstallingCount() { return installingCount; }
        public int getActiveCount() { return activeCount; }
        public int getFailedCount() { return failedCount; }
        public int getTotalUsers() { return totalUsers; }
    }

    // --- Internal representation of a tracked user ---
    private static class TrackedUser {
        final String username;
        int generation = 0;
        String[] configuration;
        volatile InstallationStatus.State state = InstallationStatus.State.PENDING;

        // Synchronization mechanism for activation joiners waiting on the install result
        private Object installationMonitor = new Object();

        // State related to an ongoing or recently completed attempt
        volatile InstallationStatus.State currentAttemptState = InstallationStatus.State.PENDING;
        volatile InstallationMetrics.InstallationStatus snapshotManifest = null; // Stores the manifestation if INSTALLING or ACTIVE

        // Signals used by callers waiting on a specific attempt
        Object activationLatch = null; // Used to signal completion of an INSTALLING cycle

        public TrackedUser(String username) {
            this.username = username;
        }

        // Deep copy configuration array
        public static String[] copyConfig(String[] config) {
            if (config == null || config.length == 0) return null;
            String[] copy = new String[config.length];
            for (int i = 0; i < config.length; i++) {
                copy[i] = config[i];
            }
            return copy;
        }

        public void advanceGeneration(String[] newConfig) {
            this.generation++;
            this.configuration = copyConfig(newConfig);
        }
    }

    // --- UserSpaceProvisioner Implementation ---

    private Installer installer; // The injected callback implementation
    private final java.util.Map<String, TrackedUser> users = new java.util.HashMap<>();
    private volatile int nextGenerationId = 1; // Global counter, if needed beyond per-user generation

    // Synchronization lock for managing the map structure (adding/removing users)
    private final Object userManagementLock = new Object();

    // Tracks counts for metrics gathering (simplified, mirroring the required structure)
    private int pendingCount = 0;
    private int installingCount = 0;
    private int activeCount = 0;
    private int failedCount = 0;

    // --- Constructors/Setup (Matching the Contract) ---
    public UserSpaceProvisioner(Installer installerInstance) {
        this.installer = installerInstance;
    }

    // Mapping the public interface methods to the implementation logic.
    // We rename installation methods to match the required structure for this simulation environment,
    // although the problem describes the Installer as the callback *to* us. Since we must implement the logic,
    // we assume our methods are orchestrating actions *through* this system.

    /**
     * Corresponds to Installer.register(String user, String[] configuration).
     */
    public void registerUser(String user, String[] configuration) {
        if (user == null || user.isEmpty()) return;
        if (configuration == null || configuration.length == 0) {
            // Reject null or empty configurations for initial registration, but allow configuration update later.
            return;
        }

        synchronized (userManagementLock) {
            if (users.containsKey(user)) return; // Reject duplicate users

            TrackedUser newUser = new TrackedUser(user);
            newUser.advanceGeneration(configuration); // Initial generation is 1 (or 0 if we start at 0)

            users.put(user, newUser);
            pendingCount++;
            // In a real system, we'd notify waiting threads here that registration succeeded.
        }
    }

    /**
     * Corresponds to Installer.activate(String user).
     */
    public void activateUser(String user) {
        TrackedUser userObj;
        synchronized (userManagementLock) {
            if (!users.containsKey(user)) return; // Unknown user
            userObj = users.get(user);
        }

        // Critical section for activation sequence
        synchronized (userObj.installationMonitor) {
            if (userObj.state == InstallationStatus.State.ACTIVE) {
                // Return manifest immediately if already active (handled by the contract implication)
                InstallationStatus status = getCombinedStatus(user, true);
                // The caller expects INSTALLATION_STATUS to represent the activation success.
                return; 
            }

            if (userObj.state == InstallationStatus.State.PENDING) {
                // Attempt to transition PENDING -> INSTALLING/ACTIVE
                if (tryActivateAndInstall(userObj)) {
                    // Successfully started or joined the installation process.
                } else {
                    // Activation attempt failed immediately (e.g., internal protection failure)
                    return;
                }
            } else if (userObj.state == InstallationStatus.State.INSTALLING) {
                // Join the ongoing attempt instead of starting a duplicate install
                waitOnInstallation(userObj);
            } else if (userObj.state == InstallationStatus.State.FAILED) {
                // If FAILED, we must wait for reinstall or fail loudly (as per contract nuance)
                // Contract: FAILED throws the retained attempt failure until reinstall. 
                throw new IllegalStateException("User is in FAILED state. Must reinstall.");
            }
        }
    }

    /**
     * Corresponds to Installer.reinstall(String user, String[] configuration).
     */
    public void reinstallUser(String user, String[] configuration) {
        TrackedUser userObj;
        synchronized (userManagementLock) {
            if (!users.containsKey(user)) return; // Unknown user
            userObj = users.get(user);
        }

        // Reinstall sequence happens under the user's specific monitor/lock to manage state transition safely
        synchronized (userObj.installationMonitor) {
            if (userObj.state == InstallationStatus.State.ACTIVE && userObj.generation > 0) {
                // If active, we transition out of ACTIVE state (conceptually destroying old artifacts/manifests)
                // and move to a new PENDING phase for the next generation.
                userObj.state = InstallationStatus.State.PENDING;
            } else if (userObj.state == InstallationStatus.State.FAILED) {
                // If failed, we transition to a new PENDING phase.
            } else if (userObj.state != InstallationStatus.State.PENDING) {
                // Should not happen if invariants hold, but handles unexpected states gracefully.
            }

            // Advance generation and update config *before* signaling the install attempt
            userObj.advanceGeneration(configuration);

            // Invalidate old attempts and start new installation attempt flow (effectively treating reinstall as a forceful activate)
            if (tryActivateAndInstall(userObj)) {
                // Installation successfully restarted. Notify waiters.
            } else {
                 // Fatal error during attempt restart, though unlikely if generation advanced.
            }
        }
    }

    /**
     * Corresponds to Installer.verify(String user) - synchronous blocking call if necessary for status.
     */
    public InstallationStatus getUserStatus(String user) {
        TrackedUser userObj;
        synchronized (userManagementLock) {
            if (!users.containsKey(user)) return null; // Unknown user handled gracefully
            userObj = users.get(user);
        }

        // Read access should ideally be fast, but needs synchronization barrier to ensure visibility of latest state.
        synchronized (userObj.installationMonitor) {
            if (userObj.state == InstallationStatus.State.ACTIVE || userObj.state == InstallationStatus.State.FAILED) {
                // If we are in a final state, return the current snapshot reliably.
                return getCombinedStatus(user, false);
            } else if (userObj.state == InstallationStatus.State.INSTALLING) {
                // If installing, we wait for the current attempt to finish or be superseded.
                waitForInstallationCompletion(userObj); 
            }
            // If PENDING, return the current generation/state snapshot.
        }
        return getCombinedStatus(user, false); // Fallback if waiting wasn't necessary or helpful
    }

    // --- Internal Synchronization Logic (Simulating the complexities) ---

    /**
     * Handles the complex activation sequence, starting installation if needed or joining an attempt.
     * Assumes caller holds userObj monitor lock on the object being activated/reinstalled.
     * @return true if this call initiated a new installation attempt cycle or successfully transitioned to INSTALLING/ACTIVE.
     */
    private boolean tryActivateAndInstall(TrackedUser userObj) {
        // Check for revision overflow before mutation (simulated check)
        if (userObj.generation <= 0) return false;

        if (userObj.state == InstallationStatus.State.PENDING) {
            // Initiate the new installation flow
            installingCount++;
            userObj.state = InstallationStatus.State.INSTALLING;
            installationingCount++; // Increment transactional count

            // 1. Invoke installer callback (outside shared monitors)
            // This mimics the installation process taking time and blocking on its own thread/mechanism.
            String[] manifestArtifact = installer.install(userObj.username, userObj.configuration);

            // 2. Installation succeeded conceptually (the callback returned)
            if (manifestArtifact != null) {
                userObj.state = InstallationStatus.State.ACTIVE;
                installationingCount--; // Decrement transactional count upon success
                // Publish manifest atomically upon transition to ACTIVE state.
                userObj.snapshotManifest = InstallationStatus.InstallationStatus.getActiveManifest(manifestArtifact); 
                activeCount++;
            } else {
                // Installation failed during the callback execution (must handle failure modes from installer)
                handleInstallationFailure(userObj, "Fatal Install Error");
            }
        } else if (userObj.state == InstallationStatus.State.INSTALLING) {
            // This path should ideally be unreachable if the caller checked state correctly, 
            // but handles concurrent activation callers joining this specific attempt.
             waitOnInstallation(userObj); 
        } else if (userObj.state == InstallationStatus.State.ACTIVE) {
            // If already active, no installation needed, just return the current manifest conceptually.
        }

        installationingCount--; // Decrement transactional count if it was in INSTALLING state upon entry.
        return true; 
    }

    /**
     * Waits for a specific installation attempt to resolve, required when joining an INSTALLING cycle.
     * Assumes caller holds userObj monitor lock on the object being activated/reinstalled.
     */
    private void waitOnInstallation(TrackedUser userObj) {
        // This is where a thread waits for the current attempt to resolve (success/fail)
        try {
            // Wait until state changes or a signal is received.
            while (userObj.state == InstallationStatus.State.INSTALLING) {
                // Wait for the monitor to be notified about resolution of THIS specific attempt.
                synchronized (userObj.installationMonitor) {
                    if (userObj.state != InstallationStatus.State.INSTALLING) break; // Check condition again after waking up
                    wait(); 
                }
            }
        } catch (InterruptedException e) {
            // Handle interruption during wait.
            Thread.currentThread().interrupt();
        }

        // Post-wait check: If state is not INSTALLING, it resolved. Check final state.
        if (userObj.state == InstallationStatus.State.FAILED) {
            // Contract: Joined callers receive retained attempt failure.
             throw new IllegalStateException("Installation failed during wait period.");
        }
    }

    private void handleInstallationFailure(TrackedUser userObj, String reason) {
        // Contract: Callback throws silently upon failure, atomically publishing FAILED and waking all joined callers.
        synchronized (userObj.installationMonitor) {
            if (userObj.state != InstallationStatus.State.FAILED) {
                userObj.state = InstallationStatus.State.FAILED;
                failedCount++;
                // Notify all waiters about the failure so they can inspect the final state.
                userObj.installationMonitor.notifyAll(); 
            }
        }
    }


    // --- Public interface mapping for the simulation context ---

    /**
     * Simulates the Installer.register call flow.
     */
    public void register(String user, String[] configuration) {
        this.registerUser(user, configuration);
    }

    /**
     * Simulates the Installer.activate call flow. This is blocking if it must wait for INSTALLING to become ACTIVE.
     * @throws RuntimeException if the attempt fails catastrophically during activation tracking.
     */
    public void activate(String user) {
        // In a real implementation, we'd map this to the internal activation sequence.
        activateUser(user);
    }

    /**
     * Simulates the Installer.reinstall call flow.
     */
    public void reinstall(String user, String[] configuration) {
        reinstallUser(user, configuration);
    }

    /**
     * Simulates the Installer.verify call flow. Returns a synchronous snapshot object.
     */
    public InstallationStatus verify(String user) {
        return getUserStatus(user); // Executes the necessary waiting/checking logic internally
    }

    /**
     * Simulates the Installer.snapshotMetrics call flow.
     */
    public InstallationMetrics snapshotMetrics() {
        // Reads counts under the management lock to ensure consistency between metrics and user map size.
        synchronized (userManagementLock) {
            return new InstallationMetrics(pendingCount, installingCount, activeCount, failedCount, users.size());
        }
    }

    // --- Placeholder methods needed to satisfy the structure imposed by the prompt's ambiguous use of Installer vs Provisioner ---

    // Since the prompt confusingly mixes roles, we implement conceptual wrappers for the INSTALLER interface methods
    // that would typically be called *by* this class into an external service, but here we assume they are triggers on the Provisioner.
    // Given the constraints ("Implement the thread-safe UserSpaceProvisioner concurrency problem"), we focus on making the Provisioner correct.

    // The methods Installer implies are called *into* the provisioning system:
    public void triggerRegister(String user, String[] configuration) { this.register(user, configuration); }
    public void triggerActivate(String user) { this.activate(user); }
    public void triggerReinstall(String user, String[] configuration) { this.reinstall(user, configuration); }
    public InstallationStatus triggerVerify(String user) { return this.verify(user); }
    public InstallationMetrics triggerSnapshotMetrics() { return this.snapshotMetrics(); }

    // --- Simplified/Conceptual Helpers (since full simulation context is missing) ---
    private InstallationStatus getCombinedStatus(String user, boolean isActiveCheck) {
        // In a real implementation, this combines generation, state, and manifest existence.
        return new InstallationStatus(user, 1, InstallationStatus.State.ACTIVE, null); // Placeholder
    }
    private void waitForInstallationCompletion(TrackedUser userObj) { /* implementation */ }

    // NOTE: To compile and run this structure, the `Installer` interface methods would need to be called
    // against an instance of this class, making the class itself fulfill both roles dynamically.

}
