@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final int MAX_HEALTH = 100;
    private static final int HEAL_AMOUNT = 10;
    private static final int KILL_EXPERIENCE = 10;
    private static final long POINTS_PER_LEVEL = 100L;

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;

    private final long id;
    private final String name;

    // Guarded by this object's monitor.
    private int health;
    private int attack;
    private int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attack, int defense) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must be nonempty");
        }
        if (health < 1 || health > MAX_HEALTH) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attack < 0) {
            throw new IllegalArgumentException("attack must be nonnegative");
        }
        if (defense < 0) {
            throw new IllegalArgumentException("defense must be nonnegative");
        }
        this.id = allocateId();
        this.name = name;
        this.health = health;
        this.attack = attack;
        this.defense = defense;
        this.level = 1;
        this.experience = 0L;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("character ID space exhausted");
            }
            return nextId++;
        }
    }

    public AttackResult attack(RPGCharacter target) {
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (target == this) {
            throw new IllegalArgumentException("self-attack is not allowed");
        }

        // Consistent lock ordering by immutable unique ID prevents deadlock.
        RPGCharacter low = (this.id < target.id) ? this : target;
        RPGCharacter high = (low == this) ? target : this;

        synchronized (low) {
            synchronized (high) {
                if (this.health == 0 || target.health == 0) {
                    return new AttackResult(0, false, this.snapshotLocked(), target.snapshotLocked());
                }

                long raw = (long) this.attack - (long) target.defense;
                int damage = raw > 0L ? (int) raw : 0;
                int applied = Math.min(damage, target.health);
                boolean killed = (target.health - applied) == 0;

                // Validate the killing-blow reward before any mutation.
                long rewardLevels = 0L;
                if (killed) {
                    rewardLevels = this.validateGain(KILL_EXPERIENCE);
                }

                target.health -= applied;
                if (killed) {
                    this.commitGain(KILL_EXPERIENCE, rewardLevels);
                }

                return new AttackResult(applied, killed, this.snapshotLocked(), target.snapshotLocked());
            }
        }
    }

    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }
            health = Math.min(MAX_HEALTH, health + HEAL_AMOUNT);
            return health;
        }
    }

    public void gainExperience(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("experience amount must be nonnegative");
        }
        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("dead character cannot gain experience");
            }
            long levels = validateGain(amount);
            commitGain(amount, levels);
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    // Caller must hold this object's monitor.
    private Snapshot snapshotLocked() {
        return new Snapshot(name, id, health, attack, defense, level, experience, health > 0);
    }

    // Caller must hold this object's monitor. Validates without mutating; returns levels gained.
    private long validateGain(long amount) {
        if (amount > Long.MAX_VALUE - experience) {
            throw new IllegalStateException("experience overflow");
        }
        long total = experience + amount;
        long levels = total / POINTS_PER_LEVEL;

        if (levels > (long) Integer.MAX_VALUE - level) {
            throw new IllegalStateException("level overflow");
        }
        long newLevel = level + levels;
        if (newLevel > Integer.MAX_VALUE) {
            throw new IllegalStateException("level overflow");
        }
        long newAttack = (long) attack + 2L * levels;
        if (newAttack > Integer.MAX_VALUE) {
            throw new IllegalStateException("attack overflow");
        }
        long newDefense = (long) defense + levels;
        if (newDefense > Integer.MAX_VALUE) {
            throw new IllegalStateException("defense overflow");
        }
        return levels;
    }

    // Caller must hold this object's monitor and have validated the gain.
    private void commitGain(long amount, long levels) {
        long total = experience + amount;
        experience = total % POINTS_PER_LEVEL;
        level += (int) levels;
        attack += (int) (2L * levels);
        defense += (int) levels;
    }

    public static final class Snapshot {
        private final String name;
        private final long id;
        private final int health;
        private final int attack;
        private final int defense;
        private final int level;
        private final long experience;
        private final boolean alive;

        private Snapshot(String name, long id, int health, int attack, int defense,
                         int level, long experience, boolean alive) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attack = attack;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = alive;
        }

        public String getName() {
            return name;
        }

        public long getId() {
            return id;
        }

        public int getHealth() {
            return health;
        }

        public int getAttack() {
            return attack;
        }

        public int getDefense() {
            return defense;
        }

        public int getLevel() {
            return level;
        }

        public long getExperience() {
            return experience;
        }

        public boolean isAlive() {
            return alive;
        }
    }

    public static final class AttackResult {
        private final int appliedDamage;
        private final boolean targetDefeated;
        private final Snapshot attackerSnapshot;
        private final Snapshot targetSnapshot;

        private AttackResult(int appliedDamage, boolean targetDefeated,
                             Snapshot attackerSnapshot, Snapshot targetSnapshot) {
            this.appliedDamage = appliedDamage;
            this.targetDefeated = targetDefeated;
            this.attackerSnapshot = attackerSnapshot;
            this.targetSnapshot = targetSnapshot;
        }

        public int getAppliedDamage() {
            return appliedDamage;
        }

        public boolean didCauseDeath() {
            return targetDefeated;
        }

        public Snapshot getAttackerSnapshot() {
            return attackerSnapshot;
        }

        public Snapshot getTargetSnapshot() {
            return targetSnapshot;
        }
    }
}
