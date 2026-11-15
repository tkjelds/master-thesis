@RPGCharacter.ThreadSafe
public class RPGCharacter {
    @interface ThreadSafe {}

    private String name;
    private long id;
    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;
    private static volatile long nextId = 0;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name cannot be null or empty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("Health must be in the range [1, 100]");
        }
        if (attackPower < 0) {
            throw new IllegalArgumentException("Attack power must be nonnegative");
        }
        if (defense < 0) {
            throw new IllegalArgumentException("Defense must be nonnegative");
        }
        this.name = name;
        this.id = nextId++;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0;
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new IllegalArgumentException("Target cannot be null");
        }
        synchronized (this) {
            if (this.health <= 0 || other.health <= 0) {
                return new EncounterResult(this.snapshot(), other.snapshot());
            }
        }

        int damage = Math.max(0, this.attackPower - other.defense);
        synchronized (this) {
            if (this.health <= 0 || other.health <= 0) {
                return new EncounterResult(this.snapshot(), other.snapshot());
            }
        }

        synchronized (other) {
            if (this.health <= 0 || other.health <= 0) {
                return new EncounterResult(this.snapshot(), other.snapshot());
            }
        }

        synchronized (this) {
            if (this.health <= 0 || other.health <= 0) {
                return new EncounterResult(this.snapshot(), other.snapshot());
            }
        }

        synchronized (other) {
            int newHealth = other.health - damage;
            if (newHealth < 0) {
                newHealth = 0;
                other.gainExperience(10);
            }
            other.health = newHealth;

            if (other.health == 0 && damage > other.attackPower - this.defense) {
                synchronized (this) {
                    this.gainExperience(10);
                }
            }

            return new EncounterResult(this.snapshot(), other.snapshot());
        }
    }

    public void heal() {
        synchronized (this) {
            if (this.health <= 0) {
                return;
            }
        }

        synchronized (this) {
            this.health = Math.min(100, this.health + 10);
        }
    }

    public void gainExperience(long amount) {
        synchronized (this) {
            if (this.health <= 0) {
                throw new IllegalStateException("Cannot gain experience on a dead character");
            }
        }

        synchronized (this) {
            this.experience += amount;
            while (this.experience >= 100) {
                this.experience -= 100;
                this.level += 1;
                this.attackPower += 2;
                this.defense += 1;
            }
        }
    }

    public EncounterResult snapshot() {
        synchronized (this) {
            return new EncounterResult(this.name, this.id, this.health, this.attackPower, this.defense, this.level, this.experience, this.health > 0);
        }
    }

    public static class EncounterResult {
        private final String name;
        private final long id;
        private final int health;
        private final int attackPower;
        private final int defense;
        private final int level;
        private final long experience;
        private final boolean alive;

        public EncounterResult(String name, long id, int health, int attackPower, int defense, int level, long experience, boolean alive) {
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
}
