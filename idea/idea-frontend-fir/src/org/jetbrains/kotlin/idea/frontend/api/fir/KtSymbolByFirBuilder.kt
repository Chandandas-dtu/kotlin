/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirValueParameterImpl
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.idea.frontend.api.*
import org.jetbrains.kotlin.idea.frontend.api.Invalidatable
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.*
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirClassOrObjectSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirLocalVariableSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirPropertySymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirFunctionSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirFunctionValueParameterSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.utils.weakRef
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtClassLikeSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtVariableSymbol

internal class KtSymbolByFirBuilder(private val validityToken: Invalidatable) {
    fun buildSymbol(fir: FirDeclaration): KtSymbol = when (fir) {
        is FirRegularClass -> buildClassSymbol(fir)
        is FirSimpleFunction -> buildFunctionSymbol(fir)
        is FirProperty -> buildPropertySymbol(fir)
        is FirValueParameterImpl -> buildParameterSymbol(fir)
        is FirConstructor -> buildFirConstructorSymbol(fir)
        is FirTypeParameter -> buildFirTypeParameterSymbol(fir)
        is FirTypeAlias -> buildFirTypeAliasSymbol(fir)
        is FirEnumEntry -> buildFirEnumEntrySymbol(fir)
        is FirField -> buildFirFieldSymbol(fir)
        is FirAnonymousFunction -> buildFirAnonymousFunction(fir)
        else ->
            TODO(fir::class.toString())
    }

    fun buildClassSymbol(fir: FirRegularClass) = KtFirClassOrObjectSymbol(fir, validityToken)

    // TODO it can be a constructor parameter, which may be split into parameter & property
    // we should handle them both
    fun buildParameterSymbol(fir: FirValueParameterImpl) = KtFirFunctionValueParameterSymbol(fir, validityToken)
    fun buildFirConstructorParameter(fir: FirValueParameterImpl) = KtFirConstructorValueParameterSymbol(fir, validityToken)

    fun buildFunctionSymbol(fir: FirSimpleFunction) = KtFirFunctionSymbol(fir, validityToken, this)
    fun buildFirConstructorSymbol(fir: FirConstructor) = KtFirConstructorSymbol(fir, validityToken, this)
    fun buildFirTypeParameterSymbol(fir: FirTypeParameter) = KtFirTypeParameterSymbol(fir, validityToken)
    fun buildFirTypeAliasSymbol(fir: FirTypeAlias) = KtFirTypeAliasSymbol(fir, validityToken)
    fun buildFirEnumEntrySymbol(fir: FirEnumEntry) = KtFirEnumEntrySymbol(fir, validityToken)
    fun buildFirFieldSymbol(fir: FirField) = KtFirFieldSymbol(fir, validityToken)
    fun buildFirAnonymousFunction(fir: FirAnonymousFunction) = KtFirAnonymousFunctionSymbol(fir, validityToken, this)

    fun buildPropertySymbol(fir: FirProperty): KtVariableSymbol {
        return when {
            fir.isLocal -> KtFirLocalVariableSymbol(fir, validityToken)
            else -> KtFirPropertySymbol(fir, validityToken)
        }
    }
}

internal fun FirElement.buildSymbol(builder: KtSymbolByFirBuilder) =
    (this as? FirDeclaration)?.let(builder::buildSymbol)

internal fun FirDeclaration.buildSymbol(builder: KtSymbolByFirBuilder) =
    builder.buildSymbol(this)