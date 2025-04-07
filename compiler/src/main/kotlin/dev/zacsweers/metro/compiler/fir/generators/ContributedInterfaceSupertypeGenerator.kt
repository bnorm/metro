// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.rankValue
import dev.zacsweers.metro.compiler.fir.resolvedAdditionalScopesClassIds
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.resolvedExcludedClassIds
import dev.zacsweers.metro.compiler.fir.resolvedReplacedClassIds
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.fir.scopeArgument
import java.util.TreeMap
import kotlin.collections.plusAssign
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds

// Toe-hold for contributed types
internal class ContributedInterfaceSupertypeGenerator(
  session: FirSession,
  private val classIds: ClassIds,
) : FirSupertypeGenerationExtension(session) {

  class Factory(private val classIds: ClassIds) : FirSupertypeGenerationExtension.Factory {
    override fun create(session: FirSession) =
      ContributedInterfaceSupertypeGenerator(session, classIds)
  }

  private val dependencyGraphPredicate =
    LookupPredicate.create {
      annotated(classIds.dependencyGraphAnnotations.map { it.asSingleFqName() })
    }

  private val dependencyGraphs by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(dependencyGraphPredicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .toSet()
  }

  private val contributingTypesPredicate =
    LookupPredicate.create {
      annotated(classIds.allContributesAnnotations.map { it.asSingleFqName() })
    }

  private val inCompilationScopesToContributions:
    FirCache<FirSession, Map<ClassId, Set<ClassId>>, TypeResolveService> =
    session.firCachesFactory.createCache { session, typeResolver ->
      val scopesToContributingClass = mutableMapOf<ClassId, MutableSet<ClassId>>()
      // In a KMP compilation we want to capture _all_ sessions' symbols. For example, if we are
      // generating supertypes for a graph in jvmMain, we want to capture contributions declared in
      // commonMain.
      val allSessions =
        sequenceOf(session).plus(session.moduleData.allDependsOnDependencies.map { it.session })
      allSessions
        .flatMap { it.predicateBasedProvider.getSymbolsByPredicate(contributingTypesPredicate) }
        .filterIsInstance<FirRegularClassSymbol>()
        .forEach { clazz ->
          clazz.resolvedCompilerAnnotationsWithClassIds
            .annotationsIn(session, session.classIds.allContributesAnnotations)
            .mapNotNull { it.resolvedScopeClassId(typeResolver) }
            .forEach { scopeClassId ->
              scopesToContributingClass
                .getOrPut(scopeClassId, ::mutableSetOf)
                .add(clazz.classId.createNestedClassId(Symbols.Names.metroContribution))
            }
        }
      scopesToContributingClass
    }

  private val generatedScopesToContributions:
    FirCache<FqName, Map<ClassId, Set<ClassId>>, TypeResolveService> =
    session.firCachesFactory.createCache { hintsPackage, typeResolver ->
      val functionsInPackage =
        session.symbolProvider.symbolNamesProvider
          .getTopLevelCallableNamesInPackage(hintsPackage)
          .orEmpty()
          .flatMap { name -> session.symbolProvider.getTopLevelFunctionSymbols(hintsPackage, name) }

      buildMap<ClassId, MutableSet<ClassId>> {
        for (contribution in functionsInPackage) {
          val originClass =
            contribution.resolvedReturnType.toRegularClassSymbol(session) ?: continue

          originClass.resolvedCompilerAnnotationsWithClassIds
            .annotationsIn(session, session.classIds.allContributesAnnotations)
            .mapNotNull { it.resolvedScopeClassId(typeResolver) }
            .distinct()
            .forEach { scopeClassId ->
              val metroContribution =
                originClass.classId.createNestedClassId(Symbols.Names.metroContribution)
              getOrPut(scopeClassId, ::mutableSetOf).add(metroContribution)
            }
        }
      }
    }

  private fun FirAnnotationContainer.graphAnnotation(): FirAnnotation? {
    return annotations.annotationsIn(session, classIds.dependencyGraphAnnotations).firstOrNull()
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    if (declaration.symbol !in dependencyGraphs) {
      return false
    }
    val graphAnnotation = declaration.graphAnnotation() ?: return false

    // TODO in an FIR checker, disallow omitting scope but defining additional scopes
    // Can't check the scope class ID here but we'll check in computeAdditionalSupertypes
    return graphAnnotation.scopeArgument() != null
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    val graphAnnotation = classLikeDeclaration.graphAnnotation()!!

    val scopes =
      buildSet {
          graphAnnotation.resolvedScopeClassId(typeResolver)?.let(::add)
          graphAnnotation.resolvedAdditionalScopesClassIds(typeResolver).let(::addAll)
        }
        .filterNotTo(mutableSetOf()) { it == StandardClassIds.Nothing }

    val contributions =
      scopes
        .flatMap { scopeClassId ->
          val classPathContributions =
            generatedScopesToContributions
              .getValue(Symbols.FqNames.metroHintsPackage, typeResolver)[scopeClassId]
              .orEmpty()

          val inCompilationContributions =
            inCompilationScopesToContributions
              .getValue(session, typeResolver)[scopeClassId]
              .orEmpty()

          (inCompilationContributions + classPathContributions).map {
            it.constructClassLikeType(emptyArray())
          }
        }
        .let {
          // Stable sort
          TreeMap<ClassId, ConeKotlinType>(compareBy(ClassId::asString)).apply {
            for (contribution in it) {
              // This is always the $$MetroContribution, the contribution is its parent
              val classId = contribution.classId?.parentClassId ?: continue
              put(classId, contribution)
            }
          }
        }

    val excluded = graphAnnotation.resolvedExcludedClassIds(typeResolver)
    if (contributions.isEmpty() && excluded.isEmpty()) {
      return emptyList()
    }

    val unmatchedExclusions = mutableSetOf<ClassId>()

    for (excludedClassId in excluded) {
      val removed = contributions.remove(excludedClassId)
      if (removed == null) {
        unmatchedExclusions += excludedClassId
      }
    }

    if (unmatchedExclusions.isNotEmpty()) {
      // TODO warn?
    }

    // Process replacements
    val unmatchedReplacements = mutableSetOf<ClassId>()
    contributions.values
      .filterIsInstance<ConeClassLikeType>()
      .mapNotNull { it.toClassSymbol(session)?.getContainingClassSymbol() }
      .flatMap { contributingType ->
        contributingType.resolvedCompilerAnnotationsWithClassIds
          .annotationsIn(session, session.classIds.allContributesAnnotations)
          .flatMap { annotation -> annotation.resolvedReplacedClassIds(typeResolver) }
      }
      .distinct()
      .forEach { replacedClassId ->
        val removed = contributions.remove(replacedClassId)
        if (removed != null) {
          unmatchedReplacements += replacedClassId
        }
      }

    if (unmatchedReplacements.isNotEmpty()) {
      // TODO warn?
    }

    if (session.metroFirBuiltIns.options.enableDaggerAnvilInterop) {
      val unmatchedRankReplacements = mutableSetOf<ClassId>()
      val pendingRankReplacements = processRankBasedReplacements(contributions, typeResolver)

      pendingRankReplacements.distinct().forEach { replacedClassId ->
        val removed = contributions.remove(replacedClassId)
        if (removed != null) {
          unmatchedRankReplacements += replacedClassId
        }
      }

      if (unmatchedRankReplacements.isNotEmpty()) {
        // TODO we could report all rank based replacements here
      }
    }

    return contributions.values.toList()
  }

  /**
   * This is an imperfect solution to provide `rank` interop for users migrating from Dagger-Anvil.
   * We're not able to get 1:1 parity due to some type restrictions but it should be enough to make
   * the migration much more feasible in large projects that have a lot of ranked bindings.
   *
   * There are two important limitations to note here:
   * 1. Ranked bindings and bindings that get outranked must both explicitly declare their binding
   *    type in order for us to actually compare them, because supertypes are not resolvable here.
   *    The user will end up getting a duplicate binding error if the outranked binding is using an
   *    implicit type.
   * 2. We can't check for qualifiers when comparing these bindings because those annotation calls
   *    are not resolved at this point. E.g. a compiler critical annotation
   *    like @ContributesBinding(..) will be resolved but @Named(..) will not. This means that
   *    qualifiers are unsupported for rank interop support. Rank can only effectively be used for
   *    binding types where all of them are unqualified or all use the same qualifier. Other
   *    combinations will need to be migrated to instead use explicit replacements or exclusions.
   *
   * @return The bindings which have been outranked and should not be included in the merged graph.
   */
  private fun processRankBasedReplacements(
    contributions: TreeMap<ClassId, ConeKotlinType>,
    typeResolver: TypeResolveService,
  ): Set<ClassId> {
    val pendingRankReplacements = mutableSetOf<ClassId>()

    val rankedBindings =
      contributions.values
        .filterIsInstance<ConeClassLikeType>()
        .mapNotNull { it.toClassSymbol(session)?.getContainingClassSymbol() }
        .flatMap { contributingType ->
          contributingType.resolvedCompilerAnnotationsWithClassIds
            .annotationsIn(session, session.classIds.contributesBindingAnnotations)
            .mapNotNull { annotation ->
              annotation.resolvedBindingArgument(session, typeResolver)?.let { bindingArg ->
                ContributedBinding(contributingType, bindingArg, annotation.rankValue())
              }
            }
        }
    val bindingGroups =
      rankedBindings
        .groupBy { binding -> binding.boundType?.coneType }
        .filter { bindingGroup -> bindingGroup.value.size > 1 }

    for (bindingGroup in bindingGroups.values) {
      val topBindings =
        bindingGroup
          .groupBy { binding -> binding.rank }
          .toSortedMap()
          .let { it.getValue(it.lastKey()) }

      // These are the bindings that were outranked and should not be processed further
      bindingGroup.minus(topBindings).forEach {
        pendingRankReplacements += it.contributingType.classId
      }
    }

    return pendingRankReplacements
  }

  private data class ContributedBinding(
    val contributingType: FirClassLikeSymbol<*>,
    val boundType: FirTypeRef?,
    val rank: Long,
  )
}
