@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long lastId = 0;

    private final Object lock = new Object();
    private final String name;
    private final long id;

    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("attributes must be nonnegative");
        }

        this.name = name;
        synchronized (ID_LOCK) {
            if (lastId == Long.MAX_VALUE) {
                throw new IllegalStateException("character IDs exhausted");
            }
            this.id = ++lastId;
        }

        synchronized (lock) {
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = 1;
            this.experience = 0;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (other == this) {
            throw new IllegalArgumentException("cannot attack oneself");
        }

        RPGCharacter first = id < other.id ? this : other;
        RPGCharacter second = id < other.id ? other : this;

        synchronized (first.lock) {
            synchronized (second.lock) {
                int damage = 0;
                boolean causedDeath = false;

                if (health > 0 && other.health > 0) {
                    int potentialDamage = attackPower - other.defense;
                    if (potentialDamage > 0) {
                        damage = Math.min(potentialDamage, other.health);
                        causedDeath = damage == other.health;

                        ExperienceUpdate reward = null;
                        if (causedDeath) {
                            reward = calculateExperienceUpdate(10);
                        }

                        other.health -= damage;
                        if (reward != null) {
                            applyExperienceUpdate(reward);
                        }
                    }
                }

                return new EncounterResult(
                    damage, causedDeath, snapshotLocked(), other.snapshotLocked()
                );
            }
        }
    }

    public int heal() {
        synchronized (lock) {
            if (health > 0) {
                health = Math.min(100, health + 10);
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
            applyExperienceUpdate(calculateExperienceUpdate(amount));
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private ExperienceUpdate calculateExperienceUpdate(long amount) {
        long remainder = experience + amount % 100;
        long levelsGained = amount / 100 + remainder / 100;
        long newExperience = remainder % 100;

        if (levelsGained > (long) Integer.MAX_VALUE - level
                || levelsGained > ((long) Integer.MAX_VALUE - attackPower) / 2
                || levelsGained > (long) Integer.MAX_VALUE - defense) {
            throw new ArithmeticException("level or attribute overflow");
        }

        return new ExperienceUpdate(
            level + (int) levelsGained,
            attackPower + (int) (levelsGained * 2),
            defense + (int) levelsGained,
            newExperience
        );
    }

    private void applyExperienceUpdate(ExperienceUpdate update) {
        level = update.level;
        attackPower = update.attackPower;
        defense = update.defense;
        experience = update.experience;
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
            name, id, health, attackPower, defense, level, experience, health > 0
        );
    }

    private static final class ExperienceUpdate {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private ExperienceUpdate(int level, int attackPower, int defense, long experience) {
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
    }

    public static final class EncounterResult {
        public final int damage;
        public final boolean causedDeath;
        public final Snapshot attacker;
        public final Snapshot target;

        private EncounterResult(
                int damage, boolean causedDeath, Snapshot attacker, Snapshot target) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attacker = attacker;
            this.target = target;
        }
    }
}
