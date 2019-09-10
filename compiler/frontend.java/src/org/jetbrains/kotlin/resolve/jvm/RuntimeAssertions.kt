/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.jvm

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.load.java.typeEnhancement.hasEnhancedNullability
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.calls.callUtil.isSafeCall
import org.jetbrains.kotlin.resolve.calls.checkers.AdditionalTypeChecker
import org.jetbrains.kotlin.resolve.calls.checkers.CallChecker
import org.jetbrains.kotlin.resolve.calls.checkers.CallCheckerContext
import org.jetbrains.kotlin.resolve.calls.checkers.ImplicitNothingAsTypeParameterCallChecker
import org.jetbrains.kotlin.resolve.calls.context.ResolutionContext
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.VariableAsFunctionResolvedCall
import org.jetbrains.kotlin.resolve.calls.tower.isSynthesized
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.isEffectivelyPublicApi
import org.jetbrains.kotlin.resolve.descriptorUtil.isPublishedApi
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.isNullableUnderlyingType
import org.jetbrains.kotlin.resolve.scopes.LexicalScopeKind
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.checker.isClassType
import org.jetbrains.kotlin.types.expressions.ControlStructureTypingUtils
import org.jetbrains.kotlin.types.typeUtil.immediateSupertypes
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.io.File

class RuntimeAssertionInfo(val needNotNullAssertion: Boolean, val message: String) {
    interface DataFlowExtras {
        class OnlyMessage(message: String) : DataFlowExtras {
            override val canBeNull: Boolean get() = true
            override val presentableText: String = message
        }

        val canBeNull: Boolean
        val presentableText: String
    }

    companion object {
        @JvmStatic fun create(
                expectedType: KotlinType,
                expressionType: KotlinType,
                dataFlowExtras: DataFlowExtras
        ): RuntimeAssertionInfo? {
            fun assertNotNull(): Boolean {
                if (expectedType.isError || expressionType.isError) return false

                // T : Any, T! = T..T?
                // Let T$ will be copy of T! with enhanced nullability.
                // Cases when nullability assertion needed: T! -> T, T$ -> T

                // Expected type either T?, T! or T$
                if (TypeUtils.isNullableType(expectedType) ||
                    expectedType.hasEnhancedNullability() ||
                    expectedType.isNullableUnderlyingType()
                ) {
                    return false
                }

                // Expression type is not nullable and not enhanced (neither T?, T! or T$)
                val isExpressionTypeNullable = TypeUtils.isNullableType(expressionType)
                if (!isExpressionTypeNullable && !expressionType.hasEnhancedNullability()) return false

                // Smart-cast T! or T?
                if (!dataFlowExtras.canBeNull && isExpressionTypeNullable) return false

                return true
            }

            return if (assertNotNull())
                RuntimeAssertionInfo(needNotNullAssertion = true, message = dataFlowExtras.presentableText)
            else
                null
        }
    }
}

private val KtElement.textForRuntimeAssertionInfo
    get() = StringUtil.trimMiddle(text, 50)

class RuntimeAssertionsDataFlowExtras(
        private val c: ResolutionContext<*>,
        private val expressionType: KotlinType,
        private val expression: KtExpression
) : RuntimeAssertionInfo.DataFlowExtras {
    private val dataFlowValue by lazy(LazyThreadSafetyMode.PUBLICATION) {
        c.dataFlowValueFactory.createDataFlowValue(expression, expressionType, c)
    }

    override val canBeNull: Boolean
        get() = c.dataFlowInfo.getStableNullability(dataFlowValue).canBeNull()
    override val presentableText: String
        get() = expression.textForRuntimeAssertionInfo
}

object RuntimeAssertionsTypeChecker : AdditionalTypeChecker {
    override fun checkType(expression: KtExpression, expressionType: KotlinType, expressionTypeWithSmartCast: KotlinType, c: ResolutionContext<*>) {
        if (TypeUtils.noExpectedType(c.expectedType)) return

        val assertionInfo = RuntimeAssertionInfo.create(
                c.expectedType,
                expressionType,
                RuntimeAssertionsDataFlowExtras(c, expressionType, expression)
        )

        if (assertionInfo != null) {
            c.trace.record(JvmBindingContextSlices.RUNTIME_ASSERTION_INFO, expression, assertionInfo)
        }
    }

}

object RuntimeAssertionsOnGenericTypeReturningFunctionsCallChecker : CallChecker {
    private val SPECIAL_FUNCTION_NAMES = ControlStructureTypingUtils.ResolveConstruct.values().map { it.specialFunctionName }.toSet()

    override fun check(resolvedCall: ResolvedCall<*>, reportOn: PsiElement, context: CallCheckerContext) {
        val unsubstitutedReturnType = resolvedCall.candidateDescriptor.original.returnType ?: return
        val inferredReturnType = resolvedCall.resultingDescriptor.returnType ?: return
        val isEnabledGeneratingNullChecksOnCallSite =
            context.languageVersionSettings.supportsFeature(LanguageFeature.GenerateNullChecksForGenericTypeReturningFunctions)

        /*
         * For flexible types the null checks is generated in the other place,
         * This branch isn't executed for calls returning a flexible type,
         * because `inferredReturnType` in this case will be flexible (nullable), too.
         */

        if (unsubstitutedReturnType.isTypeParameter() &&
            unsubstitutedReturnType.isNullable() &&
            !inferredReturnType.isNullable() &&
            resolvedCall.candidateDescriptor.name !in SPECIAL_FUNCTION_NAMES
        ) {
            val y = resolvedCall.candidateDescriptor.annotations
            val y1 = resolvedCall.candidateDescriptor.fqNameSafe
            val y2 = resolvedCall.candidateDescriptor.containingDeclaration
            val y3 = resolvedCall.candidateDescriptor.isEffectivelyPublicApi
            val y4 = resolvedCall.candidateDescriptor.module
            val callElement = resolvedCall.call.callElement
            val assertionInfo = RuntimeAssertionInfo(needNotNullAssertion = true, message = callElement.textForRuntimeAssertionInfo)

            if (resolvedCall is VariableAsFunctionResolvedCall) {
                File("/Users/victor.petukhov/Desktop/untitled_folder/1.txt").appendText(resolvedCall.variableCall.candidateDescriptor.toString() + " +++|+++\n")
            } else {
                File("/Users/victor.petukhov/Desktop/untitled_folder/1.txt").appendText(resolvedCall.candidateDescriptor.toString() + "\n")
            }
            File("/Users/victor.petukhov/Desktop/untitled_folder/1.txt").appendText(unsubstitutedReturnType.toString() + ": " + inferredReturnType + "\n\n")

            File("/Users/victor.petukhov/Desktop/untitled_folder/10.txt").appendText(" |")

            context.trace.record(
                when (context.scope.kind) {
                    LexicalScopeKind.PROPERTY_DELEGATE_METHOD -> JvmBindingContextSlices.RUNTIME_ASSERTION_INFO_ON_DELEGATES
                    else -> JvmBindingContextSlices.RUNTIME_ASSERTION_INFO_ON_GENERIC_CALL
                },
                callElement,
                assertionInfo
            )
        }
    }
}

object RuntimeAssertionsOnExtensionReceiverCallChecker : CallChecker {
    override fun check(resolvedCall: ResolvedCall<*>, reportOn: PsiElement, context: CallCheckerContext) {
        if (resolvedCall.call.isSafeCall()) return

        val callee = resolvedCall.resultingDescriptor
        checkReceiver(callee.extensionReceiverParameter, resolvedCall.extensionReceiver, context)
    }

    private fun checkReceiver(receiverParameter: ReceiverParameterDescriptor?, receiverValue: ReceiverValue?, context: CallCheckerContext) {
        if (receiverParameter == null || receiverValue == null) return
        val expressionReceiverValue = receiverValue.safeAs<ExpressionReceiver>() ?: return
        val receiverExpression = expressionReceiverValue.expression
        val c = context.resolutionContext

        val assertionInfo = RuntimeAssertionInfo.create(
                receiverParameter.type,
                receiverValue.type,
                RuntimeAssertionsDataFlowExtras(c, receiverValue.type, receiverExpression)
        )

        if (assertionInfo != null) {
            c.trace.record(JvmBindingContextSlices.RECEIVER_RUNTIME_ASSERTION_INFO, expressionReceiverValue, assertionInfo)
        }
    }
}

object RuntimeAssertionsOnDeclarationBodyChecker {
    @JvmStatic
    fun check(
            declaration: KtDeclaration,
            descriptor: DeclarationDescriptor,
            bindingTrace: BindingTrace,
            languageVersionSettings: LanguageVersionSettings
    ) {
        if (!languageVersionSettings.supportsFeature(LanguageFeature.StrictJavaNullabilityAssertions)) return

        when {
            declaration is KtProperty && descriptor is VariableDescriptor ->
                checkLocalVariable(declaration, descriptor, bindingTrace)
            declaration is KtFunction && descriptor is FunctionDescriptor ->
                checkFunction(declaration, descriptor, bindingTrace)
            declaration is KtProperty && descriptor is PropertyDescriptor ->
                checkProperty(declaration, descriptor, bindingTrace)
            declaration is KtPropertyAccessor && descriptor is PropertyAccessorDescriptor ->
                checkPropertyAccessor(declaration, descriptor, bindingTrace)
        }
    }

    private fun checkLocalVariable(
            declaration: KtProperty,
            descriptor: VariableDescriptor,
            bindingTrace: BindingTrace
    ) {
        if (declaration.typeReference != null) return

        checkNullabilityAssertion(declaration.initializer ?: return, descriptor.type, bindingTrace)
    }

    private fun checkFunction(
            declaration: KtFunction,
            descriptor: FunctionDescriptor,
            bindingTrace: BindingTrace
    ) {
        if (declaration.typeReference != null || declaration.hasBlockBody()) return

        checkNullabilityAssertion(declaration.bodyExpression ?: return, descriptor.returnType ?: return,
                                  bindingTrace)
    }

    private fun checkProperty(
            declaration: KtProperty,
            descriptor: PropertyDescriptor,
            bindingTrace: BindingTrace
    ) {
        if (declaration.typeReference != null) return

        // TODO nullability assertion on delegate initialization expression, see KT-20823
        if (declaration.hasDelegateExpression()) return

        checkNullabilityAssertion(declaration.initializer ?: return, descriptor.type, bindingTrace)
    }

    private fun checkPropertyAccessor(
            declaration: KtPropertyAccessor,
            descriptor: PropertyAccessorDescriptor,
            bindingTrace: BindingTrace
    ) {
        if (declaration.property.typeReference != null || declaration.hasBlockBody()) return

        checkNullabilityAssertion(declaration.bodyExpression ?: return, descriptor.correspondingProperty.type,
                                  bindingTrace)
    }


    private fun checkNullabilityAssertion(
            expression: KtExpression,
            declarationType: KotlinType,
            bindingTrace: BindingTrace
    ) {
        if (declarationType.unwrap().canContainNull()) return

        val expressionType = bindingTrace.getType(expression) ?: return
        if (expressionType.isError) return

        if (!expressionType.hasEnhancedNullability()) return

        bindingTrace.record(
                JvmBindingContextSlices.BODY_RUNTIME_ASSERTION_INFO,
                expression,
                RuntimeAssertionInfo(true, expression.textForRuntimeAssertionInfo)
        )
    }

    private fun UnwrappedType.canContainNull(): Boolean {
        val upper = upperIfFlexible()
        return when {
            upper.isMarkedNullable -> true
            upper.isClassType -> false
            else -> upper.immediateSupertypes().all { it.unwrap().canContainNull() }
        }
    }
}
