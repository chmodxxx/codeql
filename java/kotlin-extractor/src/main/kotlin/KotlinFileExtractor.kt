package com.github.codeql

import com.semmle.extractor.java.OdasaOutput
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*

open class KotlinFileExtractor(
    override val logger: FileLogger,
    override val tw: FileTrapWriter,
    dependencyCollector: OdasaOutput.TrapFileManager?,
    externalClassExtractor: ExternalClassExtractor,
    primitiveTypeMapping: PrimitiveTypeMapping,
    pluginContext: IrPluginContext
): KotlinUsesExtractor(logger, tw, dependencyCollector, externalClassExtractor, primitiveTypeMapping, pluginContext) {

    fun extractDeclaration(declaration: IrDeclaration, parentId: Label<out DbReftype>) {
        when (declaration) {
            is IrClass -> extractClassSource(declaration)
            is IrFunction -> extractFunction(declaration, parentId)
            is IrAnonymousInitializer -> {
                // Leaving this intentionally empty. init blocks are extracted during class extraction.
            }
            is IrProperty -> extractProperty(declaration, parentId)
            is IrEnumEntry -> extractEnumEntry(declaration, parentId)
            is IrTypeAlias -> extractTypeAlias(declaration) // TODO: Pass in and use parentId
            else -> logger.warnElement(Severity.ErrorSevere, "Unrecognised IrDeclaration: " + declaration.javaClass, declaration)
        }
    }



    fun getLabel(element: IrElement) : String? {
        when (element) {
            is IrFile -> return "@\"${element.path};sourcefile\"" // todo: remove copy-pasted code
            is IrClass -> return getClassLabel(element, listOf()).classLabel
            is IrTypeParameter -> return getTypeParameterLabel(element)
            is IrFunction -> return getFunctionLabel(element)
            is IrValueParameter -> return getValueParameterLabel(element)
            is IrProperty -> return getPropertyLabel(element)
            is IrField -> return getFieldLabel(element)
            is IrEnumEntry -> return getEnumEntryLabel(element)

            // Fresh entities:
            is IrBody -> return null
            is IrExpression -> return null

            // todo add others:
            else -> {
                logger.warnElement(Severity.ErrorSevere, "Unhandled element type: ${element::class}", element)
                return null
            }
        }
    }

    fun extractTypeParameter(tp: IrTypeParameter): Label<out DbTypevariable> {
        val id = tw.getLabelFor<DbTypevariable>(getTypeParameterLabel(tp))

        val parentId: Label<out DbClassorinterfaceorcallable> = when (val parent = tp.parent) {
            is IrFunction -> useFunction(parent)
            is IrClass -> useClassSource(parent)
            else -> {
                logger.warnElement(Severity.ErrorSevere, "Unexpected type parameter parent", tp)
                fakeLabel()
            }
        }

        tw.writeTypeVars(id, tp.name.asString(), tp.index, 0, parentId)
        val locId = tw.getLocation(tp)
        tw.writeHasLocation(id, locId)

        // todo: add type bounds

        return id
    }

    fun extractClassInstance(c: IrClass, typeArgs: List<IrTypeArgument>): Label<out DbClassorinterface> {
        if (typeArgs.isEmpty()) {
            logger.warn(Severity.ErrorSevere, "Instance without type arguments: " + c.name.asString())
        }

        val results = addClassLabel(c, typeArgs)
        val id = results.id
        val pkg = c.packageFqName?.asString() ?: ""
        val cls = results.shortName
        val pkgId = extractPackage(pkg)
        if(c.kind == ClassKind.INTERFACE) {
            @Suppress("UNCHECKED_CAST")
            val interfaceId = id as Label<out DbInterface>
            @Suppress("UNCHECKED_CAST")
            val sourceInterfaceId = useClassSource(c) as Label<out DbInterface>
            tw.writeInterfaces(interfaceId, cls, pkgId, sourceInterfaceId)
        } else {
            @Suppress("UNCHECKED_CAST")
            val classId = id as Label<out DbClass>
            @Suppress("UNCHECKED_CAST")
            val sourceClassId = useClassSource(c) as Label<out DbClass>
            tw.writeClasses(classId, cls, pkgId, sourceClassId)

            if (c.kind == ClassKind.ENUM_CLASS) {
                tw.writeIsEnumType(classId)
            }
        }

        for ((idx, arg) in typeArgs.withIndex()) {
            val argId = getTypeArgumentLabel(arg).id
            tw.writeTypeArgs(argId, idx, id)
        }
        tw.writeIsParameterized(id)
        val unbound = useClassSource(c)
        tw.writeErasure(id, unbound)
        extractClassModifiers(c, id)
        extractClassSupertypes(c, id, typeArgs)

        val locId = tw.getLocation(c)
        tw.writeHasLocation(id, locId)

        return id
    }

    private fun extractAnonymousClassStmt(c: IrClass, callable: Label<out DbCallable>, parent: Label<out DbStmtparent>, idx: Int) {
        @Suppress("UNCHECKED_CAST")
        val id = extractClassSource(c) as Label<out DbClass>
        extractAnonymousClassStmt(id, c, callable, parent, idx)
    }

    private fun extractAnonymousClassStmt(id: Label<out DbClass>, locElement: IrElement, callable: Label<out DbCallable>, parent: Label<out DbStmtparent>, idx: Int) {
        // TODO: is this the same as @localtypedeclstmt
        val stmtId = tw.getFreshIdLabel<DbAnonymousclassdeclstmt>()
        tw.writeStmts_anonymousclassdeclstmt(stmtId, parent, idx, callable)
        tw.writeKtAnonymousClassDeclarationStmts(stmtId, id)
        val locId = tw.getLocation(locElement)
        tw.writeHasLocation(stmtId, locId)
    }

    fun extractClassSource(c: IrClass): Label<out DbClassorinterface> {
        val id = if (c.isAnonymousObject) {
            @Suppress("UNCHECKED_CAST")
            useAnonymousClass(c).javaResult.id as Label<out DbClass>
        } else {
            useClassSource(c)
        }
        val pkg = c.packageFqName?.asString() ?: ""
        val cls = if (c.isAnonymousObject) "" else c.name.asString()
        val pkgId = extractPackage(pkg)
        if(c.kind == ClassKind.INTERFACE) {
            @Suppress("UNCHECKED_CAST")
            val interfaceId = id as Label<out DbInterface>
            tw.writeInterfaces(interfaceId, cls, pkgId, interfaceId)
        } else {
            @Suppress("UNCHECKED_CAST")
            val classId = id as Label<out DbClass>
            tw.writeClasses(classId, cls, pkgId, classId)

            if (c.kind == ClassKind.ENUM_CLASS) {
                tw.writeIsEnumType(classId)
            }
        }

        val locId = tw.getLocation(c)
        tw.writeHasLocation(id, locId)

        var parent: IrDeclarationParent? = c.parent
        while (parent != null) {
            if (parent is IrClass) {
                val parentId =
                    if (parent.isAnonymousObject) {
                        @Suppress("UNCHECKED_CAST")
                        useAnonymousClass(c).javaResult.id as Label<out DbClass>
                    } else {
                        useClassInstance(parent, listOf()).typeResult.id
                    }
                tw.writeEnclInReftype(id, parentId)
                if(c.isCompanion) {
                    // If we are a companion then our parent has a
                    //     public static final ParentClass$CompanionObjectClass CompanionObjectName;
                    // that we need to fabricate here
                    val instance = useCompanionObjectClassInstance(c)
                    if(instance != null) {
                        val type = useSimpleTypeClass(c, emptyList(), false)
                        tw.writeFields(instance.id, instance.name, type.javaResult.id, type.kotlinResult.id, id, instance.id)
                        tw.writeHasLocation(instance.id, locId)
                        addModifiers(instance.id, "public", "static", "final")
                        @Suppress("UNCHECKED_CAST")
                        tw.writeClass_companion_object(parentId as Label<DbClass>, instance.id, id as Label<DbClass>)
                    }
                }

                break
            }

            parent = (parent as? IrDeclaration)?.parent
        }

        c.typeParameters.map { extractTypeParameter(it) }
        c.declarations.map { extractDeclaration(it, id) }
        extractObjectInitializerFunction(c, id)
        if(c.isNonCompanionObject) {
            // For `object MyObject { ... }`, the .class has an
            // automatically-generated `public static final MyObject INSTANCE`
            // field that may be referenced from Java code, and is used in our
            // IrGetObjectValue support. We therefore need to fabricate it
            // here.
            val instance = useObjectClassInstance(c)
            val type = useSimpleTypeClass(c, emptyList(), false)
            tw.writeFields(instance.id, instance.name, type.javaResult.id, type.kotlinResult.id, id, instance.id)
            tw.writeHasLocation(instance.id, locId)
            addModifiers(instance.id, "public", "static", "final")
            @Suppress("UNCHECKED_CAST")
            tw.writeClass_object(id as Label<DbClass>, instance.id)
        }

        extractClassModifiers(c, id)
        extractClassSupertypes(c, id)

        return id
    }

    data class FieldResult(val id: Label<DbField>, val name: String)

    fun useCompanionObjectClassInstance(c: IrClass): FieldResult? {
        val parent = c.parent
        if(!c.isCompanion) {
            logger.warn(Severity.ErrorSevere, "Using companion instance for non-companion class")
            return null
        }
        else if (parent !is IrClass) {
            logger.warn(Severity.ErrorSevere, "Using companion instance for non-companion class")
            return null
        } else {
            val parentId = useClassInstance(parent, listOf()).typeResult.id
            val instanceName = c.name.asString()
            val instanceLabel = "@\"field;{$parentId};$instanceName\""
            val instanceId: Label<DbField> = tw.getLabelFor(instanceLabel)
            return FieldResult(instanceId, instanceName)
        }
    }

    fun useObjectClassInstance(c: IrClass): FieldResult {
        if(!c.isNonCompanionObject) {
            logger.warn(Severity.ErrorSevere, "Using instance for non-object class")
        }
        val classId = useClassInstance(c, listOf()).typeResult.id
        val instanceName = "INSTANCE"
        val instanceLabel = "@\"field;{$classId};$instanceName\""
        val instanceId: Label<DbField> = tw.getLabelFor(instanceLabel)
        return FieldResult(instanceId, instanceName)
    }

    fun extractValueParameter(vp: IrValueParameter, parent: Label<out DbCallable>, idx: Int): TypeResults {
        val id = useValueParameter(vp)
        val type = useType(vp.type)
        val locId = tw.getLocation(vp)
        tw.writeParams(id, type.javaResult.id, type.kotlinResult.id, idx, parent, id)
        tw.writeHasLocation(id, locId)
        tw.writeParamName(id, vp.name.asString())
        return type
    }

    private fun extractObjectInitializerFunction(c: IrClass, parentId: Label<out DbReftype>) {
        if (isExternalDeclaration(c)) {
            return
        }

        // add method:
        val obinitLabel = getFunctionLabel(c, "<obinit>", listOf(), pluginContext.irBuiltIns.unitType, extensionReceiverParameter = null)
        val obinitId = tw.getLabelFor<DbMethod>(obinitLabel)
        val returnType = useType(pluginContext.irBuiltIns.unitType)
        tw.writeMethods(obinitId, "<obinit>", "<obinit>()", returnType.javaResult.id, returnType.kotlinResult.id, parentId, obinitId)

        val locId = tw.getLocation(c)
        tw.writeHasLocation(obinitId, locId)

        // add body:
        val blockId = tw.getFreshIdLabel<DbBlock>()
        tw.writeStmts_block(blockId, obinitId, 0, obinitId)
        tw.writeHasLocation(blockId, locId)

        // body content with field initializers and init blocks
        var idx = 0
        for (decl in c.declarations) {
            when (decl) {
                is IrProperty -> {
                    val backingField = decl.backingField
                    val initializer = backingField?.initializer

                    if (backingField == null || backingField.isStatic || initializer == null) {
                        continue
                    }

                    val declLocId = tw.getLocation(decl)
                    val stmtId = tw.getFreshIdLabel<DbExprstmt>()
                    tw.writeStmts_exprstmt(stmtId, blockId, idx++, obinitId)
                    tw.writeHasLocation(stmtId, declLocId)
                    val assignmentId = tw.getFreshIdLabel<DbAssignexpr>()
                    val type = useType(initializer.expression.type)
                    tw.writeExprs_assignexpr(assignmentId, type.javaResult.id, type.kotlinResult.id, stmtId, 0)
                    tw.writeHasLocation(assignmentId, declLocId)
                    tw.writeCallableEnclosingExpr(assignmentId, obinitId)
                    tw.writeStatementEnclosingExpr(assignmentId, stmtId)

                    val lhsId = tw.getFreshIdLabel<DbVaraccess>()
                    val lhsType = useType(backingField.type)
                    tw.writeExprs_varaccess(lhsId, lhsType.javaResult.id, lhsType.kotlinResult.id, assignmentId, 0)
                    tw.writeHasLocation(lhsId, declLocId)
                    tw.writeCallableEnclosingExpr(lhsId, obinitId)
                    tw.writeStatementEnclosingExpr(lhsId, stmtId)
                    val vId = useField(backingField)
                    tw.writeVariableBinding(lhsId, vId)

                    extractExpressionExpr(initializer.expression, obinitId, assignmentId, 1, stmtId)
                }
                is IrAnonymousInitializer -> {
                    if (decl.isStatic) {
                        continue
                    }

                    for (stmt in decl.body.statements) {
                        extractStatement(stmt, obinitId, blockId, idx++)
                    }
                }
                else -> continue
            }
        }
    }

    fun extractFunction(f: IrFunction, parentId: Label<out DbReftype>): Label<out DbCallable> {
        currentFunction = f

        f.typeParameters.map { extractTypeParameter(it) }

        val locId = tw.getLocation(f)

        val id =
            if (f.isLocalFunction())
                getLocalFunctionLabels(f).function
            else
                useFunction<DbCallable>(f)

        val extReceiver = f.extensionReceiverParameter
        val idxOffset = if (extReceiver != null) 1 else 0
        val paramTypes = f.valueParameters.mapIndexed { i, vp ->
            extractValueParameter(vp, id, i + idxOffset)
        }
        val allParamTypes = if (extReceiver != null) {
            val extendedType = useType(extReceiver.type)
            @Suppress("UNCHECKED_CAST")
            tw.writeKtExtensionFunctions(id as Label<DbMethod>, extendedType.javaResult.id, extendedType.kotlinResult.id)

            val t = extractValueParameter(extReceiver, id, 0)
            val l = mutableListOf(t)
            l.addAll(paramTypes)
            l
        } else {
            paramTypes
        }

        val paramsSignature = allParamTypes.joinToString(separator = ",", prefix = "(", postfix = ")") { it.javaResult.signature!! }

        if (f.symbol is IrConstructorSymbol) {
            val returnType = useType(erase(f.returnType), TypeContext.RETURN)
            val shortName = if (f.returnType.isAnonymous) "" else f.returnType.classFqName?.shortName()?.asString() ?: f.name.asString()
            @Suppress("UNCHECKED_CAST")
            tw.writeConstrs(id as Label<DbConstructor>, shortName, "$shortName$paramsSignature", returnType.javaResult.id, returnType.kotlinResult.id, parentId, id)
        } else {
            val returnType = useType(f.returnType, TypeContext.RETURN)
            val shortName = f.name.asString()
            @Suppress("UNCHECKED_CAST")
            tw.writeMethods(id as Label<DbMethod>, shortName, "$shortName$paramsSignature", returnType.javaResult.id, returnType.kotlinResult.id, parentId, id)
        }

        tw.writeHasLocation(id, locId)
        val body = f.body
        if(body != null) {
            extractBody(body, id)
        }

        currentFunction = null
        return id
    }

    fun extractField(f: IrField, parentId: Label<out DbReftype>): Label<out DbField> {
        val id = useField(f)
        val locId = tw.getLocation(f)
        val type = useType(f.type)
        tw.writeFields(id, f.name.asString(), type.javaResult.id, type.kotlinResult.id, parentId, id)
        tw.writeHasLocation(id, locId)
        return id
    }

    fun extractProperty(p: IrProperty, parentId: Label<out DbReftype>) {
        val id = useProperty(p)
        val locId = tw.getLocation(p)
        tw.writeKtProperties(id, p.name.asString())
        tw.writeHasLocation(id, locId)

        val bf = p.backingField
        val getter = p.getter
        val setter = p.setter

        if(getter != null) {
            @Suppress("UNCHECKED_CAST")
            val getterId = extractFunction(getter, parentId) as Label<out DbMethod>
            tw.writeKtPropertyGetters(id, getterId)
        } else {
            if (p.modality != Modality.FINAL || !isExternalDeclaration(p)) {
                logger.warnElement(Severity.ErrorSevere, "IrProperty without a getter", p)
            }
        }

        if(setter != null) {
            if(!p.isVar) {
                logger.warnElement(Severity.ErrorSevere, "!isVar property with a setter", p)
            }
            @Suppress("UNCHECKED_CAST")
            val setterId = extractFunction(setter, parentId) as Label<out DbMethod>
            tw.writeKtPropertySetters(id, setterId)
        } else {
            if (p.isVar && !isExternalDeclaration(p)) {
                logger.warnElement(Severity.ErrorSevere, "isVar property without a setter", p)
            }
        }

        if(bf != null) {
            val fieldId = extractField(bf, parentId)
            tw.writeKtPropertyBackingFields(id, fieldId)
        }
    }

    fun extractEnumEntry(ee: IrEnumEntry, parentId: Label<out DbReftype>) {
        val id = useEnumEntry(ee)
        val parent = ee.parent
        if(parent !is IrClass) {
            logger.warnElement(Severity.ErrorSevere, "Enum entry with unexpected parent: " + parent.javaClass, ee)
        } else if (parent.typeParameters.isNotEmpty()) {
            logger.warnElement(Severity.ErrorSevere, "Enum entry parent class has type parameters: " + parent.name, ee)
        } else {
            val type = useSimpleTypeClass(parent, emptyList(), false)
            tw.writeFields(id, ee.name.asString(), type.javaResult.id, type.kotlinResult.id, parentId, id)
            val locId = tw.getLocation(ee)
            tw.writeHasLocation(id, locId)
        }
    }

    fun extractTypeAlias(ta: IrTypeAlias) {
        if (ta.typeParameters.isNotEmpty()) {
            // TODO: Extract this information
            logger.warn(Severity.ErrorSevere, "Type alias type parameters ignored for " + ta.render())
        }
        val id = useTypeAlias(ta)
        val locId = tw.getLocation(ta)
        // TODO: We don't really want to generate any Java types here; we only want the KT type:
        val type = useType(ta.expandedType)
        tw.writeKt_type_alias(id, ta.name.asString(), type.kotlinResult.id)
        tw.writeHasLocation(id, locId)
    }

    fun extractBody(b: IrBody, callable: Label<out DbCallable>) {
        when(b) {
            is IrBlockBody -> extractBlockBody(b, callable)
            is IrSyntheticBody -> extractSyntheticBody(b, callable)
            else -> {
                logger.warnElement(Severity.ErrorSevere, "Unrecognised IrBody: " + b.javaClass, b)
            }
        }
    }

    fun extractBlockBody(b: IrBlockBody, callable: Label<out DbCallable>) {
        val id = tw.getFreshIdLabel<DbBlock>()
        val locId = tw.getLocation(b)
        tw.writeStmts_block(id, callable, 0, callable)
        tw.writeHasLocation(id, locId)
        for((sIdx, stmt) in b.statements.withIndex()) {
            extractStatement(stmt, callable, id, sIdx)
        }
    }

    fun extractSyntheticBody(b: IrSyntheticBody, callable: Label<out DbCallable>) {
        when (b.kind) {
            IrSyntheticBodyKind.ENUM_VALUES -> tw.writeKtSyntheticBody(callable, 1)
            IrSyntheticBodyKind.ENUM_VALUEOF -> tw.writeKtSyntheticBody(callable, 2)
        }
    }

    fun extractVariable(v: IrVariable, callable: Label<out DbCallable>, parent: Label<out DbStmtparent>, idx: Int) {
        val stmtId = tw.getFreshIdLabel<DbLocalvariabledeclstmt>()
        val locId = tw.getLocation(v)
        tw.writeStmts_localvariabledeclstmt(stmtId, parent, idx, callable)
        tw.writeHasLocation(stmtId, locId)
        extractVariableExpr(v, callable, stmtId, 1, stmtId)
    }

    fun extractVariableExpr(v: IrVariable, callable: Label<out DbCallable>, parent: Label<out DbExprparent>, idx: Int, enclosingStmt: Label<out DbStmt>) {
        val varId = useVariable(v)
        val exprId = tw.getFreshIdLabel<DbLocalvariabledeclexpr>()
        val locId = tw.getLocation(v)
        val type = useType(v.type)
        tw.writeLocalvars(varId, v.name.asString(), type.javaResult.id, type.kotlinResult.id, exprId)
        tw.writeHasLocation(varId, locId)
        tw.writeExprs_localvariabledeclexpr(exprId, type.javaResult.id, type.kotlinResult.id, parent, idx)
        tw.writeHasLocation(exprId, locId)
        tw.writeCallableEnclosingExpr(exprId, callable)
        tw.writeStatementEnclosingExpr(exprId, enclosingStmt)
        val i = v.initializer
        if(i != null) {
            extractExpressionExpr(i, callable, exprId, 0, enclosingStmt)
        }
    }

    fun extractStatement(s: IrStatement, callable: Label<out DbCallable>, parent: Label<out DbStmtparent>, idx: Int) {
        when(s) {
            is IrExpression -> {
                extractExpressionStmt(s, callable, parent, idx)
            }
            is IrVariable -> {
                extractVariable(s, callable, parent, idx)
            }
            is IrClass -> {
                if (s.isAnonymousObject) {
                    extractAnonymousClassStmt(s, callable, parent, idx)
                } else {
                    logger.warnElement(Severity.ErrorSevere, "Found non anonymous IrClass as IrStatement: " + s.javaClass, s)
                }
            }
            is IrFunction -> {
                if (s.isLocalFunction()) {
                    val classId =  extractGeneratedClass(s, listOf(pluginContext.irBuiltIns.anyType))
                    extractAnonymousClassStmt(classId, s, callable, parent, idx)
                } else {
                    logger.warnElement(Severity.ErrorSevere, "Expected to find local function", s)
                }
            }
            else -> {
                logger.warnElement(Severity.ErrorSevere, "Unrecognised IrStatement: " + s.javaClass, s)
            }
        }
    }

    private fun isBuiltinCallInternal(c: IrCall, fName: String) = isBuiltinCall(c, fName, "kotlin.internal.ir")
    private fun isBuiltinCallKotlin(c: IrCall, fName: String) = isBuiltinCall(c, fName, "kotlin")

    private fun isBuiltinCall(c: IrCall, fName: String, pName: String): Boolean {
        val verbose = false
        fun verboseln(s: String) { if(verbose) println(s) }
        verboseln("Attempting builtin match for $fName")
        val target = c.symbol.owner
        if (target.name.asString() != fName) {
            verboseln("No match as function name is ${target.name.asString()} not $fName")
            return false
        }
        val extensionReceiverParameter = target.extensionReceiverParameter
        // TODO: Are both branches of this `if` possible?:
        val targetPkg = if (extensionReceiverParameter == null) target.parent
                        else (extensionReceiverParameter.type as? IrSimpleType)?.classifier?.owner
        if (targetPkg !is IrPackageFragment) {
            verboseln("No match as didn't find target package")
            return false
        }
        if (targetPkg.fqName.asString() != pName) {
            verboseln("No match as package name is ${targetPkg.fqName.asString()}")
            return false
        }
        verboseln("Match")
        return true
    }

    private fun unaryOp(id: Label<out DbExpr>, c: IrCall, callable: Label<out DbCallable>, enclosingStmt: Label<out DbStmt>) {
        val locId = tw.getLocation(c)
        tw.writeHasLocation(id, locId)
        tw.writeCallableEnclosingExpr(id, callable)
        tw.writeStatementEnclosingExpr(id, enclosingStmt)

        val dr = c.dispatchReceiver
        if (dr != null) {
            logger.warnElement(Severity.ErrorSevere, "Unexpected dispatch receiver found", c)
        }

        if (c.valueArgumentsCount < 1) {
            logger.warnElement(Severity.ErrorSevere, "No arguments found", c)
            return
        }

        extractArgument(id, c, callable, enclosingStmt, 0, "Operand null")

        if (c.valueArgumentsCount > 1) {
            logger.warnElement(Severity.ErrorSevere, "Extra arguments found", c)
        }
    }

    private fun binOp(id: Label<out DbExpr>, c: IrCall, callable: Label<out DbCallable>, enclosingStmt: Label<out DbStmt>) {
        val locId = tw.getLocation(c)
        tw.writeHasLocation(id, locId)
        tw.writeCallableEnclosingExpr(id, callable)
        tw.writeStatementEnclosingExpr(id, enclosingStmt)

        val dr = c.dispatchReceiver
        if (dr != null) {
            logger.warnElement(Severity.ErrorSevere, "Unexpected dispatch receiver found", c)
        }

        if (c.valueArgumentsCount < 1) {
            logger.warnElement(Severity.ErrorSevere, "No arguments found", c)
            return
        }

        extractArgument(id, c, callable, enclosingStmt, 0, "LHS null")

        if (c.valueArgumentsCount < 2) {
            logger.warnElement(Severity.ErrorSevere, "No RHS found", c)
            return
        }

        extractArgument(id, c, callable, enclosingStmt, 1, "RHS null")

        if (c.valueArgumentsCount > 2) {
            logger.warnElement(Severity.ErrorSevere, "Extra arguments found", c)
        }
    }

    private fun extractArgument(id: Label<out DbExpr>, c: IrCall, callable: Label<out DbCallable>, enclosingStmt: Label<out DbStmt>, idx: Int, msg: String) {
        val op = c.getValueArgument(idx)
        if (op == null) {
            logger.warnElement(Severity.ErrorSevere, msg, c)
        } else {
            extractExpressionExpr(op, callable, id, idx, enclosingStmt)
        }
    }

    fun extractCall(c: IrCall, callable: Label<out DbCallable>, parent: Label<out DbExprparent>, idx: Int, enclosingStmt: Label<out DbStmt>) {
        fun isFunction(pkgName: String, className: String, fName: String, hasQuestionMark: Boolean = false): Boolean {
            val verbose = false
            fun verboseln(s: String) { if(verbose) println(s) }
            verboseln("Attempting match for $pkgName $className $fName")
            val target = c.symbol.owner
            if (target.name.asString() != fName) {
                verboseln("No match as function name is ${target.name.asString()} not $fName")
                return false
            }
            val extensionReceiverParameter = target.extensionReceiverParameter
            // TODO: Are both branches of this `if` possible?:
            val targetClass = if (extensionReceiverParameter == null) target.parent
                              else {
                                    val st = extensionReceiverParameter.type as? IrSimpleType
                                    if (st?.hasQuestionMark != hasQuestionMark) {
                                        verboseln("Nullablility of type didn't match")
                                        return false
                                    }
                                    st?.classifier?.owner
                              }
            if (targetClass !is IrClass) {
                verboseln("No match as didn't find target class")
                return false
            }
            if (targetClass.name.asString() != className) {
                verboseln("No match as class name is ${targetClass.name.asString()} not $className")
                return false
            }
            val targetPkg = targetClass.parent
            if (targetPkg !is IrPackageFragment) {
                verboseln("No match as didn't find target package")
                return false
            }
            if (targetPkg.fqName.asString() != pkgName) {
                verboseln("No match as package name is ${targetPkg.fqName.asString()} not $pkgName")
                return false
            }
            verboseln("Match")
            return true
        }

        fun isNumericFunction(fName: String): Boolean {
            return isFunction("kotlin", "Int", fName) ||
                   isFunction("kotlin", "Byte", fName) ||
                   isFunction("kotlin", "Short", fName) ||
                   isFunction("kotlin", "Long", fName) ||
                   isFunction("kotlin", "Float", fName) ||
                   isFunction("kotlin", "Double", fName)
        }

        fun extractMethodAccess(callTarget: IrFunction, extractTypeArguments: Boolean = true){
            val id = tw.getFreshIdLabel<DbMethodaccess>()
            val type = useType(c.type)
            val locId = tw.getLocation(c)

            tw.writeExprs_methodaccess(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
            tw.writeHasLocation(id, locId)
            tw.writeCallableEnclosingExpr(id, callable)
            tw.writeStatementEnclosingExpr(id, enclosingStmt)

            if (extractTypeArguments) {
                // type arguments at index -2, -3, ...
                extractTypeArguments(c, id, callable, enclosingStmt, -2, true)
            }

            if (callTarget.isLocalFunction()) {
                val ids = getLocalFunctionLabels(callTarget)

                val methodId = ids.function
                tw.writeCallableBinding(id, methodId)

                val idNewexpr = tw.getFreshIdLabel<DbNewexpr>()
                tw.writeExprs_newexpr(idNewexpr, ids.type.javaResult.id, ids.type.kotlinResult.id, id, -1)
                tw.writeHasLocation(idNewexpr, locId)
                tw.writeCallableEnclosingExpr(idNewexpr, callable)
                tw.writeStatementEnclosingExpr(idNewexpr, enclosingStmt)
                tw.writeCallableBinding(idNewexpr, ids.constructor)

            } else {
                val methodId = useFunction<DbMethod>(callTarget)
                tw.writeCallableBinding(id, methodId)

                val dr = c.dispatchReceiver
                if (dr != null) {
                    extractExpressionExpr(dr, callable, id, -1, enclosingStmt)
                }
            }

            val er = c.extensionReceiver
            val idxOffset: Int
            if (er != null) {
                extractExpressionExpr(er, callable, id, 0, enclosingStmt)
                idxOffset = 1
            } else {
                idxOffset = 0
            }

            for(i in 0 until c.valueArgumentsCount) {
                val arg = c.getValueArgument(i)
                if(arg != null) {
                    extractExpressionExpr(arg, callable, id, i + idxOffset, enclosingStmt)
                }
            }
        }

        fun extractSpecialEnumFunction(fnName: String){
            if (c.typeArgumentsCount != 1) {
                logger.warnElement(Severity.ErrorSevere, "Expected to find exactly one type argument", c)
                return
            }

            val func = ((c.getTypeArgument(0) as? IrSimpleType)?.classifier?.owner as? IrClass)?.declarations?.find { it is IrFunction && it.name.asString() == fnName }
            if (func == null) {
                logger.warnElement(Severity.ErrorSevere, "Couldn't find function $fnName on enum type", c)
                return
            }

            extractMethodAccess(func as IrFunction, false)
        }

        fun binopDisp(id: Label<out DbExpr>) {
            val locId = tw.getLocation(c)
            tw.writeHasLocation(id, locId)
            tw.writeCallableEnclosingExpr(id, callable)
            tw.writeStatementEnclosingExpr(id, enclosingStmt)

            val dr = c.dispatchReceiver
            if(dr == null) {
                logger.warnElement(Severity.ErrorSevere, "Dispatch receiver not found", c)
            } else {
                extractExpressionExpr(dr, callable, id, 0, enclosingStmt)
            }
            if(c.valueArgumentsCount < 1) {
                logger.warnElement(Severity.ErrorSevere, "No RHS found", c)
            } else {
                if(c.valueArgumentsCount > 1) {
                    logger.warnElement(Severity.ErrorSevere, "Extra arguments found", c)
                }
                val arg = c.getValueArgument(0)
                if(arg == null) {
                    logger.warnElement(Severity.ErrorSevere, "RHS null", c)
                } else {
                    extractExpressionExpr(arg, callable, id, 1, enclosingStmt)
                }
            }
        }

        val dr = c.dispatchReceiver
        when {
            c.origin == IrStatementOrigin.PLUS &&
            (isNumericFunction("plus")
                    || isFunction("kotlin", "String", "plus")) -> {
                val id = tw.getFreshIdLabel<DbAddexpr>()
                val type = useType(c.type)
                tw.writeExprs_addexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binopDisp(id)
            }
            isFunction("kotlin", "String", "plus", true) -> {
                // TODO: this is not correct. `a + b` becomes `(a?:"\"null\"") + (b?:"\"null\"")`.
                val func = pluginContext.irBuiltIns.stringType.classOrNull?.owner?.declarations?.find { it is IrFunction && it.name.asString() == "plus" }
                if (func == null) {
                    logger.warnElement(Severity.ErrorSevere, "Couldn't find plus function on string type", c)
                    return
                }
                extractMethodAccess(func as IrFunction)
            }
            c.origin == IrStatementOrigin.MINUS && isNumericFunction("minus") -> {
                val id = tw.getFreshIdLabel<DbSubexpr>()
                val type = useType(c.type)
                tw.writeExprs_subexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binopDisp(id)
            }
            c.origin == IrStatementOrigin.DIV && isNumericFunction("div") -> {
                val id = tw.getFreshIdLabel<DbDivexpr>()
                val type = useType(c.type)
                tw.writeExprs_divexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binopDisp(id)
            }
            c.origin == IrStatementOrigin.PERC && isNumericFunction("rem") -> {
                val id = tw.getFreshIdLabel<DbRemexpr>()
                val type = useType(c.type)
                tw.writeExprs_remexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binopDisp(id)
            }
            // != gets desugared into not and ==. Here we resugar it.
            // TODO: This is wrong. Kotlin `a == b` is `a?.equals(b) ?: (b === null)`
            c.origin == IrStatementOrigin.EXCLEQ && isFunction("kotlin", "Boolean", "not") && c.valueArgumentsCount == 0 && dr != null && dr is IrCall && isBuiltinCallInternal(dr, "EQEQ") -> {
                val id = tw.getFreshIdLabel<DbNeexpr>()
                val type = useType(c.type)
                tw.writeExprs_neexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binOp(id, dr, callable, enclosingStmt)
            }
            c.origin == IrStatementOrigin.EXCLEQEQ && isFunction("kotlin", "Boolean", "not") && c.valueArgumentsCount == 0 && dr != null && dr is IrCall && isBuiltinCallInternal(dr, "EQEQEQ") -> {
                val id = tw.getFreshIdLabel<DbNeexpr>()
                val type = useType(c.type)
                tw.writeExprs_neexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binOp(id, dr, callable, enclosingStmt)
            }
            c.origin == IrStatementOrigin.EXCLEQ && isFunction("kotlin", "Boolean", "not") && c.valueArgumentsCount == 0 && dr != null && dr is IrCall && isBuiltinCallInternal(dr, "ieee754equals") -> {
                val id = tw.getFreshIdLabel<DbNeexpr>()
                val type = useType(c.type)
                // TODO: Is this consistent with Java?
                tw.writeExprs_neexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binOp(id, dr, callable, enclosingStmt)
            }
            // We need to handle all the builtin operators defines in BuiltInOperatorNames in
            //     compiler/ir/ir.tree/src/org/jetbrains/kotlin/ir/IrBuiltIns.kt
            // as they can't be extracted as external dependencies.
            isBuiltinCallInternal(c, "less") -> {
                if(c.origin != IrStatementOrigin.LT) {
                    logger.warnElement(Severity.ErrorSevere, "Unexpected origin for LT: ${c.origin}", c)
                }
                val id = tw.getFreshIdLabel<DbLtexpr>()
                val type = useType(c.type)
                tw.writeExprs_ltexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binOp(id, c, callable, enclosingStmt)
            }
            isBuiltinCallInternal(c, "lessOrEqual") -> {
                if(c.origin != IrStatementOrigin.LTEQ) {
                    logger.warnElement(Severity.ErrorSevere, "Unexpected origin for LTEQ: ${c.origin}", c)
                }
                val id = tw.getFreshIdLabel<DbLeexpr>()
                val type = useType(c.type)
                tw.writeExprs_leexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binOp(id, c, callable, enclosingStmt)
            }
            isBuiltinCallInternal(c, "greater") -> {
                if(c.origin != IrStatementOrigin.GT) {
                    logger.warnElement(Severity.ErrorSevere, "Unexpected origin for GT: ${c.origin}", c)
                }
                val id = tw.getFreshIdLabel<DbGtexpr>()
                val type = useType(c.type)
                tw.writeExprs_gtexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binOp(id, c, callable, enclosingStmt)
            }
            isBuiltinCallInternal(c, "greaterOrEqual") -> {
                if(c.origin != IrStatementOrigin.GTEQ) {
                    logger.warnElement(Severity.ErrorSevere, "Unexpected origin for GTEQ: ${c.origin}", c)
                }
                val id = tw.getFreshIdLabel<DbGeexpr>()
                val type = useType(c.type)
                tw.writeExprs_geexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binOp(id, c, callable, enclosingStmt)
            }
            isBuiltinCallInternal(c, "EQEQ") -> {
                if(c.origin != IrStatementOrigin.EQEQ) {
                    logger.warnElement(Severity.ErrorSevere, "Unexpected origin for EQEQ: ${c.origin}", c)
                }
                // TODO: This is wrong. Kotlin `a == b` is `a?.equals(b) ?: (b === null)`
                val id = tw.getFreshIdLabel<DbEqexpr>()
                val type = useType(c.type)
                tw.writeExprs_eqexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binOp(id, c, callable, enclosingStmt)
            }
            isBuiltinCallInternal(c, "EQEQEQ") -> {
                if(c.origin != IrStatementOrigin.EQEQEQ) {
                    logger.warnElement(Severity.ErrorSevere, "Unexpected origin for EQEQEQ: ${c.origin}", c)
                }
                val id = tw.getFreshIdLabel<DbEqexpr>()
                val type = useType(c.type)
                tw.writeExprs_eqexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binOp(id, c, callable, enclosingStmt)
            }
            isBuiltinCallInternal(c, "ieee754equals") -> {
                if(c.origin != IrStatementOrigin.EQEQ) {
                    logger.warnElement(Severity.ErrorSevere, "Unexpected origin for ieee754equals: ${c.origin}", c)
                }
                // TODO: Is this consistent with Java?
                val id = tw.getFreshIdLabel<DbEqexpr>()
                val type = useType(c.type)
                tw.writeExprs_eqexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                binOp(id, c, callable, enclosingStmt)
            }
            isBuiltinCallInternal(c, "CHECK_NOT_NULL") -> {
                if(c.origin != IrStatementOrigin.EXCLEXCL) {
                    logger.warnElement(Severity.ErrorSevere, "Unexpected origin for CHECK_NOT_NULL: ${c.origin}", c)
                }

                val id = tw.getFreshIdLabel<DbNotnullexpr>()
                val type = useType(c.type)
                tw.writeExprs_notnullexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                unaryOp(id, c, callable, enclosingStmt)
            }
            isBuiltinCallInternal(c, "THROW_CCE") -> {
                // TODO
                logger.warnElement(Severity.ErrorSevere, "Unhandled builtin", c)
            }
            isBuiltinCallInternal(c, "THROW_ISE") -> {
                // TODO
                logger.warnElement(Severity.ErrorSevere, "Unhandled builtin", c)
            }
            isBuiltinCallInternal(c, "noWhenBranchMatchedException") -> {
                // TODO
                logger.warnElement(Severity.ErrorSevere, "Unhandled builtin", c)
            }
            isBuiltinCallInternal(c, "illegalArgumentException") -> {
                // TODO
                logger.warnElement(Severity.ErrorSevere, "Unhandled builtin", c)
            }
            isBuiltinCallInternal(c, "ANDAND") -> {
                // TODO
                logger.warnElement(Severity.ErrorSevere, "Unhandled builtin", c)
            }
            isBuiltinCallInternal(c, "OROR") -> {
                // TODO
                logger.warnElement(Severity.ErrorSevere, "Unhandled builtin", c)
            }
            isFunction("kotlin", "Any", "toString", true) -> {
                // TODO: this is not correct. `a.toString()` becomes `(a?:"\"null\"").toString()`
                val func = pluginContext.irBuiltIns.anyType.classOrNull?.owner?.declarations?.find { it is IrFunction && it.name.asString() == "toString" }
                if (func == null) {
                    logger.warnElement(Severity.ErrorSevere, "Couldn't find toString function", c)
                    return
                }
                extractMethodAccess(func as IrFunction)
            }
            isBuiltinCallKotlin(c, "enumValues") -> {
                extractSpecialEnumFunction("values")
            }
            isBuiltinCallKotlin(c, "enumValueOf") -> {
                extractSpecialEnumFunction("valueOf")
            }
            isBuiltinCallKotlin(c, "arrayOfNulls") -> {
                val id = tw.getFreshIdLabel<DbArraycreationexpr>()
                val type = useType(c.type)
                tw.writeExprs_arraycreationexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                val locId = tw.getLocation(c)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)

                if (c.typeArgumentsCount == 1) {
                    extractTypeArguments(c, id, callable, enclosingStmt, -1)
                } else {
                    logger.warnElement(Severity.ErrorSevere, "Expected to find exactly one type argument in an arrayOfNulls call", c)
                }

                if (c.valueArgumentsCount == 1) {
                    val dim = c.getValueArgument(0)
                    if (dim != null) {
                        extractExpressionExpr(dim, callable, id, 0, enclosingStmt)
                    } else {
                        logger.warnElement(Severity.ErrorSevere, "Expected to find non-null argument in an arrayOfNulls call", c)
                    }
                } else {
                    logger.warnElement(Severity.ErrorSevere, "Expected to find only one argument in an arrayOfNulls call", c)
                }
            }
            isBuiltinCallKotlin(c, "arrayOf")
                    || isBuiltinCallKotlin(c, "doubleArrayOf")
                    || isBuiltinCallKotlin(c, "floatArrayOf")
                    || isBuiltinCallKotlin(c, "longArrayOf")
                    || isBuiltinCallKotlin(c, "intArrayOf")
                    || isBuiltinCallKotlin(c, "charArrayOf")
                    || isBuiltinCallKotlin(c, "shortArrayOf")
                    || isBuiltinCallKotlin(c, "byteArrayOf")
                    || isBuiltinCallKotlin(c, "booleanArrayOf") -> {
                val id = tw.getFreshIdLabel<DbArraycreationexpr>()
                val type = useType(c.type)
                tw.writeExprs_arraycreationexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                val locId = tw.getLocation(c)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)

                if (isBuiltinCallKotlin(c, "arrayOf")) {
                    if (c.typeArgumentsCount == 1) {
                        extractTypeArguments(c, id, callable, enclosingStmt,-1)
                    } else {
                        logger.warnElement( Severity.ErrorSevere, "Expected to find one type argument in arrayOf call", c )
                    }
                } else {
                    val argId = tw.getFreshIdLabel<DbUnannotatedtypeaccess>()
                    val elementType = c.type.getArrayElementType(pluginContext.irBuiltIns)
                    val elementTypeResult = useType(elementType)
                    tw.writeExprs_unannotatedtypeaccess(argId, elementTypeResult.javaResult.id, elementTypeResult.kotlinResult.id, id, -1)
                    tw.writeCallableEnclosingExpr(argId, callable)
                    tw.writeStatementEnclosingExpr(argId, enclosingStmt)
                }

                if (c.valueArgumentsCount == 1) {
                    val vararg = c.getValueArgument(0)
                    if (vararg is IrVararg) {
                        val initId = tw.getFreshIdLabel<DbArrayinit>()
                        tw.writeExprs_arrayinit(initId, type.javaResult.id, type.kotlinResult.id, id, -2)
                        tw.writeHasLocation(initId, locId)
                        tw.writeCallableEnclosingExpr(initId, callable)
                        tw.writeStatementEnclosingExpr(initId, enclosingStmt)
                        vararg.elements.forEachIndexed { i, arg -> extractVarargElement(arg, callable, initId, i, enclosingStmt) }

                        val dim = vararg.elements.size
                        val dimId = tw.getFreshIdLabel<DbIntegerliteral>()
                        val dimType = useType(pluginContext.irBuiltIns.intType)
                        tw.writeExprs_integerliteral(dimId, dimType.javaResult.id, dimType.kotlinResult.id, id, 0)
                        tw.writeHasLocation(dimId, locId)
                        tw.writeCallableEnclosingExpr(dimId, callable)
                        tw.writeStatementEnclosingExpr(dimId, enclosingStmt)
                        tw.writeNamestrings(dim.toString(), dim.toString(), dimId)
                    } else {
                        logger.warnElement(Severity.ErrorSevere, "Expected to find vararg argument in ${c.symbol.owner.name.asString()} call", c)
                    }
                } else {
                    logger.warnElement(Severity.ErrorSevere, "Expected to find only one (vararg) argument in ${c.symbol.owner.name.asString()} call", c)
                }
            }
            else -> {
                extractMethodAccess(c.symbol.owner)
            }
        }
    }

    private fun extractTypeArguments(
        c: IrFunctionAccessExpression,
        id: Label<out DbExprparent>,
        callable: Label<out DbCallable>,
        enclosingStmt: Label<out DbStmt>,
        startIndex: Int = 0,
        reverse: Boolean = false
    ) {
        for (argIdx in 0 until c.typeArgumentsCount) {
            val arg = c.getTypeArgument(argIdx)!!
            val argType = useType(arg, TypeContext.GENERIC_ARGUMENT)
            val argId = tw.getFreshIdLabel<DbUnannotatedtypeaccess>()
            val mul = if (reverse) -1 else 1
            tw.writeExprs_unannotatedtypeaccess(argId, argType.javaResult.id, argType.kotlinResult.id, id, argIdx * mul + startIndex)
            tw.writeCallableEnclosingExpr(argId, callable)
            tw.writeStatementEnclosingExpr(argId, enclosingStmt)
        }
    }

    private fun extractConstructorCall(
        e: IrFunctionAccessExpression,
        parent: Label<out DbExprparent>,
        idx: Int,
        callable: Label<out DbCallable>,
        enclosingStmt: Label<out DbStmt>
    ) {
        val id = tw.getFreshIdLabel<DbNewexpr>()
        val type: TypeResults
        val isAnonymous = e.type.isAnonymous
        if (isAnonymous) {
            if (e.typeArgumentsCount > 0) {
                logger.warn("Unexpected type arguments for anonymous class constructor call")
            }

            val c = (e.type as IrSimpleType).classifier.owner as IrClass

            type = useAnonymousClass(c)

            @Suppress("UNCHECKED_CAST")
            tw.writeIsAnonymClass(type.javaResult.id as Label<DbClass>, id)
        } else {
            type = useType(e.type)
        }
        val locId = tw.getLocation(e)
        val methodId = useFunction<DbConstructor>(e.symbol.owner)
        tw.writeExprs_newexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
        tw.writeHasLocation(id, locId)
        tw.writeCallableEnclosingExpr(id, callable)
        tw.writeStatementEnclosingExpr(id, enclosingStmt)
        tw.writeCallableBinding(id, methodId)
        for (i in 0 until e.valueArgumentsCount) {
            val arg = e.getValueArgument(i)
            if (arg != null) {
                extractExpressionExpr(arg, callable, id, i, enclosingStmt)
            }
        }
        val dr = e.dispatchReceiver
        if (dr != null) {
            extractExpressionExpr(dr, callable, id, -2, enclosingStmt)
        }

        val typeAccessType = if (isAnonymous) {
            val c = (e.type as IrSimpleType).classifier.owner as IrClass
            if (c.superTypes.size == 1) {
                useType(c.superTypes.first())
            } else {
                useType(pluginContext.irBuiltIns.anyType)
            }
        } else {
            type
        }

        val typeAccessId = tw.getFreshIdLabel<DbUnannotatedtypeaccess>()
        tw.writeExprs_unannotatedtypeaccess(typeAccessId, typeAccessType.javaResult.id, typeAccessType.kotlinResult.id, id, -3)
        tw.writeCallableEnclosingExpr(typeAccessId, callable)
        tw.writeStatementEnclosingExpr(typeAccessId, enclosingStmt)

        if (e.typeArgumentsCount > 0) {
            extractTypeArguments(e, typeAccessId, callable, enclosingStmt)
        }
    }

    private val loopIdMap: MutableMap<IrLoop, Label<out DbKtloopstmt>> = mutableMapOf()

    private var currentFunction: IrFunction? = null

    abstract inner class StmtExprParent {
        abstract fun stmt(e: IrExpression, callable: Label<out DbCallable>): StmtParent
        abstract fun expr(e: IrExpression, callable: Label<out DbCallable>): ExprParent
    }

    inner class StmtParent(val parent: Label<out DbStmtparent>, val idx: Int): StmtExprParent() {
        override fun stmt(e: IrExpression, callable: Label<out DbCallable>): StmtParent {
            return this
        }
        override fun expr(e: IrExpression, callable: Label<out DbCallable>): ExprParent {
            val id = tw.getFreshIdLabel<DbExprstmt>()
            val locId = tw.getLocation(e)
            tw.writeStmts_exprstmt(id, parent, idx, callable)
            tw.writeHasLocation(id, locId)
            return ExprParent(id, 0, id)
        }
    }
    inner class ExprParent(val parent: Label<out DbExprparent>, val idx: Int, val enclosingStmt: Label<out DbStmt>): StmtExprParent() {
        override fun stmt(e: IrExpression, callable: Label<out DbCallable>): StmtParent {
            val id = tw.getFreshIdLabel<DbStmtexpr>()
            val type = useType(e.type)
            val locId = tw.getLocation(e)
            tw.writeExprs_stmtexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
            tw.writeHasLocation(id, locId)
            tw.writeCallableEnclosingExpr(id, callable)
            tw.writeStatementEnclosingExpr(id, enclosingStmt)
            return StmtParent(id, 0)
        }
        override fun expr(e: IrExpression, callable: Label<out DbCallable>): ExprParent {
            return this
        }
    }

    fun extractExpressionStmt(e: IrExpression, callable: Label<out DbCallable>, parent: Label<out DbStmtparent>, idx: Int) {
        extractExpression(e, callable, StmtParent(parent, idx))
    }

    fun extractExpressionExpr(e: IrExpression, callable: Label<out DbCallable>, parent: Label<out DbExprparent>, idx: Int, enclosingStmt: Label<out DbStmt>) {
        extractExpression(e, callable, ExprParent(parent, idx, enclosingStmt))
    }

    fun extractExpression(e: IrExpression, callable: Label<out DbCallable>, parent: StmtExprParent) {
        when(e) {
            is IrDelegatingConstructorCall -> {
                val stmtParent = parent.stmt(e, callable)

                val irCallable = currentFunction
                if (irCallable == null) {
                    logger.warnElement(Severity.ErrorSevere, "Current function is not set", e)
                    return
                }

                val delegatingClass = e.symbol.owner.parent as IrClass
                val currentClass = irCallable.parent as IrClass

                val id: Label<out DbStmt>
                if (delegatingClass != currentClass) {
                    id = tw.getFreshIdLabel<DbSuperconstructorinvocationstmt>()
                    tw.writeStmts_superconstructorinvocationstmt(id, stmtParent.parent, stmtParent.idx, callable)
                } else {
                    id = tw.getFreshIdLabel<DbConstructorinvocationstmt>()
                    tw.writeStmts_constructorinvocationstmt(id, stmtParent.parent, stmtParent.idx, callable)
                }

                val locId = tw.getLocation(e)
                val methodId = useFunction<DbConstructor>(e.symbol.owner)

                tw.writeHasLocation(id, locId)
                @Suppress("UNCHECKED_CAST")
                tw.writeCallableBinding(id as Label<DbCaller>, methodId)
                for (i in 0 until e.valueArgumentsCount) {
                    val arg = e.getValueArgument(i)
                    if (arg != null) {
                        extractExpressionExpr(arg, callable, id, i, id)
                    }
                }
                val dr = e.dispatchReceiver
                if (dr != null) {
                    extractExpressionExpr(dr, callable, id, -1, id)
                }

                // todo: type arguments at index -2, -3, ...
            }
            is IrThrow -> {
                val stmtParent = parent.stmt(e, callable)
                val id = tw.getFreshIdLabel<DbThrowstmt>()
                val locId = tw.getLocation(e)
                tw.writeStmts_throwstmt(id, stmtParent.parent, stmtParent.idx, callable)
                tw.writeHasLocation(id, locId)
                extractExpressionExpr(e.value, callable, id, 0, id)
            }
            is IrBreak -> {
                val stmtParent = parent.stmt(e, callable)
                val id = tw.getFreshIdLabel<DbBreakstmt>()
                tw.writeStmts_breakstmt(id, stmtParent.parent, stmtParent.idx, callable)
                extractBreakContinue(e, id)
            }
            is IrContinue -> {
                val stmtParent = parent.stmt(e, callable)
                val id = tw.getFreshIdLabel<DbContinuestmt>()
                tw.writeStmts_continuestmt(id, stmtParent.parent, stmtParent.idx, callable)
                extractBreakContinue(e, id)
            }
            is IrReturn -> {
                val stmtParent = parent.stmt(e, callable)
                val id = tw.getFreshIdLabel<DbReturnstmt>()
                val locId = tw.getLocation(e)
                tw.writeStmts_returnstmt(id, stmtParent.parent, stmtParent.idx, callable)
                tw.writeHasLocation(id, locId)
                extractExpressionExpr(e.value, callable, id, 0, id)
            }
            is IrTry -> {
                val stmtParent = parent.stmt(e, callable)
                val id = tw.getFreshIdLabel<DbTrystmt>()
                val locId = tw.getLocation(e)
                tw.writeStmts_trystmt(id, stmtParent.parent, stmtParent.idx, callable)
                tw.writeHasLocation(id, locId)
                extractExpressionStmt(e.tryResult, callable, id, -1)
                val finallyStmt = e.finallyExpression
                if(finallyStmt != null) {
                    extractExpressionStmt(finallyStmt, callable, id, -2)
                }
                for((catchIdx, catchClause) in e.catches.withIndex()) {
                    val catchId = tw.getFreshIdLabel<DbCatchclause>()
                    tw.writeStmts_catchclause(catchId, id, catchIdx, callable)
                    val catchLocId = tw.getLocation(catchClause)
                    tw.writeHasLocation(catchId, catchLocId)
                    extractTypeAccess(catchClause.catchParameter.type, callable, catchId, -1, catchClause.catchParameter, catchId)
                    extractVariableExpr(catchClause.catchParameter, callable, catchId, 0, catchId)
                    extractExpressionStmt(catchClause.result, callable, catchId, 1)
                }
            }
            is IrContainerExpression -> {
                val stmtParent = parent.stmt(e, callable)
                val id = tw.getFreshIdLabel<DbBlock>()
                val locId = tw.getLocation(e)
                tw.writeStmts_block(id, stmtParent.parent, stmtParent.idx, callable)
                tw.writeHasLocation(id, locId)
                e.statements.forEachIndexed { i, s ->
                    extractStatement(s, callable, id, i)
                }
            }
            is IrWhileLoop -> {
                val stmtParent = parent.stmt(e, callable)
                val id = tw.getFreshIdLabel<DbWhilestmt>()
                loopIdMap[e] = id
                val locId = tw.getLocation(e)
                tw.writeStmts_whilestmt(id, stmtParent.parent, stmtParent.idx, callable)
                tw.writeHasLocation(id, locId)
                extractExpressionExpr(e.condition, callable, id, 0, id)
                val body = e.body
                if(body != null) {
                    extractExpressionStmt(body, callable, id, 1)
                }
                loopIdMap.remove(e)
            }
            is IrDoWhileLoop -> {
                val stmtParent = parent.stmt(e, callable)
                val id = tw.getFreshIdLabel<DbDostmt>()
                loopIdMap[e] = id
                val locId = tw.getLocation(e)
                tw.writeStmts_dostmt(id, stmtParent.parent, stmtParent.idx, callable)
                tw.writeHasLocation(id, locId)
                extractExpressionExpr(e.condition, callable, id, 0, id)
                val body = e.body
                if(body != null) {
                    extractExpressionStmt(body, callable, id, 1)
                }
                loopIdMap.remove(e)
            }
            is IrInstanceInitializerCall -> {
                val exprParent = parent.expr(e, callable)
                val irCallable = currentFunction
                if (irCallable == null) {
                    logger.warnElement(Severity.ErrorSevere, "Current function is not set", e)
                    return
                }

                if (irCallable is IrConstructor && irCallable.isPrimary) {
                    // Todo add parameter to field assignments
                }

                // Add call to <obinit>:
                val id = tw.getFreshIdLabel<DbMethodaccess>()
                val type = useType(e.type)
                val locId = tw.getLocation(e)
                val methodLabel = getFunctionLabel(irCallable.parent, "<obinit>", listOf(), e.type, null)
                val methodId = tw.getLabelFor<DbMethod>(methodLabel)
                tw.writeExprs_methodaccess(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                tw.writeCallableBinding(id, methodId)
            }
            is IrConstructorCall -> {
                val exprParent = parent.expr(e, callable)
                extractConstructorCall(e, exprParent.parent, exprParent.idx, callable, exprParent.enclosingStmt)
            }
            is IrEnumConstructorCall -> {
                val exprParent = parent.expr(e, callable)
                extractConstructorCall(e, exprParent.parent, exprParent.idx, callable, exprParent.enclosingStmt)
            }
            is IrCall -> {
                val exprParent = parent.expr(e, callable)
                extractCall(e, callable, exprParent.parent, exprParent.idx, exprParent.enclosingStmt)
            }
            is IrStringConcatenation -> {
                val exprParent = parent.expr(e, callable)
                val id = tw.getFreshIdLabel<DbStringtemplateexpr>()
                val type = useType(e.type)
                val locId = tw.getLocation(e)
                tw.writeExprs_stringtemplateexpr(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                e.arguments.forEachIndexed { i, a ->
                    extractExpressionExpr(a, callable, id, i, exprParent.enclosingStmt)
                }
            }
            is IrConst<*> -> {
                val exprParent = parent.expr(e, callable)
                when(val v = e.value) {
                    is Int, is Short, is Byte -> {
                        val id = tw.getFreshIdLabel<DbIntegerliteral>()
                        val type = useType(e.type)
                        val locId = tw.getLocation(e)
                        tw.writeExprs_integerliteral(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                        tw.writeHasLocation(id, locId)
                        tw.writeCallableEnclosingExpr(id, callable)
                        tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                        tw.writeNamestrings(v.toString(), v.toString(), id)
                    } is Long -> {
                        val id = tw.getFreshIdLabel<DbLongliteral>()
                        val type = useType(e.type)
                        val locId = tw.getLocation(e)
                        tw.writeExprs_longliteral(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                        tw.writeHasLocation(id, locId)
                        tw.writeCallableEnclosingExpr(id, callable)
                        tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                        tw.writeNamestrings(v.toString(), v.toString(), id)
                    } is Float -> {
                        val id = tw.getFreshIdLabel<DbFloatingpointliteral>()
                        val type = useType(e.type)
                        val locId = tw.getLocation(e)
                        tw.writeExprs_floatingpointliteral(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                        tw.writeHasLocation(id, locId)
                        tw.writeCallableEnclosingExpr(id, callable)
                        tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                        tw.writeNamestrings(v.toString(), v.toString(), id)
                    } is Double -> {
                        val id = tw.getFreshIdLabel<DbDoubleliteral>()
                        val type = useType(e.type)
                        val locId = tw.getLocation(e)
                        tw.writeExprs_doubleliteral(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                        tw.writeHasLocation(id, locId)
                        tw.writeCallableEnclosingExpr(id, callable)
                        tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                        tw.writeNamestrings(v.toString(), v.toString(), id)
                    } is Boolean -> {
                        val id = tw.getFreshIdLabel<DbBooleanliteral>()
                        val type = useType(e.type)
                        val locId = tw.getLocation(e)
                        tw.writeExprs_booleanliteral(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                        tw.writeHasLocation(id, locId)
                        tw.writeCallableEnclosingExpr(id, callable)
                        tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                        tw.writeNamestrings(v.toString(), v.toString(), id)
                    } is Char -> {
                        val id = tw.getFreshIdLabel<DbCharacterliteral>()
                        val type = useType(e.type)
                        val locId = tw.getLocation(e)
                        tw.writeExprs_characterliteral(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                        tw.writeHasLocation(id, locId)
                        tw.writeCallableEnclosingExpr(id, callable)
                        tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                        tw.writeNamestrings(v.toString(), v.toString(), id)
                    } is String -> {
                        val id = tw.getFreshIdLabel<DbStringliteral>()
                        val type = useType(e.type)
                        val locId = tw.getLocation(e)
                        tw.writeExprs_stringliteral(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                        tw.writeHasLocation(id, locId)
                        tw.writeCallableEnclosingExpr(id, callable)
                        tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                        tw.writeNamestrings(v.toString(), v.toString(), id)
                    }
                    null -> {
                        val id = tw.getFreshIdLabel<DbNullliteral>()
                        val type = useType(e.type) // class;kotlin.Nothing
                        val locId = tw.getLocation(e)
                        tw.writeExprs_nullliteral(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                        tw.writeHasLocation(id, locId)
                        tw.writeCallableEnclosingExpr(id, callable)
                        tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                    }
                    else -> {
                        logger.warnElement(Severity.ErrorSevere, "Unrecognised IrConst: " + v.javaClass, e)
                    }
                }
            }
            is IrGetValue -> {
                val exprParent = parent.expr(e, callable)
                val owner = e.symbol.owner
                if (owner is IrValueParameter && owner.index == -1) {
                    val id = tw.getFreshIdLabel<DbThisaccess>()
                    val type = useType(e.type)
                    val locId = tw.getLocation(e)
                    tw.writeExprs_thisaccess(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                    tw.writeHasLocation(id, locId)
                    tw.writeCallableEnclosingExpr(id, callable)
                    tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)

                    when(val ownerParent = owner.parent) {
                        is IrFunction -> {
                            if (ownerParent.dispatchReceiverParameter == owner &&
                                ownerParent.extensionReceiverParameter != null) {
                                logger.warnElement(Severity.ErrorSevere, "Function-qualifier for this", e)
                            }
                        }
                        is IrClass -> {
                            if (ownerParent.thisReceiver == owner) {
                                val qualId = tw.getFreshIdLabel<DbUnannotatedtypeaccess>()
                                // TODO: Type arguments
                                val qualType = useSimpleTypeClass(ownerParent, listOf(), false)
                                tw.writeExprs_unannotatedtypeaccess(qualId, qualType.javaResult.id, qualType.kotlinResult.id, id, 0)
                                tw.writeHasLocation(qualId, locId)
                                tw.writeCallableEnclosingExpr(qualId, callable)
                                tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                            }
                        }
                        else -> {
                            logger.warnElement(Severity.ErrorSevere, "Unexpected owner parent for this access: " + ownerParent.javaClass, e)
                        }
                    }
                } else {
                    val id = tw.getFreshIdLabel<DbVaraccess>()
                    val type = useType(e.type)
                    val locId = tw.getLocation(e)
                    tw.writeExprs_varaccess(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                    tw.writeHasLocation(id, locId)
                    tw.writeCallableEnclosingExpr(id, callable)
                    tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)

                    val vId = useValueDeclaration(owner)
                    tw.writeVariableBinding(id, vId)
                }
            }
            is IrGetField -> {
                val exprParent = parent.expr(e, callable)
                val id = tw.getFreshIdLabel<DbVaraccess>()
                val type = useType(e.type)
                val locId = tw.getLocation(e)
                tw.writeExprs_varaccess(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                val owner = e.symbol.owner
                val vId = useField(owner)
                tw.writeVariableBinding(id, vId)
                tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
            }
            is IrGetEnumValue -> {
                val exprParent = parent.expr(e, callable)
                val id = tw.getFreshIdLabel<DbVaraccess>()
                val type = useType(e.type)
                val locId = tw.getLocation(e)
                tw.writeExprs_varaccess(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                val owner = e.symbol.owner
                val vId = useEnumEntry(owner)
                tw.writeVariableBinding(id, vId)
            }
            is IrSetValue,
            is IrSetField -> {
                val exprParent = parent.expr(e, callable)
                val id = tw.getFreshIdLabel<DbAssignexpr>()
                val type = useType(e.type)
                val locId = tw.getLocation(e)
                tw.writeExprs_assignexpr(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)

                val lhsId = tw.getFreshIdLabel<DbVaraccess>()
                tw.writeHasLocation(lhsId, locId)
                tw.writeCallableEnclosingExpr(lhsId, callable)

                when (e) {
                    is IrSetValue -> {
                        val lhsType = useType(e.symbol.owner.type)
                        tw.writeExprs_varaccess(lhsId, lhsType.javaResult.id, lhsType.kotlinResult.id, id, 0)
                        // TODO: location, enclosing callable?
                        tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                        val vId = useValueDeclaration(e.symbol.owner)
                        tw.writeVariableBinding(lhsId, vId)
                        extractExpressionExpr(e.value, callable, id, 1, exprParent.enclosingStmt)
                    }
                    is IrSetField -> {
                        val lhsType = useType(e.symbol.owner.type)
                        tw.writeExprs_varaccess(lhsId, lhsType.javaResult.id, lhsType.kotlinResult.id, id, 0)
                        // TODO: location, enclosing callable?
                        tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                        val vId = useField(e.symbol.owner)
                        tw.writeVariableBinding(lhsId, vId)
                        extractExpressionExpr(e.value, callable, id, 1, exprParent.enclosingStmt)
                    }
                    else -> {
                        logger.warnElement(Severity.ErrorSevere, "Unhandled IrSet* element.", e)
                    }
                }
            }
            is IrWhen -> {
                val exprParent = parent.expr(e, callable)
                val id = tw.getFreshIdLabel<DbWhenexpr>()
                val type = useType(e.type)
                val locId = tw.getLocation(e)
                tw.writeExprs_whenexpr(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                if(e.origin == IrStatementOrigin.IF) {
                    tw.writeWhen_if(id)
                }
                e.branches.forEachIndexed { i, b ->
                    val bId = tw.getFreshIdLabel<DbWhenbranch>()
                    val bLocId = tw.getLocation(b)
                    tw.writeWhen_branch(bId, id, i)
                    tw.writeHasLocation(bId, bLocId)
                    extractExpressionExpr(b.condition, callable, bId, 0, exprParent.enclosingStmt)
                    extractExpressionStmt(b.result, callable, bId, 1)
                    if(b is IrElseBranch) {
                        tw.writeWhen_branch_else(bId)
                    }
                }
            }
            is IrGetClass -> {
                val exprParent = parent.expr(e, callable)
                val id = tw.getFreshIdLabel<DbGetclassexpr>()
                val locId = tw.getLocation(e)
                val type = useType(e.type)
                tw.writeExprs_getclassexpr(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                extractExpressionExpr(e.argument, callable, id, 0, exprParent.enclosingStmt)
            }
            is IrTypeOperatorCall -> {
                val exprParent = parent.expr(e, callable)
                extractTypeOperatorCall(e, callable, exprParent.parent, exprParent.idx, exprParent.enclosingStmt)
            }
            is IrVararg -> {
                val exprParent = parent.expr(e, callable)
                val id = tw.getFreshIdLabel<DbVarargexpr>()
                val locId = tw.getLocation(e)
                val type = useType(e.type)
                tw.writeExprs_varargexpr(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)
                e.elements.forEachIndexed { i, arg -> extractVarargElement(arg, callable, id, i, exprParent.enclosingStmt) }
            }
            is IrGetObjectValue -> {
                // For `object MyObject { ... }`, the .class has an
                // automatically-generated `public static final MyObject INSTANCE`
                // field that we are accessing here.
                val exprParent = parent.expr(e, callable)
                val c: IrClass = e.symbol.owner
                val instance = if (c.isCompanion) useCompanionObjectClassInstance(c) else useObjectClassInstance(c)

                if(instance != null) {
                    val id = tw.getFreshIdLabel<DbVaraccess>()
                    val type = useType(e.type)
                    val locId = tw.getLocation(e)
                    tw.writeExprs_varaccess(id, type.javaResult.id, type.kotlinResult.id, exprParent.parent, exprParent.idx)
                    tw.writeHasLocation(id, locId)
                    tw.writeCallableEnclosingExpr(id, callable)
                    tw.writeStatementEnclosingExpr(id, exprParent.enclosingStmt)

                    tw.writeVariableBinding(id, instance.id)
                }
            }
            else -> {
                logger.warnElement(Severity.ErrorSevere, "Unrecognised IrExpression: " + e.javaClass, e)
            }
        }
    }

    fun extractVarargElement(e: IrVarargElement, callable: Label<out DbCallable>, parent: Label<out DbExprparent>, idx: Int, enclosingStmt: Label<out DbStmt>) {
        when(e) {
            is IrExpression -> {
                extractExpressionExpr(e, callable, parent, idx, enclosingStmt)
            }
            else -> {
                logger.warnElement(Severity.ErrorSevere, "Unrecognised IrVarargElement: " + e.javaClass, e)
            }
        }
    }

    fun extractTypeAccess(t: IrType, callable: Label<out DbCallable>, parent: Label<out DbExprparent>, idx: Int, elementForLocation: IrElement, enclosingStmt: Label<out DbStmt>) {
        // TODO: elementForLocation allows us to give some sort of
        // location, but a proper location for the type access will
        // require upstream changes
        val type = useType(t)
        val id = tw.getFreshIdLabel<DbUnannotatedtypeaccess>()
        tw.writeExprs_unannotatedtypeaccess(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
        val locId = tw.getLocation(elementForLocation)
        tw.writeHasLocation(id, locId)
        tw.writeCallableEnclosingExpr(id, callable)
        tw.writeStatementEnclosingExpr(id, enclosingStmt)
    }

    fun extractTypeOperatorCall(e: IrTypeOperatorCall, callable: Label<out DbCallable>, parent: Label<out DbExprparent>, idx: Int, enclosingStmt: Label<out DbStmt>) {
        when(e.operator) {
            IrTypeOperator.CAST -> {
                val id = tw.getFreshIdLabel<DbCastexpr>()
                val locId = tw.getLocation(e)
                val type = useType(e.type)
                tw.writeExprs_castexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, enclosingStmt)
                extractTypeAccess(e.typeOperand, callable, id, 0, e, enclosingStmt)
                extractExpressionExpr(e.argument, callable, id, 1, enclosingStmt)
            }
            IrTypeOperator.IMPLICIT_CAST -> {
                // TODO: Make this distinguishable from an explicit cast?
                val id = tw.getFreshIdLabel<DbCastexpr>()
                val locId = tw.getLocation(e)
                val type = useType(e.type)
                tw.writeExprs_castexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, enclosingStmt)
                extractTypeAccess(e.typeOperand, callable, id, 0, e, enclosingStmt)
                extractExpressionExpr(e.argument, callable, id, 1, enclosingStmt)
            }
            IrTypeOperator.IMPLICIT_NOTNULL -> {
                // TODO: Make this distinguishable from an explicit cast?
                val id = tw.getFreshIdLabel<DbCastexpr>()
                val locId = tw.getLocation(e)
                val type = useType(e.type)
                tw.writeExprs_castexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, enclosingStmt)
                extractTypeAccess(e.typeOperand, callable, id, 0, e, enclosingStmt)
                extractExpressionExpr(e.argument, callable, id, 1, enclosingStmt)
            }
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
                // TODO: Make this distinguishable from an explicit cast?
                val id = tw.getFreshIdLabel<DbCastexpr>()
                val locId = tw.getLocation(e)
                val type = useType(e.type)
                tw.writeExprs_castexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, enclosingStmt)
                extractTypeAccess(e.typeOperand, callable, id, 0, e, enclosingStmt)
                extractExpressionExpr(e.argument, callable, id, 1, enclosingStmt)
            }
            IrTypeOperator.SAFE_CAST -> {
                // TODO: Distinguish this (e as? T) from CAST
                val id = tw.getFreshIdLabel<DbCastexpr>()
                val locId = tw.getLocation(e)
                val type = useType(e.type)
                tw.writeExprs_castexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, enclosingStmt)
                extractTypeAccess(e.typeOperand, callable, id, 0, e, enclosingStmt)
                extractExpressionExpr(e.argument, callable, id, 1, enclosingStmt)
            }
            IrTypeOperator.INSTANCEOF -> {
                val id = tw.getFreshIdLabel<DbInstanceofexpr>()
                val locId = tw.getLocation(e)
                val type = useType(e.type)
                tw.writeExprs_instanceofexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, enclosingStmt)
                extractExpressionExpr(e.argument, callable, id, 0, enclosingStmt)
                extractTypeAccess(e.typeOperand, callable, id, 1, e, enclosingStmt)
            }
            IrTypeOperator.NOT_INSTANCEOF -> {
                val id = tw.getFreshIdLabel<DbNotinstanceofexpr>()
                val locId = tw.getLocation(e)
                val type = useType(e.type)
                tw.writeExprs_notinstanceofexpr(id, type.javaResult.id, type.kotlinResult.id, parent, idx)
                tw.writeHasLocation(id, locId)
                tw.writeCallableEnclosingExpr(id, callable)
                tw.writeStatementEnclosingExpr(id, enclosingStmt)
                extractExpressionExpr(e.argument, callable, id, 0, enclosingStmt)
                extractTypeAccess(e.typeOperand, callable, id, 1, e, enclosingStmt)
            }
            else -> {
                logger.warnElement(Severity.ErrorSevere, "Unrecognised IrTypeOperatorCall for ${e.operator}: " + e.render(), e)
            }
        }
    }

    private fun extractBreakContinue(
        e: IrBreakContinue,
        id: Label<out DbBreakcontinuestmt>
    ) {
        val locId = tw.getLocation(e)
        tw.writeHasLocation(id, locId)
        val label = e.label
        if (label != null) {
            tw.writeNamestrings(label, "", id)
        }

        val loopId = loopIdMap[e.loop]
        if (loopId == null) {
            logger.warnElement(Severity.ErrorSevere, "Missing break/continue target", e)
            return
        }

        tw.writeKtBreakContinueTargets(id, loopId)
    }

    private val IrType.isAnonymous: Boolean
        get() = ((this as? IrSimpleType)?.classifier?.owner as? IrClass)?.isAnonymousObject ?: false

    fun extractGeneratedClass(localFunction: IrFunction, superTypes: List<IrType>) : Label<out DbClass> {
        val ids = getLocalFunctionLabels(localFunction)

        // Write class
        @Suppress("UNCHECKED_CAST")
        val id = ids.type.javaResult.id as Label<out DbClass>
        val pkgId = extractPackage("")
        tw.writeClasses(id, "", pkgId, id)
        val locId = tw.getLocation(localFunction)
        tw.writeHasLocation(id, locId)

        // Extract local function as a member
        extractFunction(localFunction, id)

        // Extract constructor
        tw.writeConstrs(ids.constructor, "", "", ids.type.javaResult.id, ids.type.kotlinResult.id, id, ids.constructor)
        tw.writeHasLocation(ids.constructor, locId)

        // Constructor body
        val constructorBlockId = tw.getFreshIdLabel<DbBlock>()
        tw.writeStmts_block(constructorBlockId, ids.constructor, 0, ids.constructor)
        tw.writeHasLocation(constructorBlockId, locId)

        // Super call
        val superCallId = tw.getFreshIdLabel<DbSuperconstructorinvocationstmt>()
        tw.writeStmts_superconstructorinvocationstmt(superCallId, constructorBlockId, 0, ids.function)

        val baseConstructor = superTypes.first().classOrNull!!.owner.declarations.find { it is IrFunction && it.symbol is IrConstructorSymbol }
        val baseConstructorId = useFunction<DbConstructor>(baseConstructor as IrFunction)

        tw.writeHasLocation(superCallId, locId)
        @Suppress("UNCHECKED_CAST")
        tw.writeCallableBinding(superCallId as Label<DbCaller>, baseConstructorId)

        // TODO: We might need to add an `<obinit>` function, and a call to it to match other classes

        addModifiers(id, "public", "static", "final")
        extractClassSupertypes(superTypes, listOf(), id)

        return id
    }
}
