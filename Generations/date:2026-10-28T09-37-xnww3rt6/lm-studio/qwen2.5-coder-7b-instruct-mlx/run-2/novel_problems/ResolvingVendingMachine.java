import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final int[] prices;
    private long funds;
    private Map<Long, Reservation> transactions;
    private Dispenser dispenser;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        this.prices = Arrays.copyOf(prices, prices.length);
        this.funds = 0;
        this.transactions = new HashMap<>();
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        synchronized (this) {
            if (product < 0 || product >= prices.length || quantity < 0) {
                throw new IllegalArgumentException("Invalid restock parameters");
            }
        }
    }

    public void insertFunds(long amount) {
        synchronized (this) {
            if (amount < 0) {
                throw new IllegalArgumentException("Invalid funds amount");
            }
            funds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        synchronized (this) {
            if (transactionId <= 0 || transactions.containsKey(transactionId)) {
                throw new IllegalArgumentException("Invalid transaction ID");
            }
            Reservation reservation = new Reservation(transactionId, quantities);
            if (validateReservation(reservation)) {
                transactions.put(transactionId, reservation);
                return true;
            }
            return false;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (this) {
            Reservation reservation = transactions.get(transactionId);
            if (reservation != null) {
                rollback(reservation);
                transactions.remove(transactionId);
                return true;
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            Map<Long, ReservationState> transactionStates = new HashMap<>();
            for (Map.Entry<Long, Reservation> entry : transactions.entrySet()) {
                transactionStates.put(entry.getKey(), new ReservationState(entry.getValue().quantities, false));
            }
            return new Snapshot(prices, funds, transactionStates);
        }
    }

    private boolean validateReservation(Reservation reservation) {
        for (int i = 0; i < prices.length; i++) {
            if (quantities[i] > 0) {
                funds -= prices[i] * quantities[i];
                if (funds < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private void rollback(Reservation reservation) {
        for (int i = 0; i < prices.length; i++) {
            if (reservation.quantities[i] > 0) {
                funds += prices[i] * reservation.quantities[i];
            }
        }
    }

    private static class Reservation {
        long transactionId;
        int[] quantities;

        Reservation(long transactionId, int[] quantities) {
            this.transactionId = transactionId;
            this.quantities = quantities.clone();
        }
    }

    private static class Snapshot {
        int[] prices;
        long funds;
        Map<Long, ReservationState> transactionStates;

        Snapshot(int[] prices, long funds, Map<Long, ReservationState> transactionStates) {
            this.prices = Arrays.copyOf(prices, prices.length);
            this.funds = funds;
            this.transactionStates = transactionStates;
        }
    }

    private static class ReservationState {
        int[] quantities;
        boolean committed;

        ReservationState(int[] quantities, boolean committed) {
            this.quantities = Arrays.copyOf(quantities, quantities.length);
            this.committed = committed;
        }
    }

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }
}
