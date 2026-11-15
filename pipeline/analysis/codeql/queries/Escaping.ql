/**
 * @name Escaping
 * @description In a thread-safe class, care should be taken to avoid exposing mutable state.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id java/escaping
 * @tags quality
 *       reliability
 *       concurrency
 */

import java
import semmle.code.java.ConflictingAccess

from Field f, ClassAnnotatedAsThreadSafe c
where
  f = c.getAField() and
  not f.isFinal() and
  not f.isPrivate() and
  not isThreadSafeType(f.getType()) and  
  not isThreadSafeType(initialValue(f).getType()) and
  not f.isVolatile()
select f, "The class $@ is marked as thread-safe, but this field is potentially escaping.", c,
  c.getName()
