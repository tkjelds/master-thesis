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
            throw new IllegalArgumentException("name must not be empty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("attributes must be nonnegative");
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
            if (lastId == 9223372036854775807L) {
                throw new IllegalStateException("character IDs exhausted");
            }
            lastId++;
            return lastId;
        }
    }

    /*
     * Operations wait only to acquire the required intrinsic monitors.
     * Encounters acquire both character monitors in increasing ID order.
     */
    public AttackResult attack(RPGCharacter other) {
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
                    return new AttackResult(
                            0, false, this.snapshotLocked(), other.snapshotLocked());
                }

                int damage = this.attackPower > other.defense
                        ? this.attackPower - other.defense : 0;
                if (damage > other.health) {
                    damage = other.health;
                }

                boolean causedDeath = damage == other.health;
                ExperienceUpdate reward = causedDeath
                        ? this.prepareExperienceUpdate(10) : null;

                other.health -= damage;
                if (reward != null) {
                    this.applyExperienceUpdate(reward);
                }

                return new AttackResult(
                        damage, causedDeath, this.snapshotLocked(), other.snapshotLocked());
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
            throw new IllegalArgumentException("experience must be nonnegative");
        }

        synchronized (lock) {
            if (health == 0) {
                throw new IllegalStateException("dead characters cannot gain experience");
            }
            ExperienceUpdate update = prepareExperienceUpdate(amount);
            applyExperienceUpdate(update);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    // The caller must hold this character's lock.
    private ExperienceUpdate prepareExperienceUpdate(long amount) {
        if (amount > 9223372036854775807L - experience) {
            throw new ArithmeticException("experience overflow");
        }

        long total = experience + amount;
        long gainedLevels = total / 100;
        long remainingExperience = total % 100;

        if (gainedLevels > 2147483647 - level
                || gainedLevels > (2147483647 - attackPower) / 2
                || gainedLevels > 2147483647 - defense) {
            throw new ArithmeticException("level or attribute overflow");
        }

        int increase = (int) gainedLevels;
        return new ExperienceUpdate(
                level + increase,
                attackPower + 2 * increase,
                defense + increase,
                remainingExperience);
    }

    // The caller must hold this character's lock.
    private void applyExperienceUpdate(ExperienceUpdate update) {
        level = update.level;
        attackPower = update.attackPower;
        defense = update.defense;
        experience = update.experience;
    }

    // The caller must hold this character's lock.
    private Snapshot snapshotLocked() {
        return new Snapshot(
                name, id, health, attackPower, defense, level, experience, health > 0);
    }

    private static final class ExperienceUpdate {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private ExperienceUpdate(
                int level, int attackPower, int defense, long experience) {
            this.level = level;
            this.attackPower = attackPower;
            this.defense = defense;
            this.experience = experience;
        }
    }

    public static final class Snapshot {
        private final String name;
        private final long id;
        private final int health;
        private final int attackPower;
        private final int defense;
        private final int level;
        private final long experience;
        private final boolean alive;

        private Snapshot(
                String name, long id, int health, int attackPower, int defense,
                int level, long experience, boolean alive) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
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

        public int getAttackPower() {
            return attackPower;
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
        private final int damage;
        private final boolean causedDeath;
        private final Snapshot attackerSnapshot;
        private final Snapshot targetSnapshot;

        private AttackResult(
                int damage, boolean causedDeath,
                Snapshot attackerSnapshot, Snapshot targetSnapshot) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attackerSnapshot = attackerSnapshot;
            this.targetSnapshot = targetSnapshot;
        }

        public int getDamage() {
            return damage;
        }

        public boolean isCausedDeath() {
            return causedDeath;
        }

        public Snapshot getAttackerSnapshot() {
            return attackerSnapshot;
        }

        public Snapshot getTargetSnapshot() {
            return targetSnapshot;
        }
    }
}
