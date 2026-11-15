@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1;

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
        if (name.length() == 0) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("attributes must be nonnegative");
        }

        this.name = name;
        this.id = allocateId();
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (nextId == 0) {
                throw new IllegalStateException("character IDs exhausted");
            }
            long id = nextId;
            nextId = (nextId == 9223372036854775807L) ? 0 : nextId + 1;
            return id;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("target");
        }
        if (other == this) {
            throw new IllegalArgumentException("cannot attack self");
        }

        RPGCharacter first = id < other.id ? this : other;
        RPGCharacter second = first == this ? other : this;

        synchronized (first.lock) {
            synchronized (second.lock) {
                int damage = 0;
                boolean causedDeath = false;

                if (health > 0 && other.health > 0) {
                    int potentialDamage = attackPower - other.defense;
                    if (potentialDamage > 0) {
                        damage = potentialDamage < other.health
                                ? potentialDamage : other.health;
                    }

                    int remainingHealth = other.health - damage;
                    causedDeath = remainingHealth == 0;

                    ExperienceUpdate reward = causedDeath
                            ? calculateExperienceUpdate(10) : null;

                    other.health = remainingHealth;
                    if (reward != null) {
                        applyExperienceUpdate(reward);
                    }
                }

                return new EncounterResult(
                        damage, causedDeath, new Snapshot(this), new Snapshot(other));
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
            ExperienceUpdate update = calculateExperienceUpdate(amount);
            applyExperienceUpdate(update);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(this);
        }
    }

    private ExperienceUpdate calculateExperienceUpdate(long amount) {
        long remainderSum = experience + amount % 100;
        long levelsGained = amount / 100 + remainderSum / 100;

        if (levelsGained > 2147483647L - level
                || levelsGained > (2147483647L - attackPower) / 2
                || levelsGained > 2147483647L - defense) {
            throw new ArithmeticException("level or attribute overflow");
        }

        return new ExperienceUpdate(
                (int) (level + levelsGained),
                (int) (attackPower + 2 * levelsGained),
                (int) (defense + levelsGained),
                remainderSum % 100);
    }

    private void applyExperienceUpdate(ExperienceUpdate update) {
        level = update.level;
        attackPower = update.attackPower;
        defense = update.defense;
        experience = update.experience;
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

        private Snapshot(RPGCharacter character) {
            name = character.name;
            id = character.id;
            health = character.health;
            attackPower = character.attackPower;
            defense = character.defense;
            level = character.level;
            experience = character.experience;
            alive = health > 0;
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
