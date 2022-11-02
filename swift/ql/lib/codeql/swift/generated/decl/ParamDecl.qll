// generated by codegen/codegen.py
private import codeql.swift.generated.Synth
private import codeql.swift.generated.Raw
import codeql.swift.elements.decl.VarDecl

module Generated {
  class ParamDecl extends Synth::TParamDecl, VarDecl {
    override string getAPrimaryQlClass() { result = "ParamDecl" }

    /**
     * Holds if this is an `inout` parameter.
     */
    predicate isInout() { Synth::convertParamDeclToRaw(this).(Raw::ParamDecl).isInout() }
  }
}
