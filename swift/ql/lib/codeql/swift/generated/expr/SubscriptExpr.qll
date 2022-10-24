// generated by codegen/codegen.py
private import codeql.swift.generated.Synth
private import codeql.swift.generated.Raw
import codeql.swift.elements.expr.Argument
import codeql.swift.elements.expr.LookupExpr

module Generated {
  class SubscriptExpr extends Synth::TSubscriptExpr, LookupExpr {
    override string getAPrimaryQlClass() { result = "SubscriptExpr" }

    /**
     * Gets the `index`th argument of this subscript expression (0-based).
     *
     * This includes nodes from the "hidden" AST. It can be overridden in subclasses to change the
     * behavior of both the `Immediate` and non-`Immediate` versions.
     */
    Argument getImmediateArgument(int index) {
      result =
        Synth::convertArgumentFromRaw(Synth::convertSubscriptExprToRaw(this)
              .(Raw::SubscriptExpr)
              .getArgument(index))
    }

    /**
     * Gets the `index`th argument of this subscript expression (0-based).
     */
    final Argument getArgument(int index) { result = getImmediateArgument(index).resolve() }

    /**
     * Gets any of the arguments of this subscript expression.
     */
    final Argument getAnArgument() { result = getArgument(_) }

    /**
     * Gets the number of arguments of this subscript expression.
     */
    final int getNumberOfArguments() { result = count(getAnArgument()) }

    /**
     * Holds if this subscript expression has direct to storage semantics.
     */
    predicate hasDirectToStorageSemantics() {
      Synth::convertSubscriptExprToRaw(this).(Raw::SubscriptExpr).hasDirectToStorageSemantics()
    }

    /**
     * Holds if this subscript expression has direct to implementation semantics.
     */
    predicate hasDirectToImplementationSemantics() {
      Synth::convertSubscriptExprToRaw(this)
          .(Raw::SubscriptExpr)
          .hasDirectToImplementationSemantics()
    }

    /**
     * Holds if this subscript expression has ordinary semantics.
     */
    predicate hasOrdinarySemantics() {
      Synth::convertSubscriptExprToRaw(this).(Raw::SubscriptExpr).hasOrdinarySemantics()
    }
  }
}
