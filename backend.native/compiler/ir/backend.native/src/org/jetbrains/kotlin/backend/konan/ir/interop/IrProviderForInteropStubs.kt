/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.backend.konan.ir.interop

import org.jetbrains.kotlin.backend.konan.descriptors.findPackage
import org.jetbrains.kotlin.backend.konan.descriptors.isFromInteropLibrary
import org.jetbrains.kotlin.backend.konan.isExternalObjCClass
import org.jetbrains.kotlin.backend.konan.isObjCClass
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.lazy.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.resolve.descriptorUtil.module

/**
 * Generates external IR declarations for descriptors from interop libraries.
 */
internal class IrProviderForInteropStubs(
        private val isSpecialCase: (IrSymbol) -> Boolean
) : LazyIrProvider {

    override lateinit var declarationStubGenerator: DeclarationStubGenerator


    val filesMap = mutableMapOf<PackageFragmentDescriptor, IrFile>()

    var module: IrModuleFragment? = null
        set(value) {
            if (value == null)
                error("Provide a valid non-null module")
            if (field != null)
                error("Module has already been set")
            field = value
            value.files += filesMap.values
        }
    private fun irParentFor(descriptor: ClassDescriptor): IrDeclarationContainer {
        val packageFragmentDescriptor = descriptor.findPackage()
        return filesMap.getOrPut(packageFragmentDescriptor) {
            IrFileImpl(NaiveSourceBasedFileEntryImpl("ObjCClasses"), packageFragmentDescriptor).also {
                this.module?.files?.add(it)
            }
        }
    }

    override fun getDeclaration(symbol: IrSymbol): IrLazyDeclarationBase? = when {
        !symbol.descriptor.module.isFromInteropLibrary() -> null
        isSpecialCase(symbol) -> null
        else -> provideIrDeclaration(symbol)
    }

    private fun provideIrDeclaration(symbol: IrSymbol): IrLazyDeclarationBase = when (symbol) {
        is IrSimpleFunctionSymbol -> provideIrFunction(symbol)
        is IrPropertySymbol -> provideIrProperty(symbol)
        is IrTypeAliasSymbol -> provideIrTypeAlias(symbol)
        is IrClassSymbol -> provideIrClass(symbol).also {
            if (symbol.descriptor.isObjCClass() && !symbol.descriptor.isCompanionObject) {
                val container = irParentFor(symbol.descriptor)
                it.parent = container
                container.declarations += it
            }
        }
        is IrConstructorSymbol -> provideIrConstructor(symbol)
        is IrFieldSymbol -> provideIrField(symbol)
        else -> error("Unsupported interop declaration: symbol=$symbol, descriptor=${symbol.descriptor}")
    }

    private fun provideIrFunction(symbol: IrSimpleFunctionSymbol): IrLazyFunction =
            declarationStubGenerator.symbolTable.declareSimpleFunction(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol.descriptor, this::createFunctionDeclaration
            ) as IrLazyFunction

    private fun createFunctionDeclaration(symbol: IrSimpleFunctionSymbol) =
            IrLazyFunction(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol, declarationStubGenerator, declarationStubGenerator.typeTranslator
            )

    private fun provideIrProperty(symbol: IrPropertySymbol): IrLazyProperty =
            declarationStubGenerator.symbolTable.declareProperty(
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