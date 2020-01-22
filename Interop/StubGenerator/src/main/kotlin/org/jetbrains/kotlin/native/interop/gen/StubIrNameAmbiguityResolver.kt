package org.jetbrains.kotlin.native.interop.gen

import org.jetbrains.kotlin.native.interop.indexer.Type

private class FakeMappingBridgeGenerator : MappingBridgeGenerator {
    override fun kotlinToNative(
            builder: KotlinCodeBuilder,
            nativeBacked: NativeBacked,
            returnType: Type,
            kotlinValues: List<TypedKotlinValue>,
            independent: Boolean,
            block: NativeCodeBuilder.(nativeValues: List<NativeExpression>) -> NativeExpression
    ): KotlinExpression {
        TODO("not implemented")
    }

    override fun nativeToKotlin(
            builder: NativeCodeBuilder,
            nativeBacked: NativeBacked,
            returnType: Type,
            nativeValues: List<TypedNativeValue>,
            block: KotlinCodeBuilder.(kotlinValues: List<KotlinExpression>) -> KotlinExpression
    ): NativeExpression {
        TODO("not implemented")
    }
}

class UniqueNames(
        private val alteredProperties: Map<PropertyStub, String>
) {
    fun uniqueNameFor(propertyStub: PropertyStub): String =
            alteredProperties[propertyStub] ?: propertyStub.name
}

class StubIrNameAmbiguityResolver(
        private val context: StubIrContext,
        private val builderResult: StubIrBuilderResult
) {
    private val alteredProperties: MutableMap<PropertyStub, String> =
            mutableMapOf()

    fun resolve(): UniqueNames {
        builderResult.stubs.accept(visitor, null)
        return UniqueNames(alteredProperties.toMap())
    }

    private val kotlinFile = object : KotlinFile(
            context.configuration.pkgName,
            builderResult.stubs.computeNamesToBeDeclared(context.configuration.pkgName)
    ) {
        override val mappingBridgeGenerator: MappingBridgeGenerator = FakeMappingBridgeGenerator()
    }

    private val visitor = object : StubIrVisitor<StubContainer?, Unit> {
        override fun visitClass(element: ClassStub, data: StubContainer?) {
        }

        override fun visitTypealias(element: TypealiasStub, data: StubContainer?) {
        }

        override fun visitFunction(element: FunctionStub, data: StubContainer?) {}

        override fun visitProperty(element: PropertyStub, data: StubContainer?) {
            if (data?.isTopLevelContainer == true) {
                val newName = getTopLevelPropertyDeclarationName(kotlinFile, element.name)
                if (newName != element.name) {
                    alteredProperties[element] = newName
                }
            }
        }

        override fun visitConstructor(constructorStub: ConstructorStub, data: StubContainer?) {}

        override fun visitPropertyAccessor(propertyAccessor: PropertyAccessor, data: StubContainer?) {}

        override fun visitSimpleStubContainer(simpleStubContainer: SimpleStubContainer, data: StubContainer?) {
            simpleStubContainer.children.forEach { it.accept(this, simpleStubContainer) }
            simpleStubContainer.simpleContainers.forEach { visitSimpleStubContainer(it, simpleStubContainer) }
        }
    }

    private val StubContainer.isTopLevelContainer: Boolean
        get() = this == builderResult.stubs

    // Try to use the provided name. If failed, mangle it with underscore and try again:
    private tailrec fun getTopLevelPropertyDeclarationName(scope: KotlinScope, name: String): String =
            scope.declareProperty(name) ?: getTopLevelPropertyDeclarationName(scope, name + "_")
}