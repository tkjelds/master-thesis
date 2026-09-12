base_prompt = """Implement a thread-safe concurrent version of the $datastructure data structure in Java.

Requirements:
- Return a complete, self-contained Java source file.
- Implement the data structure yourself; do not use an existing implementation of
  the requested data structure.
- Do not import any libraries or packages. Use only the Java language, primitive
  types, arrays, and classes that are defined in the source file.
- Do not use classes from java.util.concurrent, java.util, or any other
  concurrency or collection library.
- Use only built-in Java synchronization mechanisms and concurrency primitives,
such as synchronized blocks or methods, intrinsic object monitors, volatile
  fields, wait, notify, and notifyAll. Do not use ReentrantLock, Atomic* classes,
  concurrent collections, or other library-provided synchronization classes.
- Ensure that all public operations are thread-safe and preserve the data
  structure's invariants when called concurrently by multiple threads.
- Define a clear policy for operations that cannot complete immediately, such as
  a full bounded structure or an empty structure. If operations block, use
  wait/notifyAll correctly and guard waits with while loops.
- Do not use busy-waiting, Thread.sleep, or arbitrary delays as a synchronization mechanism.
- Prevent lost updates, inconsistent state, unsafe publication, and visibility errors.

Output only the Java source code. Do not include Markdown fences, explanations,
tests, or additional files."""
