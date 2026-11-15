public final class ResolvingVendingMachine {
    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

 public enum TransactionStatus {
     PENDING,
     COMMITTED,
     ROLLED_BACK,
     UNAVAILABLE
 }

 public static final class Snapshot {
     public final long[] availableStock;
     public final long availableFunds;
     public final long[] transactionIds;
     public final TransactionStatus[] transactionStatuses;

     private Snapshot(
             long[] availableStock,
             long availableFunds,
             long[] transactionIds,
             TransactionStatus[] transactionStatuses) {
         this.availableStock = availableStock;
         this.availableFunds = availableFunds;
         this.transactionIds = transactionIds;
         this.transactionStatuses = transactionStatuses;
     }

     public long[] getAvailableStock() {
         return copyLongArray(availableStock);
     }

     public long getAvailableFunds() {
         return availableFunds;
     }

     public long[] getTransactionIds() {
         return copyLongArray(transactionIds);
     }

     public TransactionStatus[] getTransactionStatuses() {
         return copyStatusArray(transactionStatuses);
     }
 }

 private static final class TransactionRecord {
     private final long transactionId;
     private final int[] quantities;
     private final long price;
     private TransactionStatus status;
     private TransactionRecord next;

     private TransactionRecord(long transactionId, int[] quantities, long price) {
         this.transactionId = transactionId;
         this.quantities = copyIntArray(quantities);
         this.price = price;
         this.status = TransactionStatus.PENDING;
     }
 }

 private final Object stateLock = new Object();
 private final int[] prices;
 private final long[] stock;
 private final long[] reservedStock;
 private final Dispenser dispenser;

 private long funds;
 private long reservedFunds;
 private TransactionRecord transactions;

 public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
     if (prices == null) {
         throw new IllegalArgumentException("prices must not be null");
     }
     if (dispenser == null) {
         throw new IllegalArgumentException("dispenser must not be null");
     }

     this.prices = copyIntArray(prices);
     for (int price : this.prices) {
         if (price < 0) {
             throw new IllegalArgumentException("prices must be nonnegative");
         }
     }

     this.stock = new long[this.prices.length];
     this.reservedStock = new long[this.prices.length];
     this.dispenser = dispenser;
 }

 public void restock(int product, int quantity) {
     if (product < 0 || product >= prices.length) {
         throw new IllegalArgumentException("invalid product");
     }
     if (quantity < 0) {
         throw new IllegalArgumentException("quantity must be nonnegative");
     }

     synchronized (stateLock) {
         if (stock[product] > Long.MAX_VALUE - quantity) {
             throw new ArithmeticException("stock overflow");
         }
         stock[product] += quantity;
     }
 }

 public void insertFunds(long amount) {
     if (amount < 0) {
         throw new IllegalArgumentException("amount must be nonnegative");
     }

     synchronized (stateLock) {
         if (funds > Long.MAX_VALUE - amount) {
             throw new ArithmeticException("funds overflow");
         }
         funds += amount;
     }
 }

 public boolean purchaseBundle(long transactionId, int[] quantities) {
     if (transactionId <= 0) {
         throw new IllegalArgumentException("transactionId must be positive");
     }
     if (quantities == null || quantities.length != prices.length) {
         throw new IllegalArgumentException("quantities must contain one entry per product");
     }

     int[] requested = copyIntArray(quantities);
     boolean hasPositiveQuantity = false;
     long price = 0;

     for (int i = 0; i < requested.length; i++) {
         int quantity = requested[i];
         if (quantity < 0) {
             throw new IllegalArgumentException("quantities must be nonnegative");
         }
         if (quantity > 0) {
             hasPositiveQuantity = true;
         }

         long itemPrice = (long) quantity * prices[i];
         if (price > Long.MAX_VALUE - itemPrice) {
             throw new ArithmeticException("bundle price overflow");
         }
         price += itemPrice;
     }

     if (!hasPositiveQuantity) {
         throw new IllegalArgumentException("bundle must contain a positive quantity");
     }

     TransactionRecord record = new TransactionRecord(transactionId, requested, price);

     synchronized (stateLock) {
         if (findTransaction(transactionId) != null) {
             throw new IllegalArgumentException("transactionId has already been used");
         }

         record.next = transactions;
         transactions = record;

         if (funds - reservedFunds < price) {
             record.status = TransactionStatus.UNAVAILABLE;
             return false;
         }
         for (int i = 0; i < requested.length; i++) {
             if (stock[i] - reservedStock[i] < requested[i]) {
                 record.status = TransactionStatus.UNAVAILABLE;
                 return false;
             }
         }

         reservedFunds += price;
         for (int i = 0; i < requested.length; i++) {
             reservedStock[i] += requested[i];
         }
         record.status = TransactionStatus.PENDING;
     }

     boolean dispensed;
     try {
         dispensed = dispenser.dispense(transactionId, copyIntArray(record.quantities));
     } catch (RuntimeException exception) {
         synchronized (stateLock) {
             rollbackIfPending(record);
         }
         throw exception;
     }

     synchronized (stateLock) {
         if (record.status != TransactionStatus.PENDING) {
             return false;
         }

         if (!dispensed) {
             rollbackIfPending(record);
             return false;
         }

         reservedFunds -= record.price;
         funds -= record.price;
         for (int i = 0; i < record.quantities.length; i++) {
             reservedStock[i] -= record.quantities[i];
             stock[i] -= record.quantities[i];
         }
         record.status = TransactionStatus.COMMITTED;
         return true;
     }
 }

 public boolean cancel(long transactionId) {
     synchronized (stateLock) {
         TransactionRecord record = findTransaction(transactionId);
         if (record == null || record.status != TransactionStatus.PENDING) {
             return false;
         }
         rollbackIfPending(record);
         return true;
     }
 }

 public Snapshot snapshot() {
     synchronized (stateLock) {
         long[] availableStock = new long[stock.length];
         for (int i = 0; i < stock.length; i++) {
             availableStock[i] = stock[i] - reservedStock[i];
         }

         int count = 0;
         for (TransactionRecord record = transactions; record != null; record = record.next) {
             count++;
         }

         long[] transactionIds = new long[count];
         TransactionStatus[] transactionStatuses = new TransactionStatus[count];
         int index = 0;
         for (TransactionRecord record = transactions; record != null; record = record.next) {
             transactionIds[index] = record.transactionId;
             transactionStatuses[index] = record.status;
             index++;
         }

         return new Snapshot(
                 availableStock,
                 funds - reservedFunds,
                 transactionIds,
                 transactionStatuses);
     }
 }

 private TransactionRecord findTransaction(long transactionId) {
     for (TransactionRecord record = transactions; record != null; record = record.next) {
         if (record.transactionId == transactionId) {
             return record;
         }
     }
     return null;
 }

 private void rollbackIfPending(TransactionRecord record) {
     if (record.status != TransactionStatus.PENDING) {
         return;
     }

     reservedFunds -= record.price;
     for (int i = 0; i < record.quantities.length; i++) {
         reservedStock[i] -= record.quantities[i];
     }
     record.status = TransactionStatus.ROLLED_BACK;
 }

 private static int[] copyIntArray(int[] source) {
     int[] copy = new int[source.length];
     for (int i = 0; i < source.length; i++) {
         copy[i] = source[i];
     }
     return copy;
 }

 private static long[] copyLongArray(long[] source) {
     long[] copy = new long[source.length];
     for (int i = 0; i < source.length; i++) {
         copy[i] = source[i];
     }
     return copy;
 }

 private static TransactionStatus[] copyStatusArray(TransactionStatus[] source) {
     TransactionStatus[] copy = new TransactionStatus[source.length];
     for (int i = 0; i < source.length; i++) {
         copy[i] = source[i];
     }
     return copy;
 }

}
