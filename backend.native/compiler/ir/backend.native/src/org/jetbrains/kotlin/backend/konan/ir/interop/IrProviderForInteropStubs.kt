/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.backend.konan.ir.interop

import org.jetbrains.kotlin.backend.konan.descriptors.isFromInteropLibrary
import org.jetbrains.kotlin.backend.konan.isObjCClass
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.lazy.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.util.DeclarationStubGenerator
import org.jetbrains.kotlin.ir.util.LazyIrProvider
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.referenceClassifier
import org.jetbrains.kotlin.resolve.descriptorUtil.getAllSuperClassifiers
import org.jetbrains.kotlin.resolve.descriptorUtil.module

/**
 * Generates external IR declarations for descriptors from interop libraries.
 */
internal class IrProviderForInteropStubs(
        private val symbolTable: SymbolTable,
        private val isSpecialCase: (IrSymbol) -> Boolean
) : LazyIrProvider {

    override lateinit var declarationStubGenerator: DeclarationStubGenerator

    val classes = mutableSetOf<IrClass>()

    override fun getDeclaration(symbol: IrSymbol): IrLazyDeclarationBase? = when {
        symbol.isBound -> null
        !symbol.descriptor.module.isFromInteropLibrary() -> null
        isSpecialCase(symbol) -> null
        else -> provideIrDeclaration(symbol)
    }

    private fun collectSymbols(klass: IrLazyClass) { // TODO: rename
        val descriptor = klass.descriptor
        if (descriptor.isObjCClass()) {
            // Force-load types to be able to generate RTTI for the given class.
            // We do it via descriptors and symbolTable to avoid creating of unnecessary and incorrect lazy classes.
            descriptor.getAllSuperClassifiers().forEach { symbolTable.referenceClassifier(it) }
            descriptor.companionObjectDescriptor?.let { it.getAllSuperClassifiers().forEach { symbolTable.referenceClassifier(it) } }
            // RTTI for companions will be generated when we recursively visit their parents.
            if (!descriptor.isCompanionObject) {
                classes += klass
            }
        }
    }

    private fun provideIrDeclaration(symbol: IrSymbol): IrLazyDeclarationBase = when (symbol) {
        is IrSimpleFunctionSymbol -> provideIrFunction(symbol)
        is IrPropertySymbol -> provideIrProperty(symbol)
        is IrTypeAliasSymbol -> provideIrTypeAlias(symbol)
        is IrClassSymbol -> provideIrClass(symbol).also(this::collectSymbols)
        is IrConstructorSymbol -> provideIrConstructor(symbol)
        is IrFieldSymbol -> provideIrField(symbol)
        else -> error("Unsupported interop declaration: symbol=$symbol, descriptor=${symbol.descriptor}")
    }

    private fun provideIrFunction(symbol: IrSimpleFunctionSymbol): IrLazyFunction {
        val origin =
                if (symbol.descriptor.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE)
                    IrDeclarationOrigin.FAKE_OVERRIDE
                else IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB
        return declarationStubGenerator.symbolTable.declareSimpleFunction(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                origin,
                symbol.descriptor) {
            IrLazyFunction(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    origin, it,
                    declarationStubGenerator, declarationStubGenerator.typeTranslator
            )
        } as IrLazyFunction
    }

    private fun provideIrProperty(symbol: IrPropertySymbol): IrLazyProperty =
            symbolTable.declareProperty(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol.descriptor, propertyFactory = this::createPropertyDeclaration
            ) as IrLazyProperty

    private fun provideIrClass(symbol: IrClassSymbol): IrLazyClass =
            declarationStubGenerator.symbolTable.declareClass(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol.descriptor, classFactory = this::createClassDeclaration
            ) as IrLazyClass

    private fun provideIrConstructor(symbol: IrConstructorSymbol): IrLazyConstructor =
            declarationStubGenerator.symbolTable.declareConstructor(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol.descriptor, constructorFactory = this::createConstructorDeclaration
            ) as IrLazyConstructor

    private fun createPropertyDeclaration(symbol: IrPropertySymbol) =
            IrLazyProperty(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol, declarationStubGenerator, declarationStubGenerator.typeTranslator, null
            )

    private fun provideIrTypeAlias(symbol: IrTypeAliasSymbol): IrLazyTypeAlias =
            declarationStubGenerator.symbolTable.declareTypeAlias(
                    symbol.descriptor, this::createTypeAlias
            ) as IrLazyTypeAlias

    private fun createTypeAlias(symbol: IrTypeAliasSymbol): IrLazyTypeAlias =
            IrLazyTypeAlias(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol, symbol.descriptor.name,
                    symbol.descriptor.visibility, symbol.descriptor.isActual,
                    declarationStubGenerator, declarationStubGenerator.typeTranslator
            )

    private fun createClassDeclaration(symbol: IrClassSymbol): IrLazyClass =
            IrLazyClass(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol, declarationStubGenerator, declarationStubGenerator.typeTranslator
            )

    private fun createConstructorDeclaration(symbol: IrConstructorSymbol): IrLazyConstructor =
            IrLazyConstructor(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol, declarationStubGenerator, declarationStubGenerator.typeTranslator
            )

    private fun provideIrField(symbol: IrFieldSymbol): IrLazyField {
        val type = declarationStubGenerator.typeTranslator.translateType(symbol.descriptor.type)
        return declarationStubGenerator.symbolTable.declareField(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                symbol.descriptor, type, fieldFactory = this::createFieldDeclaration
        ) as IrLazyField
    }

    private fun createFieldDeclaration(symbol: IrFieldSymbol): IrLazyField =
            IrLazyField(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol, declarationStubGenerator, declarationStubGenerator.typeTranslator
            )
}