@RPGCharacter.ThreadSafe
public class RPGCharacter {
    @interface ThreadSafe {}

    private final String name;
    private final int id = IDGenerator.generate();
    private volatile long experience = 0;
    private volatile int level = 1;
    private volatile int health = 100;
    private volatile int attackPower;
    private volatile int defense;

    public RPGCharacter(String name, int attackPower, int defense) {
        if (name == null || name.isEmpty() || attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("Invalid input");
        }
        this.name = name;
        this.attackPower = attackPower;
        this.defense = defense;
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null || this == other) {
            throw new IllegalArgumentException("Invalid target");
        }
        if (this.health <= 0 || other.health <= 0) {
            return new EncounterResult(this, other);
        }
        int damage = Math.max(0, this.attackPower - other.defense);
        synchronized (this) {
            if (this.health <= 0) {
                return new EncounterResult(this, other);
            }
            this.health -= damage;
        }
        synchronized (other) {
            if (other.health <= 0) {
                return new EncounterResult(this, other);
            }
            other.health -= damage;
        }
        if (this.health <= 0) {
            this.gainExperience(10);
        }
        return new EncounterResult(this, other);
    }

    public int heal() {
        if (this.health <= 0) {
            return this.health;
        }
        synchronized (this) {
            if (this.health <= 0) {
                return this.health;
            }
            int healAmount = Math.min(10, 100 - this.health);
            this.health += healAmount;
            return this.health;
        }
    }

    public void gainExperience(long amount) {
        if (this.health <= 0) {
            throw new IllegalStateException("Character is dead");
        }
        synchronized (this) {
            if (this.health <= 0) {
                throw new IllegalStateException("Character is dead");
            }
            long newExperience = this.experience + amount;
            if (newExperience / 100 > this.level) {
                int newLevel = (int) (newExperience / 100);
                this.level = newLevel;
                this.attackPower += (newLevel - this.level) * 2;
                this.defense += newLevel - this.level;
            }
            this.experience = newExperience % 100;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    private static class IDGenerator {
        private static int nextID = 1;

        public static synchronized int generate() {
            return nextID++;
        }
    }

    private static class EncounterResult {
        private final RPGCharacter attacker;
        private final RPGCharacter defender;

        public EncounterResult(RPGCharacter attacker, RPGCharacter defender) {
            this.attacker = attacker;
            this.defender = defender;
        }

        public RPGCharacter getAttacker() {
            return attacker;
        }

        public RPGCharacter getDefender() {
            return defender;
        }
    }

    private static class Snapshot {
        private final String name;
        private final int id;
        private final boolean alive;
        private final int health;
        private final int attackPower;
        private final int defense;
        private final int level;
        private final long experience;

        public Snapshot(RPGCharacter character) {
            this.name = character.name;
            this.id = character.id;
            this.alive = character.health > 0;
            this.health = character.health;
            this.attackPower = character.attackPower;
            this.defense = character.defense;
            this.level = character.level;
            this.experience = character.experience;
        }

        public String getName() {
            return name;
        }

        public int getId() {
            return id;
        }

        public boolean isAlive() {
            return alive;
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
    }
}
