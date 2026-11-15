@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long lastId = 0;

    private static final long MAX_LONG = 0x7fffffffffffffffL;
    private static final int MAX_INT = 0x7fffffff;
    private static final int MAX_HEALTH = 100;

    private final Object lock = new Object();
    private final String name;
    private final long id;

    // All mutable character state is guarded by lock.
    private int health;
    private int attackPower;
    private int defense;
    private long level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (name.length() == 0) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (health < 1 || health > MAX_HEALTH) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("stats must be nonnegative");
        }

        this.id = allocateId();
        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (lastId == MAX_LONG) {
                throw new IllegalStateException("character IDs exhausted");
            }
            lastId++;
            return lastId;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (other == this) {
            throw new IllegalArgumentException("cannot attack self");
        }

        RPGCharacter first = id < other.id ? this : other;
        RPGCharacter second = id < other.id ? other : this;

        synchronized (first.lock) {
            synchronized (second.lock) {
                return attackLocked(other);
            }
        }
    }

    // Caller holds both character locks.
    private EncounterResult attackLocked(RPGCharacter other) {
        int damage = 0;
        boolean causedDeath = false;

        if (health > 0 && other.health > 0) {
            int potentialDamage = attackPower - other.defense;
            if (potentialDamage > 0) {
                damage = potentialDamage < other.health
                        ? potentialDamage : other.health;
                causedDeath = damage == other.health;

                if (causedDeath) {
                    // Validate and apply the reward before committing damage.
                    // Both locks remain held throughout the encounter.
                    addExperienceLocked(10);
                }
                other.health -= damage;
            }
        }

        return new EncounterResult(
                damage, causedDeath, snapshotLocked(), other.snapshotLocked());
    }

    public int heal() {
        synchronized (lock) {
            if (health > 0) {
                health = health > MAX_HEALTH - 10 ? MAX_HEALTH : health + 10;
            }
            return health;
        }
    }

    public void gainExperience(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }
        synchronized (lock) {
            if (health == 0) {
                throw new IllegalStateException("dead character");
            }
            addExperienceLocked(amount);
        }
    }

    // Caller holds this character's lock. All checks precede mutation.
    private void addExperienceLocked(long amount) {
        long remainderSum = experience + amount % 100;
        long levelsGained = amount / 100 + remainderSum / 100;
        long newExperience = remainderSum % 100;

        if (level > MAX_LONG - levelsGained) {
            throw new ArithmeticException("level overflow");
        }
        if (levelsGained > (MAX_INT - attackPower) / 2L) {
            throw new ArithmeticException("attack power overflow");
        }
        if (levelsGained > (long) MAX_INT - defense) {
            throw new ArithmeticException("defense overflow");
        }

        long newLevel = level + levelsGained;
        int newAttackPower = (int) (attackPower + levelsGained * 2);
        int newDefense = (int) (defense + levelsGained);

        level = newLevel;
        attackPower = newAttackPower;
        defense = newDefense;
        experience = newExperience;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    // Caller holds this character's lock.
    private Snapshot snapshotLocked() {
        return new Snapshot(
                name, id, health, attackPower, defense, level, experience);
    }

    public static final class Snapshot {
        public final String name;
        public final long id;
        public final int health;
        public final int attackPower;
        public final int defense;
        public final long level;
        public final long experience;
        public final boolean alive;

        private Snapshot(
                String name, long id, int health, int attackPower, int defense,
                long level, long experience) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = health > 0;
        }
    }

    public static final class EncounterResult {
        public final int appliedDamage;
        public final boolean causedDeath;
        public final Snapshot attacker;
        public final Snapshot target;

        private EncounterResult(
                int appliedDamage, boolean causedDeath,
                Snapshot attacker, Snapshot target) {
            this.appliedDamage = appliedDamage;
            this.causedDeath = causedDeath;
            this.attacker = attacker;
            this.target = target;
        }
    }
}
