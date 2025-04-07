// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.parameters

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.BindingStack
import dev.zacsweers.metro.compiler.ir.ContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.TypeKey
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.constArgumentOfTypeAt
import dev.zacsweers.metro.compiler.ir.locationOrNull
import dev.zacsweers.metro.compiler.ir.parameters.Parameter.Kind
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name

internal class ConstructorParameter(
  override val kind: Kind,
  override val name: Name,
  override val contextualTypeKey: ContextualTypeKey,
  override val isAssisted: Boolean,
  override val isGraphInstance: Boolean,
  override val isBindsInstance: Boolean,
  override val isIncludes: Boolean,
  override val isExtends: Boolean,
  override val hasDefault: Boolean,
  override val assistedIdentifier: String,
  override val assistedParameterKey: Parameter.AssistedParameterKey =
    Parameter.AssistedParameterKey(contextualTypeKey.typeKey, assistedIdentifier),
  override val originalName: Name,
  override val providerType: IrType,
  override val lazyType: IrType,
  override val symbols: Symbols,
  val bindingStackEntry: BindingStack.Entry,
  override val location: CompilerMessageSourceLocation?,
) : Parameter {
  override lateinit var ir: IrValueParameter
  override val typeKey: TypeKey = contextualTypeKey.typeKey
  override val type: IrType = contextualTypeKey.typeKey.type
  override val isWrappedInProvider: Boolean = contextualTypeKey.isWrappedInProvider
  override val isWrappedInLazy: Boolean = contextualTypeKey.isWrappedInLazy
  override val isLazyWrappedInProvider: Boolean = contextualTypeKey.isLazyWrappedInProvider

  private val cachedToString by unsafeLazy {
    buildString {
      contextualTypeKey.typeKey.qualifier?.let {
        append(it)
        append(' ')
      }
      append(name)
      append(':')
      append(' ')
      append(contextualTypeKey.render(short = true, includeQualifier = false))
    }
  }

  override fun toString(): String = cachedToString

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ConstructorParameter

    if (isAssisted != other.isAssisted) return false
    if (isGraphInstance != other.isGraphInstance) return false
    if (isBindsInstance != other.isBindsInstance) return false
    if (isIncludes != other.isIncludes) return false
    if (isExtends != other.isExtends) return false
    if (hasDefault != other.hasDefault) return false
    if (isWrappedInProvider != other.isWrappedInProvider) return false
    if (isWrappedInLazy != other.isWrappedInLazy) return false
    if (isLazyWrappedInProvider != other.isLazyWrappedInProvider) return false
    if (kind != other.kind) return false
    if (name != other.name) return false
    if (contextualTypeKey != other.contextualTypeKey) return false
    if (assistedIdentifier != other.assistedIdentifier) return false
    if (assistedParameterKey != other.assistedParameterKey) return false
    if (ir != other.ir) return false
    if (typeKey != other.typeKey) return false
    if (type != other.type) return false
    if (cachedToString != other.cachedToString) return false

    return true
  }

  override fun hashCode(): Int {
    var result = isAssisted.hashCode()
    result = 31 * result + isGraphInstance.hashCode()
    result = 31 * result + isBindsInstance.hashCode()
    result = 31 * result + isIncludes.hashCode()
    result = 31 * result + isExtends.hashCode()
    result = 31 * result + hasDefault.hashCode()
    result = 31 * result + isWrappedInProvider.hashCode()
    result = 31 * result + isWrappedInLazy.hashCode()
    result = 31 * result + isLazyWrappedInProvider.hashCode()
    result = 31 * result + kind.hashCode()
    result = 31 * result + name.hashCode()
    result = 31 * result + contextualTypeKey.hashCode()
    result = 31 * result + assistedIdentifier.hashCode()
    result = 31 * result + assistedParameterKey.hashCode()
    result = 31 * result + ir.hashCode()
    result = 31 * result + typeKey.hashCode()
    result = 31 * result + type.hashCode()
    result = 31 * result + cachedToString.hashCode()
    return result
  }
}

internal fun List<IrValueParameter>.mapToConstructorParameters(
  context: IrMetroContext,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): List<ConstructorParameter> {
  return map { valueParameter ->
    valueParameter.toConstructorParameter(
      context,
      Kind.VALUE,
      valueParameter.name,
      typeParameterRemapper,
    )
  }
}

internal fun IrValueParameter.toConstructorParameter(
  context: IrMetroContext,
  kind: Kind = Kind.VALUE,
  uniqueName: Name = this.name,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): ConstructorParameter {
  // Remap type parameters in underlying types to the new target container. This is important for
  // type mangling
  val declaredType =
    typeParameterRemapper?.invoke(this@toConstructorParameter.type)
      ?: this@toConstructorParameter.type

  val contextKey =
    declaredType.asContextualTypeKey(
      context,
      with(context) { qualifierAnnotation() },
      defaultValue != null,
      false,
    )

  val assistedAnnotation = annotationsIn(context.symbols.assistedAnnotations).singleOrNull()

  var isProvides = false
  var isIncludes = false
  var isExtends = false
  for (annotation in annotations) {
    val classId = annotation.symbol.owner.parentAsClass.classId
    when (classId) {
      in context.symbols.classIds.providesAnnotations -> {
        isProvides = true
      }
      in context.symbols.classIds.includes -> {
        isIncludes = true
      }
      in context.symbols.classIds.extends -> {
        isExtends = true
      }
      else -> continue
    }
  }

  val assistedIdentifier = assistedAnnotation?.constArgumentOfTypeAt<String>(0).orEmpty()

  val ownerFunction = this.parent as IrFunction // TODO is this safe

  return ConstructorParameter(
      kind = kind,
      name = uniqueName,
      originalName = name,
      contextualTypeKey = contextKey,
      providerType = contextKey.typeKey.type.wrapInProvider(context.symbols.metroProvider),
      lazyType = contextKey.typeKey.type.wrapInLazy(context.symbols),
      isAssisted = assistedAnnotation != null,
      assistedIdentifier = assistedIdentifier,
      symbols = context.symbols,
      isGraphInstance = false,
      bindingStackEntry = BindingStack.Entry.injectedAt(contextKey, ownerFunction, this),
      isBindsInstance = isProvides,
      isExtends = isExtends,
      isIncludes = isIncludes,
      hasDefault = defaultValue != null,
      location = locationOrNull(),
    )
    .apply { this.ir = this@toConstructorParameter }
}
