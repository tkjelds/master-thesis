// overlay[local?]
import java
import semmle.code.java.frameworks.Mockito
import semmle.code.java.Reflection

class LockType extends RefType {
  LockType() {
    this.getAMethod().hasName("lock") and
    this.getAMethod().hasName("unlock")
  }

  private Method getLockMethod() {
    result.getDeclaringType() = this and
    result.hasName(["lock", "lockInterruptibly", "tryLock"])
  }

  private Method getUnlockMethod() {
    result.getDeclaringType() = this and
    result.hasName("unlock")
  }

  private Method getIsHeldByCurrentThreadMethod() {
    result.getDeclaringType() = this and
    result.hasName("isHeldByCurrentThread")
  }

  MethodCall getLockAccess() {
    result.getMethod() = this.getLockMethod() and
    not result instanceof MockitoVerifiedMethodCall
  }

  MethodCall getUnlockAccess() {
    result.getMethod() = this.getUnlockMethod() and
    not result instanceof MockitoVerifiedMethodCall
  }

  MethodCall getIsHeldByCurrentThreadAccess() {
    result.getMethod() = this.getIsHeldByCurrentThreadMethod() and
    not result instanceof MockitoVerifiedMethodCall
  }
}

predicate locallySynchronizedOn(Expr e, SynchronizedStmt sync, Variable v) {
  not e.(VarAccess).getVariable().isStatic() and
  (
    e.getEnclosingStmt().getEnclosingStmt+() = sync and
    sync.getExpr().(VarAccess).getVariable() = v
  )
}

predicate locallySynchronizedOnThis(Expr e, RefType thisType) {
  not e.(VarAccess).getVariable().isStatic() and
  (
    exists(SynchronizedStmt sync | e.getEnclosingStmt().getEnclosingStmt+() = sync |
      sync.getExpr().(ThisAccess).getType().(RefType).getSourceDeclaration() = thisType
    )
    or
    exists(SynchronizedCallable c | c = e.getEnclosingCallable() |
      not c.isStatic() and thisType = c.getDeclaringType()
    )
  )
}

predicate locallySynchronizedOnClass(Expr e, RefType classType) {
  exists(SynchronizedCallable c | c = e.getEnclosingCallable() |
    c.isStatic() and classType = c.getDeclaringType()
  )
  or
  exists(SynchronizedStmt sync |
    e.getEnclosingStmt().getEnclosingStmt+() = sync and
    sync.getExpr().(ReflectiveClassIdentifierLiteral).getReflectivelyIdentifiedClass() = classType
  )
}

predicate test(SynchronizedStmt sync, Expr e, string name) {
  e = sync.getExpr() and e.getAQlClass() = name
}

class SynchronizedCallable extends Callable {
  SynchronizedCallable() {
    this.isSynchronized()
    or
    exists(SynchronizedStmt s | this.getBody().(SingletonBlock).getStmt() = s |
      s.getExpr().(ThisAccess).getType() = this.getDeclaringType()
    )
  }
}

module Monitors {
  newtype TMonitor =
    TVariableMonitor(Variable v) {
      v.getType() instanceof LockType or locallySynchronizedOn(_, _, v)
    } or
    TInstanceMonitor(RefType thisType) { locallySynchronizedOnThis(_, thisType) } or
    TClassMonitor(RefType classType) { locallySynchronizedOnClass(_, classType) }

  class Monitor extends TMonitor {
    abstract Location getLocation();

    abstract string toString();
  }

  class VariableMonitor extends Monitor, TVariableMonitor {
    override Location getLocation() { result = this.getVariable().getLocation() }

    override string toString() { result = "VariableMonitor(" + this.getVariable().toString() + ")" }

    Variable getVariable() { this = TVariableMonitor(result) }
  }

  class InstanceMonitor extends Monitor, TInstanceMonitor {
    override Location getLocation() { result = this.getThisType().getLocation() }

    override string toString() { result = "InstanceMonitor(" + this.getThisType().toString() + ")" }

    RefType getThisType() { this = TInstanceMonitor(result) }
  }

  class ClassMonitor extends Monitor, TClassMonitor {
    override Location getLocation() { result = this.getClassType().getLocation() }

    override string toString() { result = "ClassMonitor(" + this.getClassType().toString() + ")" }

    RefType getClassType() { this = TClassMonitor(result) }
  }

  predicate locallyMonitors(Expr e, Monitor m) {
    (
    exists(Variable v | v = m.(VariableMonitor).getVariable() |
      locallyLockedOn(e, v)
      or
      locallySynchronizedOn(e, _, v)
    )
    or
    locallySynchronizedOnThis(e, m.(InstanceMonitor).getThisType())
    or
    locallySynchronizedOnClass(e, m.(ClassMonitor).getClassType())
    )
    or
    locallyMonitors(e.getParent(), m)
  }

  ControlFlowNode getNodeToBeDominated(Expr e) {
    exists(Assignment asgn | asgn.getDest() = e | result = asgn.getControlFlowNode())
    or
    not exists(Assignment asgn | asgn.getDest() = e) and
    result = e.getControlFlowNode()
  }

  class LockField extends Field {
    LockField() { this.getType() instanceof LockType }

    MethodCall getLockCall() {
      result.getQualifier() = this.getRepresentative().getAnAccess() and
      result = this.getType().(LockType).getLockAccess()
    }

    MethodCall getUnlockCall() {
      result.getQualifier() = this.getRepresentative().getAnAccess() and
      result = this.getType().(LockType).getUnlockAccess()
    }

    private Variable getRepresentative() {
      result = this
      or
      result.getInitializer() = this.getAnAccess()
    }
  }

  predicate locallyLockedOn(Expr e, LockField lock) {
    not e.(VarAccess).getVariable().isStatic() and
    exists(MethodCall lockCall, MethodCall unlockCall |
      lockCall = lock.getLockCall() and
      unlockCall = lock.getUnlockCall()
    |
      dominates(lockCall.getControlFlowNode(), unlockCall.getControlFlowNode()) and
      dominates(lockCall.getControlFlowNode(), getNodeToBeDominated(e)) and
      postDominates(unlockCall.getControlFlowNode(), getNodeToBeDominated(e))
    )
  }
}
