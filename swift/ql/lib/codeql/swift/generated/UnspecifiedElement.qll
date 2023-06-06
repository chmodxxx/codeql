// generated by codegen/codegen.py
private import codeql.swift.generated.Synth
private import codeql.swift.generated.Raw
import codeql.swift.elements.Element
import codeql.swift.elements.ErrorElement

module Generated {
  class UnspecifiedElement extends Synth::TUnspecifiedElement, ErrorElement {
    override string getAPrimaryQlClass() { result = "UnspecifiedElement" }

    /**
     * Gets the parent of this unspecified element, if it exists.
     *
     * This includes nodes from the "hidden" AST. It can be overridden in subclasses to change the
     * behavior of both the `Immediate` and non-`Immediate` versions.
     */
    Element getImmediateParent() {
      result =
        Synth::convertElementFromRaw(Synth::convertUnspecifiedElementToRaw(this)
              .(Raw::UnspecifiedElement)
              .getParent())
    }

    /**
     * Gets the parent of this unspecified element, if it exists.
     */
    final Element getParent() {
      exists(Element immediate |
        immediate = this.getImmediateParent() and
        result = immediate.resolve()
      )
    }

    /**
     * Holds if `getParent()` exists.
     */
    final predicate hasParent() { exists(this.getParent()) }

    /**
     * Gets the property of this unspecified element.
     */
    string getProperty() {
      result = Synth::convertUnspecifiedElementToRaw(this).(Raw::UnspecifiedElement).getProperty()
    }

    /**
     * Gets the index of this unspecified element, if it exists.
     */
    int getIndex() {
      result = Synth::convertUnspecifiedElementToRaw(this).(Raw::UnspecifiedElement).getIndex()
    }

    /**
     * Holds if `getIndex()` exists.
     */
    final predicate hasIndex() { exists(this.getIndex()) }

    /**
     * Gets the error of this unspecified element.
     */
    string getError() {
      result = Synth::convertUnspecifiedElementToRaw(this).(Raw::UnspecifiedElement).getError()
    }
  }
}
