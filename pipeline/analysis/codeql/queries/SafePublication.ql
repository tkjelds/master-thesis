/**
 * @name Safe publication
 * @description A field of a thread-safe class is not safely published.
 * @kind problem
 * @problem.severity warning
 * @precision high
 * @id java/safe-publication
 * @tags quality
 *       reliability
 *       concurrency
 */

import java
import semmle.code.java.ConflictingAccess

predicate shouldBeDefaultValueFor(Field f, Expr v) {
  v = f.getAnAssignedValue() and
  (
    v = f.getInitializer()
    or
    v.getEnclosingCallable() = f.getDeclaringType().getAConstructor()
  )
}

bindingset[result]
Expr getDefaultValue(Field f) {
  f.getType().hasName("byte") and result.(IntegerLiteral).getIntValue() = 0
  or
  f.getType().hasName("short") and result.(IntegerLiteral).getIntValue() = 0
  or
  f.getType().hasName("int") and result.(IntegerLiteral).getIntValue() = 0
  or
  f.getType().hasName("long") and
  (
    result.(LongLiteral).getValue() = "0" or
    result.(IntegerLiteral).getValue() = "0"
  )
  or
  f.getType().hasName("float") and result.(FloatLiteral).getValue() = "0.0"
  or
  f.getType().hasName("double") and result.(DoubleLiteral).getValue() = "0.0"
  or
  f.getType().hasName("char") and result.(CharacterLiteral).getCodePointValue() = 0
  or
  f.getType().hasName("boolean") and result.(BooleanLiteral).getBooleanValue() = false
  or
  not f.getType().getName() in [
      "byte", "short", "int", "long", "float", "double", "char", "boolean"
    ] and
  result instanceof NullLiteral
}

predicate isAssignedDefaultValue(Field f) {
  forall(Expr v | shouldBeDefaultValueFor(f, v) | v = getDefaultValue(f))
}

predicate isSafelyPublished(Field f) {
  f.isFinal() or
  f.isStatic() or
  f.isVolatile() or
  isAssignedDefaultValue(f)
}

from Field f, ClassAnnotatedAsThreadSafe c
where
  f = c.getAField() and
  not isSafelyPublished(f)
select f, "The class $@ is marked as thread-safe, but this field is not safely published.", c,
  c.getName()
