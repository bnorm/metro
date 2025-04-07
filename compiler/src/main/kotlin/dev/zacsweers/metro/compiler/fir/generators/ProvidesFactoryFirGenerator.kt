// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.*
import dev.zacsweers.metro.compiler.fir.*
import org.jetbrains.kotlin.builtins.functions.FunctionTypeKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.computeTypeAttributes
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.extensions.*
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.java.FirCliSession
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.withParameterNameAnnotation
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.ConstantValueKind

/** Generates factory declarations for `@Provides`-annotated members. */
internal class ProvidesFactoryFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  private val providesAnnotationPredicate by unsafeLazy {
    annotated(session.classIds.providesAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(providesAnnotationPredicate)
  }

  // TODO apparently writing these types of caches is bad and
  //  generate* functions should be side-effect-free, but honestly
  //  how is this practical without this? Or is it ok if it's just an
  //  internal cache? Unclear what "should not leak" means.
  private val providerFactoryClassIdsToCallables = mutableMapOf<ClassId, ProviderCallable>()
  private val providerFactoryClassIdsToSymbols = mutableMapOf<ClassId, FirClassLikeSymbol<*>>()

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    val callable =
      if (classSymbol.hasOrigin(Keys.ProviderFactoryCompanionDeclaration)) {
        val owner = classSymbol.getContainingClassSymbol() ?: return emptySet()
        providerFactoryClassIdsToCallables[owner.classId]
      } else {
        providerFactoryClassIdsToCallables[classSymbol.classId]
      } ?: return emptySet()

    return buildSet {
      add(SpecialNames.INIT)
      if (classSymbol.classKind == ClassKind.OBJECT) {
        // Generate create() and newInstance headers
        add(Symbols.Names.create)
        add(callable.bytecodeName)
      }
    }
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val constructor =
      if (context.owner.classKind == ClassKind.OBJECT) {
        createDefaultPrivateConstructor(context.owner, Keys.Default)
      } else {
        val callable =
          providerFactoryClassIdsToCallables[context.owner.classId] ?: return emptyList()
        buildFactoryConstructor(context, callable.instanceReceiver, null, callable.valueParameters)
      }
    return listOf(constructor.symbol)
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val nonNullContext = context ?: return emptyList()
    val factoryClassId =
      if (nonNullContext.owner.isCompanion) {
        nonNullContext.owner.getContainingClassSymbol()?.classId ?: return emptyList()
      } else {
        nonNullContext.owner.classId
      }
    val callable = providerFactoryClassIdsToCallables[factoryClassId] ?: return emptyList()
    val function =
      when (callableId.callableName) {
        Symbols.Names.create -> {
          buildFactoryCreateFunction(
            nonNullContext,
            Symbols.ClassIds.metroFactory.constructClassLikeType(arrayOf(callable.returnType)),
            callable.instanceReceiver,
            null,
            callable.valueParameters,
          )
        }
        callable.bytecodeName -> {
          buildNewInstanceFunction(
            nonNullContext,
            callable.bytecodeName,
            callable.returnType,
            callable.instanceReceiver,
            null,
            callable.valueParameters,
          )
        }
        else -> {
          println("Unrecognized function $callableId")
          return emptyList()
        }
      }
    return listOf(function)
  }

  // TODO can we get a finer-grained callback other than just per-class?
  @OptIn(DirectDeclarationsAccess::class)
  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return if (classSymbol.hasOrigin(Keys.ProviderFactoryCompanionDeclaration)) {
      // It's a factory's companion object
      emptySet()
    } else if (classSymbol.classId in providerFactoryClassIdsToCallables) {
      // It's a generated factory, give it a companion object if it isn't going to be an object
      if (classSymbol.classKind == ClassKind.OBJECT) {
        emptySet()
      } else {
        setOf(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
      }
    } else {
      // It's a provider-containing class, generated factory class names and store callable info
      classSymbol.declarationSymbols
        .filterIsInstance<FirCallableSymbol<*>>()
        .filter {
          it.isAnnotatedWithAny(session, session.classIds.providesAnnotations) ||
            (it as? FirPropertySymbol)
              ?.getterSymbol
              ?.isAnnotatedWithAny(session, session.classIds.providesAnnotations) == true
        }
        .mapNotNullToSet { providesCallable ->
          val providerCallable =
            providesCallable.asProviderCallable(classSymbol) ?: return@mapNotNullToSet null
          val simpleName =
            buildString {
                if (providerCallable.useGetPrefix) {
                  append("Get")
                }
                append(providerCallable.name.capitalizeUS())
                append(Symbols.Names.metroFactory.asString())
              }
              .asName()
          simpleName.also {
            providerFactoryClassIdsToCallables[
              classSymbol.classId.createNestedClassId(simpleName)] = providerCallable
          }
        }
    }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    return if (name == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
      // It's a factory's companion object, just generate the declaration
      createCompanionObject(owner, Keys.ProviderFactoryCompanionDeclaration).symbol
    } else if (owner.classId.createNestedClassId(name) in providerFactoryClassIdsToCallables) {
      // It's a factory class itself
      val classId = owner.classId.createNestedClassId(name)
      val sourceCallable = providerFactoryClassIdsToCallables[classId] ?: return null

      val classKind =
        if (sourceCallable.shouldGenerateObject) {
          ClassKind.OBJECT
        } else {
          ClassKind.CLASS
        }

      createNestedClass(
          owner,
          name.capitalizeUS(),
          Keys.ProviderFactoryClassDeclaration,
          classKind = classKind,
        )
        .apply {
          markAsDeprecatedHidden(session)
          // Add the source callable info
          replaceAnnotationsSafe(
            annotations + listOf(buildProvidesCallableIdAnnotation(sourceCallable))
          )
        }
        .symbol
        .also { providerFactoryClassIdsToSymbols[it.classId] = it }
    } else {
      null
    }
  }

  private fun FirCallableSymbol<*>.asProviderCallable(owner: FirClassSymbol<*>): ProviderCallable? {
    val instanceReceiver = if (owner.isCompanion) null else owner.defaultType()
    val params =
      when (this) {
        is FirPropertySymbol -> emptyList()
        is FirNamedFunctionSymbol ->
          this.valueParameterSymbols.map { MetroFirValueParameter(session, it) }
        else -> return null
      }
    return ProviderCallable(owner, this, instanceReceiver, params)
  }

  private fun buildProvidesCallableIdAnnotation(sourceCallable: ProviderCallable): FirAnnotation {
    return buildAnnotation {
      val anno = session.metroFirBuiltIns.providesCallableIdClassSymbol

      annotationTypeRef = anno.defaultType().toFirResolvedTypeRef()

      argumentMapping = buildAnnotationArgumentMapping {
        mapping[Name.identifier("callableName")] =
          buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.String,
            value = sourceCallable.callableId.callableName.asString(),
            annotations = null,
            setType = true,
            prefix = null,
          )
        mapping[Name.identifier("isPropertyAccessor")] =
          buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.Boolean,
            value =
              when (sourceCallable.symbol) {
                is FirPropertyAccessorSymbol,
                is FirPropertySymbol -> true
                else -> false
              },
            annotations = null,
            setType = true,
            prefix = null,
          )
      }
    }
  }

  class ProviderCallable(
    val owner: FirClassSymbol<*>,
    val symbol: FirCallableSymbol<*>,
    val instanceReceiver: ConeClassLikeType?,
    val valueParameters: List<MetroFirValueParameter>,
  ) {
    val callableId = CallableId(owner.classId, symbol.name)
    val name = symbol.name
    val shouldGenerateObject by unsafeLazy {
      instanceReceiver == null && (isProperty || valueParameters.isEmpty())
    }
    private val isProperty
      get() = symbol is FirPropertySymbol

    val returnType
      get() = symbol.resolvedReturnType

    val useGetPrefix by unsafeLazy { isProperty && !isWordPrefixRegex.matches(name.asString()) }

    val bytecodeName: Name by unsafeLazy {
      buildString {
          when {
            useGetPrefix -> {
              append("get")
              append(name.asString().capitalizeUS())
            }
            else -> append(name.asString())
          }
        }
        .asName()
    }
  }
}

@OptIn(DirectDeclarationsAccess::class)
internal class ProvidesFactorySupertypeGenerator(session: FirSession) :
  FirSupertypeGenerationExtension(session) {

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    return declaration.symbol.hasOrigin(Keys.ProviderFactoryClassDeclaration)
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> = emptyList()

  @OptIn(SymbolInternals::class)
  @ExperimentalSupertypesGenerationApi
  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    val originClassSymbol =
      klass.getContainingClassSymbol() as? FirClassSymbol<*> ?: return emptyList()
    val callableName =
      klass.name.asString().removeSuffix(Symbols.Names.metroFactory.asString()).decapitalizeUS()
    val callable =
      originClassSymbol.declarationSymbols.filterIsInstance<FirCallableSymbol<*>>().firstOrNull {
        val nameMatches =
          it.name.asString() == callableName ||
            (it is FirPropertySymbol &&
              it.name.asString() == callableName.removePrefix("get").decapitalizeUS())
        if (nameMatches) {
          // Secondary check to ensure it's a @Provides-annotated callable. Otherwise we may
          // match against overloaded non-Provides declarations
          val metroAnnotations = it.metroAnnotations(session)
          metroAnnotations.isProvides
        } else {
          false
        }
      } ?: return emptyList()

    val returnType =
      when (val type = callable.fir.returnTypeRef) {
        is FirUserTypeRef -> {
          typeResolver
            .resolveUserType(type)
            .also {
              if (it is FirErrorTypeRef) {
                val message =
                  """
                Could not resolve provider return type for provider: ${callable.callableId}
                This can happen if the provider references a class that is nested within the same parent class and has cyclical references to other classes.
                ${callable.fir.render()}
              """
                    .trimIndent()
                if (session is FirCliSession) {
                  error(message)
                } else {
                  // TODO TypeResolveService appears to be unimplemented in the IDE
                  //  https://youtrack.jetbrains.com/issue/KT-74553/
                  System.err.println(message)
                  return emptyList()
                }
              }
            }
            .coneType
        }
        is FirFunctionTypeRef -> {
          createFunctionType(type, typeResolver) ?: return emptyList()
        }
        is FirResolvedTypeRef -> type.coneType
        is FirImplicitTypeRef -> {
          // Ignore, will report in FIR checker
          return emptyList()
        }
        else -> return emptyList()
      }

    val factoryType =
      session.symbolProvider
        .getClassLikeSymbolByClassId(Symbols.ClassIds.metroFactory)!!
        .constructType(arrayOf(returnType))
    return listOf(factoryType)
  }

  private fun FirTypeRef.coneTypeLayered(typeResolver: TypeResolveService): ConeKotlinType? {
    return when (this) {
      is FirUserTypeRef ->
        typeResolver.resolveUserType(this).takeUnless { it is FirErrorTypeRef }?.coneType
      else -> coneTypeOrNull
    }
  }

  private fun createFunctionType(
    typeRef: FirFunctionTypeRef,
    typeResolver: TypeResolveService,
  ): ConeClassLikeType? {
    val parametersWithNulls =
      typeRef.contextParameterTypeRefs.map { it.coneTypeLayered(typeResolver) } +
        listOfNotNull(typeRef.receiverTypeRef?.coneTypeLayered(typeResolver)) +
        typeRef.parameters.map {
          it.returnTypeRef.coneTypeLayered(typeResolver)?.withParameterNameAnnotation(it)
        } +
        listOf(typeRef.returnTypeRef.coneTypeLayered(typeResolver))
    val parameters = parametersWithNulls.filterNotNull()
    if (parameters.size != parametersWithNulls.size) {
      val message =
        "Could not resolve function type parameters for function type: ${typeRef.render()}"
      if (session is FirCliSession) {
        error(message)
      } else {
        // TODO TypeResolveService appears to be unimplemented in the IDE
        //  https://youtrack.jetbrains.com/issue/KT-74553/
        System.err.println(message)
        return null
      }
    }
    val functionKinds =
      session.functionTypeService.extractAllSpecialKindsForFunctionTypeRef(typeRef)
    val kind =
      when (functionKinds.size) {
        0 -> FunctionTypeKind.Function
        1 -> functionKinds.single()
        else -> {
          FunctionTypeKind.Function
        }
      }

    val classId = kind.numberedClassId(typeRef.parametersCount)

    val attributes =
      typeRef.annotations.computeTypeAttributes(
        session,
        predefined =
          buildList {
            if (typeRef.receiverTypeRef != null) {
              add(CompilerConeAttributes.ExtensionFunctionType)
            }

            if (typeRef.contextParameterTypeRefs.isNotEmpty()) {
              add(
                CompilerConeAttributes.ContextFunctionTypeParams(
                  typeRef.contextParameterTypeRefs.size
                )
              )
            }
          },
        shouldExpandTypeAliases = true,
      )
    return classId
      .toLookupTag()
      .constructClassType(parameters.toTypedArray(), typeRef.isMarkedNullable, attributes)
  }
}
