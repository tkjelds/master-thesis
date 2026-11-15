RPGCharacter {

    // --- Internal State Variables ---
    private final String name;
    private volatile long experience; // Stored as current XP, always < 100 if tracking modulo behavior
    private volatile int level;
    private volatile int currentHealth;
    private final int maxHealth;
    private final int initialAttack;
    private final int initialDefense;

    // Unique ID for deadlock avoidance (used in comparison)
    private final long characterId;

    // Synchronization object for all state mutations and shared operations
    private final Object monitor = new Object();

    // --- Constructors and IDs ---

    private static long nextId = 0;

    /**
     * Private constructor used internally to establish invariants.
     */
    private RPGCharacter(String name, int health, int attack, int defense) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name cannot be null or empty.");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("Health must be between 1 and 100.");
        }
        if (attack < 0 || defense < 0) {
            throw new IllegalArgumentException("Attack and Defense must be non-negative.");
        }

        this.name = name;
        this.maxHealth = health;
        this.currentHealth = health;

        // Attack/Defense are effectively the initial values + bonuses from level 1
        this.initialAttack = attack;
        this.initialDefense = defense;

        // Starting state: Level 1, Exp 0
        this.level = 1;
        this.experience = 0;

        // Assign unique ID safely
        this.characterId = nextId++;
    }

    /**
     * Public constructor initializes the character. The initial state is level 1, exp 0.
     * @param name Nonempty name.
     * @param health Initial health (1-100).
     * @param attack Initial attack power contribution.
     * @param defense Initial defense contribution.
     */
    public RPGCharacter(String name, int health, int attack, int defense) {
        // The constructor ensures invariants upon object creation.
        this(name, health, attack, defense);
    }

    // --- Core Attributes and Status Queries (Snapshotting relies on these being correct) ---

    public String getName() {
        return this.name;
    }

    /**
     * Gets a coherent, thread-safe snapshot of the character's current state.
     * This operation is fast and does not lock for extended periods, only needing to read volatiles/stable values.
     * @return A detached snapshot object.
     */
    public Snapshot snapshot() {
        // Reading volatiles is safe, but locking ensures the most consistent view of interacting values
        // if complex derivation depended on sequence. For this requirement, reading the current state variables suffices.
        synchronized (monitor) {
            return new Snapshot(
                this.name,
                this.characterId,
                this.currentHealth,
                calculateAttackPower(),
                calculateDefenseValue(),
                this.level,
                this.experience,
                this.currentHealth > 0
            );
        }
    }

    private int calculateAttackPower() {
        // Base Attack + (Level - 1) * 2
        return initialAttack + (this.level - 1) * 2;
    }

    private int calculateDefenseValue() {
        // Base Defense + (Level - 1) * 1
        return initialDefense + (this.level - 1);
    }

    // --- Encounters and Actions ---

    /**
     * Attempts an attack encounter against another character. This is an atomic two-character operation.
     * @param target The target RPGCharacter.
     * @return An EncounterResult detailing the outcome.
     */
    public EncounterResult attack(RPGCharacter target) {
        if (this == target) {
            throw new IllegalArgumentException("Cannot attack self.");
        }
        if (target == null) {
            throw new IllegalArgumentException("Target cannot be null.");
        }

        // Deadlock avoidance protocol: Acquire locks in ascending order of unique IDs.
        RPGCharacter firstLocker = (this.characterId < target.characterId) ? this : target;
        RPGCharacter secondLocker = (this == firstLocker) ? target : this;

        synchronized (firstLocker.monitor) {
            synchronized (secondLocker.monitor) {

                // --- Phase 1: Pre-Encounter Checks ---
                if (this.currentHealth <= 0 || target.currentHealth <= 0) {
                    // One or both are already dead upon arrival at the monitor. No interaction needed.
                    return new EncounterResult(this, target, null, null); // Return detached no-op
                }

                // --- Phase 2: Compute Damage ---
                int attackerPower = this.calculateAttackPower();
                int targetDefense = target.calculateDefenseValue();

                // Damage computation: max(0, attackerPower - targetDefense)
                int damageDealt = Math.max(0, attackerPower - targetDefense);

                // --- Phase 3: Apply Damage and Check Death ---
                int oldTargetHealth = target.currentHealth;
                int newTargetHealth = Math.max(0, target.currentHealth - damageDealt);
                boolean targetDies = (oldTargetHealth > 0 && newTargetHealth == 0);

                // Apply damage to the target (since we hold both locks, mutation is safe)
                target.currentHealth = newTargetHealth;

                // --- Phase 4: Check for Kill Reward and Mutate Self/Target ---
                long experienceReward = 0;
                if (targetDies) {
                    // Attacker gains exactly 10 XP for the kill. This must be rewarded simultaneously.
                    experienceReward = 10;
                }

                // Apply XP only if the current character is alive (which they must be to initiate attack)
                if (experienceReward > 0) {
                    this.gainExperienceInternal(experienceReward);
                }

                // --- Phase 5: Final Result Construction ---
                Snapshot selfPost = this.snapshot(); // Snapshotting while holding locks ensures immediate consistency for the result object
                Snapshot targetPost = target.snapshot();

                // Return result object containing detached snapshots
                return new EncounterResult(this, target, damageDealt, {selfPost, targetPost});
            }
        } // End second monitor lock
    }

    /**
     * Restores health atomically.
     * @return The resulting current health after healing, capped at maxHealth.
     */
    public int heal() {
        synchronized (monitor) {
            if (this.currentHealth <= 0) {
                // Dead character cannot be resurrected or healed past zero.
                return 0;
            }
            // Atomically restore up to 10 health, capped at maxHealth.
            int increase = Math.min(10, this.maxHealth - this.currentHealth);
            this.currentHealth += increase;
            return this.currentHealth;
        }
    }

    /**
     * Gains experience points for a living character. If the character is dead, an exception is thrown.
     * This handles both non-lethal gains and the small rewards from a kill encounter.
     * @param amount The non-negative XP to gain.
     * @throws IllegalStateException if the character is dead and tries to gain experience.
     */
    public void gainExperience(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("XP gain must be non-negative.");
        }

        synchronized (monitor) {
            if (this.currentHealth <= 0) {
                throw new IllegalStateException("Cannot gain experience when deceased.");
            }
            // Use the internal method to handle XP rollover and level checks atomically.
            gainExperienceInternal(amount);
        }
    }

    /**
     * Internal method to apply experience while holding the monitor lock. Handles leveling mechanics.
     */
    private void gainExperienceInternal(long amount) {
        this.experience += amount;

        // Leveling mechanic: Every 100 accumulated points increases level by one and consumes 100.
        // We must check for rollover sequentially until no more full levels are gained.
        while (this.experience >= 100) {
            // Crucial check: Ensure we don't overrun integer capacity if this were a real-world scenario,
            // but given the constraints (long XP accumulation, int level), we proceed assuming standard integer limits apply to Level.
            if (this.level == Integer.MAX_VALUE) {
                // Prevent overflow if level advancement itself saturated the int type, though unlikely with constraints.
                break;
            }

            this.experience -= 100; // Consume 100 points
            this.level += 1;       // Advance level (adds fixed stats)
        }
    }

    // --- Read-Only Accessors for Verification/Debugging (Encounters use these internally) ---
    public int getCurrentHealth() {
        synchronized (monitor) {
            return this.currentHealth;
        }
    }

    public boolean isAlive() {
        return this.currentHealth > 0;
    }

    // =============================================================
    // --- Return Types and Encapsulation Classes ---
    // These classes are defined internally to fulfill the contract requirements cleanly.
    // They MUST be detached once created, meaning they should hold copies of the data, not references
    // to the mutable RPGCharacter objects unless explicitly intended.

    /** Represents a fixed, unchanging view of an RPGCharacter at a point in time. */
    public static class Snapshot {
        public final String name;
        public final long characterId;
        public final int health;
        public final int attackPower;
        public final int defenseValue;
        public final int level;
        public final long experience;
        public final boolean alive;

        // Constructor to establish the immutable state.
        Snapshot(String name, long id, int health, int attack, int defense, int level, long exp, boolean alive) {
            this.name = name;
            this.characterId = id;
            this.health = health;
            this.attackPower = attack;
            this.defenseValue = defense;
            this.level = level;
            this.experience = exp;
            this.alive = alive;
        }

        // Note: In a real system, comparison methods might be added here.
    }

    /** Represents the outcome of an attack encounter. */
    public static class EncounterResult {
        // The initiator and target references (useful for logging/context, but not for state change)
        public final RPGCharacter initiator;
        public final RPGCharacter target;
        // Damage dealt by initiator to target
        public final Integer damageDealt;
        // Both characters' states after the encounter, captured atomically.
        public final Snapshot selfPost;
        public final Snapshot targetPost;

        /** Constructor for a successful, resulting encounter. */
        EncounterResult(RPGCharacter init, RPGCharacter targ, int damage, {Snapshot selfS, Snapshot targetP}) {
            this.initiator = init;
            this.target = targ;
            this.damageDealt = damage;
            this.selfPost = selfS;
            this.targetPost = targetP;
        }

        /** Constructor for a no-op encounter (e.g., already dead). */
        EncounterResult(RPGCharacter init, RPGCharacter targ, Snapshot selfS, Snapshot targetP) {
            this.initiator = init;
            this.target = targ;
            this.damageDealt = null; // No damage recorded in the result structure if no interaction occurred
            this.selfPost = selfS;
            this.targetPost = targetP;
        }
    }

    // --- Test/Demonstration purposes: Constructor matching the required initialization ---
    private RPGCharacter(String name, int health, int attack, int defense) {
        // Base constructor handles all validation and initialization.
    }
}
