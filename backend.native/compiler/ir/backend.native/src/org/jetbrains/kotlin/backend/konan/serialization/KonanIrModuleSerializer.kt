package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.descriptors.propertyIfAccessor
import org.jetbrains.kotlin.backend.common.serialization.*
import org.jetbrains.kotlin.backend.konan.descriptors.isFromInteropLibrary
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.UniqId
import org.jetbrains.kotlin.ir.util.isLocal
import org.jetbrains.kotlin.resolve.descriptorUtil.module

private class KonanDeclarationTable(
        descriptorTable: DescriptorTable,
        globalDeclarationTable: GlobalDeclarationTable,
        startIndex: Long
) : DeclarationTable(descriptorTable, globalDeclarationTable, startIndex),
        DescriptorUniqIdAware by DeserializedDescriptorUniqIdAware {

    /**
     * It is incorrect to compute UniqId for declarations from metadata-based libraries.
     * Instead we should get precomputed value from metadata.
     */
    override fun tryComputeBackendSpecificUniqId(declaration: IrDeclaration): UniqId? {
        // Local declarations should be skipped because they may get here in the following case.
        //
        //  Parent class A is declared in metadata-based library and child class B : A declared in sourcecode-based
        //  library.
        //
        // It will lead to presence of FAKE_OVERRIDE functions, whose dispatch receiver parameter's descriptor
        // is from metadata-based library.
        return if (declaration.descriptor.module.isFromInteropLibrary() && !declaration.isLocalDeclaration()) {
            // Property accessor doesn't provide UniqId so we need to get it from the property itself.
            UniqId(declaration.descriptor.propertyIfAccessor.getUniqId()
                    ?: error("No uniq id found for ${declaration.descriptor}"))
        } else {
            null
        }
    }

    // Copy-pasted from DeclarationTable class.
    // TODO: Make that declaration protected and remove this one.
    private fun IrDeclaration.isLocalDeclaration(): Boolean {
        return origin == IrDeclarationOrigin.FAKE_OVERRIDE
                || !isExportedDeclaration(this)
                || this is IrValueDeclaration
                || this is IrAnonymousInitializer
                || this is IrLocalDelegatedProperty
    }
}

class KonanIrModuleSerializer(
    logger: LoggingContext,
    irBuiltIns: IrBuiltIns,
    private val descriptorTable: DescriptorTable,
    private val expectDescriptorToSymbol: MutableMap<DeclarationDescriptor, IrSymbol>,
    val skipExpects: Boolean
) : IrModuleSerializer<KonanIrFileSerializer>(logger) {


    private val globalDeclarationTable = KonanGlobalDeclarationTable(irBuiltIns)

    override fun createSerializerForFile(file: IrFile): KonanIrFileSerializer =
            KonanIrFileSerializer(logger, KonanDeclarationTable(descriptorTable, globalDeclarationTable, 0), expectDescriptorToSymbol, skipExpects = skipExpects)
}
