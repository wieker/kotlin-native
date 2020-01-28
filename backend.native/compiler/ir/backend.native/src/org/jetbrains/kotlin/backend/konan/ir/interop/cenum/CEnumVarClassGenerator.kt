/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.backend.konan.ir.interop.cenum

import org.jetbrains.kotlin.backend.konan.InteropBuiltIns
import org.jetbrains.kotlin.backend.konan.descriptors.getArgumentValueOrNull
import org.jetbrains.kotlin.backend.konan.ir.interop.DescriptorToIrTranslationMixin
import org.jetbrains.kotlin.backend.konan.objcexport.getErasedTypeClass
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.addMember
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.util.irBuilder
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.typeUtil.isUnsignedNumberType

internal class CEnumVarClassGenerator(
        override val irBuiltIns: IrBuiltIns,
        override val symbolTable: SymbolTable,
        override val typeTranslator: TypeTranslator,
        private val interopBuiltIns: InteropBuiltIns
) : DescriptorToIrTranslationMixin {

    companion object {
        private val typeSizeAnnotation = FqName("kotlinx.cinterop.internal.CEnumVarTypeSize")
    }

    fun generate(enumIrClass: IrClass): IrClass {
        val enumVarClassDescriptor = enumIrClass.descriptor.unsubstitutedMemberScope
                .getContributedClassifier(Name.identifier("Var"), NoLookupLocation.FROM_BACKEND)!! as ClassDescriptor
        return createClass(enumVarClassDescriptor) { enumVarClass ->
            enumVarClass.addMember(createPrimaryConstructor(enumVarClass))
            enumVarClass.addMember(createCompanionObject(enumVarClass))
            enumVarClass.addMember(createValueProperty(enumVarClass))
        }
    }

    private fun createValueProperty(enumVarClass: IrClass): IrProperty {
        val valuePropertyDescriptor = enumVarClass.descriptor.unsubstitutedMemberScope
                .getContributedVariables(Name.identifier("value"), NoLookupLocation.FROM_BACKEND).single()
        val irValueProperty = createProperty(valuePropertyDescriptor)
        symbolTable.withScope(valuePropertyDescriptor) {
            irValueProperty.getter = declareSimpleIrFunction(valuePropertyDescriptor.getter!!).also { getter ->
                getter.correspondingPropertySymbol = irValueProperty.symbol
            }
            irValueProperty.setter = declareSimpleIrFunction(valuePropertyDescriptor.setter!!).also { setter ->
                setter.correspondingPropertySymbol = irValueProperty.symbol
            }
        }
        return irValueProperty
    }

    private fun createPrimaryConstructor(enumVarClass: IrClass): IrConstructor {
        val irConstructor = createConstructor(enumVarClass.descriptor.unsubstitutedPrimaryConstructor!!)
        val enumVarConstructorSymbol = symbolTable.referenceConstructor(
                interopBuiltIns.cEnumVar.unsubstitutedPrimaryConstructor!!
        )
        irConstructor.body = irBuilder(irBuiltIns, irConstructor.symbol).irBlockBody {
            +IrDelegatingConstructorCallImpl(
                    startOffset, endOffset, context.irBuiltIns.unitType, enumVarConstructorSymbol
            ).also {
                it.putValueArgument(0, irGet(irConstructor.valueParameters[0]))
            }
            +IrInstanceInitializerCallImpl(
                    startOffset, endOffset,
                    symbolTable.referenceClass(enumVarClass.descriptor),
                    context.irBuiltIns.unitType
            )
        }
        return irConstructor
    }

    private fun createCompanionObject(enumVarClass: IrClass): IrClass =
            createClass(enumVarClass.descriptor.companionObjectDescriptor!!) { companionIrClass ->
                val typeSize = companionIrClass.descriptor.annotations
                        .findAnnotation(typeSizeAnnotation)!!
                        .getArgumentValueOrNull<Int>("size")!!
                companionIrClass.addMember(createCompanionConstructor(companionIrClass.descriptor, typeSize))
            }

    private fun createCompanionConstructor(companionObjectDescriptor: ClassDescriptor, typeSize: Int): IrConstructor {
        val irConstructor = createConstructor(companionObjectDescriptor.unsubstitutedPrimaryConstructor!!)
        val superConstructorSymbol = symbolTable.referenceConstructor(interopBuiltIns.cPrimitiveVarType.unsubstitutedPrimaryConstructor!!)
        irConstructor.body = irBuilder(irBuiltIns, irConstructor.symbol).irBlockBody {
            +IrDelegatingConstructorCallImpl(
                    startOffset, endOffset, context.irBuiltIns.unitType,
                    superConstructorSymbol
            ).also {
                it.putValueArgument(0, irInt(typeSize))
            }
            +IrInstanceInitializerCallImpl(
                    startOffset, endOffset,
                    symbolTable.referenceClass(companionObjectDescriptor),
                    context.irBuiltIns.unitType
            )
        }
        return irConstructor
    }
}