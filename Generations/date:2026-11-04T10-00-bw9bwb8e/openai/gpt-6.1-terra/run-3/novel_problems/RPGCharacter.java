@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long lastId = 0;

    private final Object lock = new Object();
    private final String name;
    private final long id;

    // Accessed only while holding lock.
    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (name.length() == 0) {
            throw new IllegalArgumentException("name must be nonempty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("attributes must be nonnegative");
        }

        long allocatedId;
        synchronized (ID_LOCK) {
            if (lastId == Long.MAX_VALUE) {
                throw new IllegalStateException("character IDs exhausted");
            }
            allocatedId = ++lastId;
        }

        this.name = name;
        this.id = allocatedId;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0;
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (other == this) {
            throw new IllegalArgumentException("self-attacks are not allowed");
        }

        RPGCharacter first = this.id < other.id ? this : other;
        RPGCharacter second = this.id < other.id ? other : this;

        synchronized (first.lock) {
            synchronized (second.lock) {
                if (this.health == 0 || other.health == 0) {
                    return new EncounterResult(
                            0, false, this.snapshotLocked(), other.snapshotLocked());
                }

                int potentialDamage = this.attackPower - other.defense;
                if (potentialDamage < 0) {
                    potentialDamage = 0;
                }
                int appliedDamage = potentialDamage < other.health
                        ? potentialDamage : other.health;
                boolean causedDeath = appliedDamage == other.health;

                ExperienceUpdate reward = null;
                if (causedDeath) {
                    // Validate the entire reward before committing any damage.
                    reward = this.prepareExperienceUpdateLocked(10);
                }

                other.health -= appliedDamage;
                if (reward != null) {
                    this.applyExperienceUpdateLocked(reward);
                }

                return new EncounterResult(
                        appliedDamage, causedDeath,
                        this.snapshotLocked(), other.snapshotLocked());
            }
        }
    }

    public int heal() {
        synchronized (lock) {
            if (health > 0) {
                health = health > 90 ? 100 : health + 10;
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
                throw new IllegalStateException("dead characters cannot gain experience");
            }
            ExperienceUpdate update = prepareExperienceUpdateLocked(amount);
            applyExperienceUpdateLocked(update);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    // Caller holds this character's lock.
    private ExperienceUpdate prepareExperienceUpdateLocked(long amount) {
        long levelsGained = amount / 100;
        long remainder = experience + amount % 100;
        if (remainder >= 100) {
            levelsGained++;
            remainder -= 100;
        }

        if (levelsGained > (long) Integer.MAX_VALUE - level
                || levelsGained > ((long) Integer.MAX_VALUE - attackPower) / 2
                || levelsGained > (long) Integer.MAX_VALUE - defense) {
            throw new ArithmeticException("level or attribute overflow");
        }

        return new ExperienceUpdate(
                (int) (level + levelsGained),
                (int) (attackPower + 2 * levelsGained),
                (int) (defense + levelsGained),
                remainder);
    }

    // Caller holds this character's lock.
    private void applyExperienceUpdateLocked(ExperienceUpdate update) {
        level = update.level;
        attackPower = update.attackPower;
        defense = update.defense;
        experience = update.experience;
    }

    // Caller holds this character's lock.
    private Snapshot snapshotLocked() {
        return new Snapshot(name, id, health, attackPower, defense,
                level, experience, health > 0);
    }

    private static final class ExperienceUpdate {
        final int level;
        final int attackPower;
        final int defense;
        final long experience;

        ExperienceUpdate(int level, int attackPower, int defense, long experience) {
            this.level = level;
            this.attackPower = attackPower;
            this.defense = defense;
            this.experience = experience;
        }
    }

    public static final class Snapshot {
        public final String name;
        public final long id;
        public final int health;
        public final int attackPower;
        public final int defense;
        public final int level;
        public final long experience;
        public final boolean alive;

        private Snapshot(String name, long id, int health, int attackPower,
                         int defense, int level, long experience, boolean alive) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = alive;
        }

        public String getName() { return name; }
        public long getId() { return id; }
        public int getHealth() { return health; }
        public int getAttackPower() { return attackPower; }
        public int getDefense() { return defense; }
        public int getLevel() { return level; }
        public long getExperience() { return experience; }
        public boolean isAlive() { return alive; }
    }

    public static final class EncounterResult {
        public final int appliedDamage;
        public final boolean causedDeath;
        public final Snapshot attackerSnapshot;
        public final Snapshot targetSnapshot;

        private EncounterResult(int appliedDamage, boolean causedDeath,
                                Snapshot attackerSnapshot, Snapshot targetSnapshot) {
            this.appliedDamage = appliedDamage;
            this.causedDeath = causedDeath;
            this.attackerSnapshot = attackerSnapshot;
            this.targetSnapshot = targetSnapshot;
        }

        public int getAppliedDamage() { return appliedDamage; }
        public boolean causedDeath() { return causedDeath; }
        public Snapshot getAttackerSnapshot() { return attackerSnapshot; }
        public Snapshot getTargetSnapshot() { return targetSnapshot; }
    }
}
