package dev.robakowski.yang2opcua

import org.opcfoundation.ua.modeldesign.*
import org.opendaylight.yangtools.yang.model.api.*
import org.opendaylight.yangtools.yang.model.api.meta.EffectiveStatement
import org.opendaylight.yangtools.yang.model.api.stmt.*
import org.opendaylight.yangtools.yang.model.api.type.*
import java.time.Instant
import javax.xml.namespace.QName as JQName
import org.opendaylight.yangtools.yang.common.QName as YQName

const val OPC_UA_BASE_URI = "http://opcfoundation.org/UA/"

@Suppress("UnstableApiUsage", "MemberVisibilityCanBePrivate")
class OpcuaModelBuilder(yangModel: EffectiveModelContext, val mainName: String) {
    private val opcuaRootModel = newModelDesign()
    fun build() = opcuaRootModel

    private val registeredTypes: MutableSet<JQName> = mutableSetOf()

    init {
        yangModel.moduleStatements.forEach { (moduleName, module) ->
            println("processing module $moduleName")
            addYangModule(module)
        }
    }

    //    @Deprecated("This should probably be handled in the future.")
    val ignoredForNow = Unit

    fun newModelDesign() = ModelDesign().apply {
        targetNamespace = "urn:yang2opcua:$mainName"

        namespaces = NamespaceTable().apply {
            namespace += Namespace().apply {
                name = "OpcUa"
                version = "1.03"
                publicationDate = "2013-12-02T00:00:00Z"
                prefix = "Opc.Ua"
                internalPrefix = "Opc.Ua.Server"
                xmlNamespace = "http://opcfoundation.org/UA/2008/02/Types.xsd"
                xmlPrefix = "OpcUa"
                value = OPC_UA_BASE_URI
            }

            namespace += Namespace().apply {
                name = mainName
                version = "0.1"
                publicationDate = Instant.now().toString()
                prefix = mainName
                xmlPrefix = mainName
                xmlNamespace = targetNamespace
            }
        }
    }

    fun addYangModule(yangModule: ModuleEffectiveStatement) {
        yangModule.effectiveSubstatements().forEach {
            when (it) {
                is NamespaceEffectiveStatement -> ignoredForNow
                is PrefixEffectiveStatement -> ignoredForNow
                is ImportEffectiveStatement -> ignoredForNow
                is OrganizationEffectiveStatement -> ignoredForNow
                is ContactEffectiveStatement -> ignoredForNow
                is DescriptionEffectiveStatement -> ignoredForNow
                is RevisionEffectiveStatement -> ignoredForNow
                is FeatureEffectiveStatement -> ignoredForNow
                is ReferenceEffectiveStatement -> ignoredForNow
                is YangVersionEffectiveStatement -> ignoredForNow
                is IdentityEffectiveStatement -> addIdentityType(it)
                is ContainerEffectiveStatement -> addContainerObjectType(it)
                is TypedefEffectiveStatement -> addTypedef(it)

                // we process effective models, so this should already be inlined
                is GroupingEffectiveStatement -> ignoredForNow

                // we process effective models, so this should already be inlined
                is AugmentEffectiveStatement -> ignoredForNow
                else -> TODO("Effective Statement of type ${it.javaClass.simpleName} not supported")
            }
        }
    }

    fun NodeDesign.copyDescription(documentedNode: DocumentedNode) {
        documentedNode.description?.orElse(null)?.let { desc ->
            description = LocalizedText().apply { value = desc }
        }
    }

    fun Parameter.copyDescription(documentedNode: DocumentedNode) {
        documentedNode.description?.orElse(null)?.let { desc ->
            description = LocalizedText().apply { value = desc }
        }
    }

    fun SchemaNode.getSymbolicName(suffix: String = ""): JQName =
        JQName(qName.namespace.toString(), path.pathFromRoot.joinToString("/") { it.localName } + suffix)

    fun getDataType(type: TypeDefinition<*>): JQName {
        if (type.isPrimitive()) {
            return translatePrimitiveType(type) ?: error("unknown primitive type [${type.javaClass.simpleName}]")
        }
        val symbolicName = type.getSymbolicName("DataType")
        if (symbolicName !in registeredTypes) registerDataType(type)
        return symbolicName
    }

    fun registerDataType(type: TypeDefinition<*>) {
        val typeSymbolicName = type.getSymbolicName("DataType")
        registeredTypes += typeSymbolicName

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += DataTypeDesign().apply {
            symbolicName = typeSymbolicName
            copyDescription(type)

            val base = type.baseType
                ?: type // for weird cases where we get a kinda-primitive type here, like an enum from a leaf
            baseType = translatePrimitiveType(base)
                ?: error("typedef with base type [${base.javaClass.simpleName}] not supported")

            if (base is EnumTypeDefinition) {
                fields = ListOfFields().apply {
                    base.values.forEach {
                        field += Parameter().apply {
                            copyDescription(it)
                            name = it.name
                            identifier = it.value
                        }
                    }
                }
            }
        }
    }

    fun translatePrimitiveType(type: TypeDefinition<*>): JQName? {
        return when (type) {
            is StringTypeDefinition -> "String"
            is BinaryTypeDefinition -> "ByteString"
            is BitsTypeDefinition -> "UInt32" // TODO: bit flags?
            is BooleanTypeDefinition -> "Boolean"
            is DecimalTypeDefinition -> "Int64" // TODO: precision?
            is EmptyTypeDefinition -> "Structure"
            is EnumTypeDefinition -> "EnumeratedType"
            is IdentityrefTypeDefinition -> "NodeId" // TODO identityrefs should probably be implemented as references, not data types
            is IdentityTypeDefinition -> TODO("I think this is impossible?")
            is InstanceIdentifierTypeDefinition -> "ExpandedNodeId" // TODO: is this correct???

            is Int8TypeDefinition -> "SByte"
            is Int16TypeDefinition -> "Int16"
            is Int32TypeDefinition -> "Int32"
            is Int64TypeDefinition -> "Int64"

            is Uint8TypeDefinition -> "Byte"
            is Uint16TypeDefinition -> "UInt16"
            is Uint32TypeDefinition -> "UInt32"
            is Uint64TypeDefinition -> "UInt64"

            is LeafrefTypeDefinition -> "NodeId" // TODO: is this correct???
            is UnionTypeDefinition -> "Variant" // TODO: can we somehow further restrict this?

            else -> null
        }?.let(::opcuaRef)
    }

    fun opcuaRef(ref: String) = JQName(OPC_UA_BASE_URI, ref)

    /** Adds a primitive typedef */
    fun addTypedef(typedef: TypedefEffectiveStatement) {
        typedef as SchemaNode
        val symbolicName = typedef.getSymbolicName("DataType")
        // type might have already been registered by some leaf that needed it
        if (symbolicName !in registeredTypes) registerDataType(typedef.typeDefinition)
    }

    fun addIdentityType(identity: IdentityEffectiveStatement) {
        identity as IdentitySchemaNode

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += ObjectTypeDesign().apply {
            symbolicName = identity.getSymbolicName()
            copyDescription(identity)

            if (identity.baseIdentities.isNotEmpty()) {
                baseType = identity.baseIdentities
                    .singleOrNull().let { it ?: error("multiple inheritance is not supported [$identity]") }
                    .getSymbolicName()
            }
        }
    }

    fun ObjectTypeDesign.makeChildren(yangChildren: Collection<EffectiveStatement<*, *>>): ListOfChildren {
        return ListOfChildren().apply {
            yangChildren.forEach { child ->
                when (child) {
                    is ConfigEffectiveStatement -> ignoredForNow
                    is StatusEffectiveStatement -> ignoredForNow

                    // TODO: add feature processing (this should be probably implemented somewhere in opendaylight
                    //  already for effective statements)
                    is IfFeatureEffectiveStatement -> ignoredForNow

                    // we process effective models, so this should already be inlined
                    is UsesEffectiveStatement -> ignoredForNow

                    // TODO: are such schema conditionals supported in opcua?
                    is WhenEffectiveStatement -> ignoredForNow

                    // TODO: maybe add to the description?
                    is ReferenceEffectiveStatement -> ignoredForNow

                    // handled one level above
                    is DescriptionEffectiveStatement -> ignoredForNow

                    // TODO: are such constraints even supported in opcua?
                    is UniqueEffectiveStatement -> ignoredForNow

                    // we process effective models, so this should already be inlined
                    is AugmentEffectiveStatement -> ignoredForNow

                    // should be handled in ListEffectiveStatement below
                    is KeyEffectiveStatement -> ignoredForNow

                    is ListEffectiveStatement -> {
                        // so, the mapping from yang lists goes like this
                        // 1) create an object type for the list object
                        // 2) create an object type for the list element
                        // 3) create a reference type that will represent that elements belong to the list
                        // 4) add the list object of type from 1) to the current type design
                        // TODO: validate if this is a correct encoding

                        child as ListSchemaNode
                        val listType = addListObjectType(child)
                        val elementType = addListElementObjectType(child)
                        val referenceType = addListElementReferenceType(child)
                        objectOrVariableOrProperty += ObjectDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            if (description != null) {
                                description.value += "\n\telement type: ${elementType.symbolicName}" +
                                        "\n\treference type: ${referenceType.symbolicName}" +
                                        "\n\telement key: ${child.keyDefinition.joinToString()}"
                            }
                            typeDefinition = listType.symbolicName
                        }
                    }
                    is LeafEffectiveStatement -> { // TODO: special-case this and model idnetityref leafs as references
                        child as LeafSchemaNode
                        val leafType = addLeafType(child)

                        objectOrVariableOrProperty += VariableDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            valueRank = ValueRank.SCALAR
                            typeDefinition = leafType.symbolicName
                        }
                    }
                    is LeafListEffectiveStatement -> {
                        child as LeafListSchemaNode
                        val leafListType = addLeafListType(child)

                        objectOrVariableOrProperty += VariableDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            valueRank = ValueRank.ARRAY
                            typeDefinition = leafListType.symbolicName
                        }
                    }
                    is ContainerEffectiveStatement -> {
                        child as ContainerSchemaNode
                        val containerType = addContainerObjectType(child)
                        objectOrVariableOrProperty += ObjectDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            typeDefinition = containerType.symbolicName
                        }
                    }
                    is ChoiceEffectiveStatement -> {
                        // I guess we can implement it as an abstract base class and a concrete class for each of the cases
                        child as ChoiceSchemaNode
                        val choiceType = addChoiceObjectType(child)
                        objectOrVariableOrProperty += ObjectDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            typeDefinition = choiceType.symbolicName
                        }
                    }
                    else -> TODO("container child of type [${child.javaClass.simpleName.ifEmpty { child.javaClass.name }}] not supported")
                }
            }
        }
    }

    fun addContainerObjectType(container: ContainerEffectiveStatement): ObjectTypeDesign {
        container as ContainerSchemaNode
        val res = ObjectTypeDesign().apply {
            symbolicName = container.getSymbolicName("ContainerType")
            copyDescription(container)
            baseType = opcuaRef("BaseObjectType")

            children = makeChildren(container.effectiveSubstatements())
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addListObjectType(list: ListEffectiveStatement): ObjectTypeDesign {
        list as ListSchemaNode
        val res = ObjectTypeDesign().apply {
            symbolicName = list.getSymbolicName("ListType")
            copyDescription(list)
            baseType = opcuaRef("BaseObjectType")
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addListElementObjectType(list: ListEffectiveStatement): ObjectTypeDesign {
        list as ListSchemaNode
        val res = ObjectTypeDesign().apply {
            symbolicName = list.getSymbolicName("ListElementType")
            copyDescription(list)
            baseType = opcuaRef("BaseObjectType")

            children = makeChildren(list.effectiveSubstatements())
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addListElementReferenceType(list: ListEffectiveStatement): ReferenceTypeDesign {
        list as ListSchemaNode
        val res = ReferenceTypeDesign().apply {
            symbolicName = list.getSymbolicName("ListElementReferenceType")
            copyDescription(list)
            baseType = opcuaRef("HasComponent") // TODO: or maybe `Aggregates`?
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addLeafType(leaf: LeafEffectiveStatement): VariableTypeDesign {
        leaf as LeafSchemaNode
        val res = VariableTypeDesign().apply {
            symbolicName = leaf.getSymbolicName("LeafType")
            copyDescription(leaf)
            valueRank = ValueRank.SCALAR
            baseType = opcuaRef("BaseDataVariableType")
            dataType = getDataType(leaf.type)
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addLeafListType(leafList: LeafListEffectiveStatement): VariableTypeDesign {
        leafList as LeafListSchemaNode
        val res = VariableTypeDesign().apply {
            symbolicName = leafList.getSymbolicName("LeafType")
            copyDescription(leafList)
            valueRank = ValueRank.ARRAY
            baseType = opcuaRef("BaseDataVariableType")
            dataType = getDataType(leafList.type)
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addChoiceObjectType(choice: ChoiceEffectiveStatement): ObjectTypeDesign {
        choice as ChoiceSchemaNode
        val choiceType = ObjectTypeDesign().apply {
            symbolicName = choice.getSymbolicName("ChoiceType")
            isIsAbstract = true
            copyDescription(choice)
            baseType = opcuaRef("BaseObjectType")
        }

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += choiceType
        choice.cases.forEach { (_, caseNode) -> addCaseObjectType(caseNode, choiceType.symbolicName) }

        return choiceType
    }

    fun addCaseObjectType(case: CaseSchemaNode, choiceType: JQName): ObjectTypeDesign {
        case as CaseEffectiveStatement
        val res = ObjectTypeDesign().apply {
            symbolicName = case.getSymbolicName("CaseType")
            copyDescription(case)
            baseType = choiceType

            children = makeChildren(case.effectiveSubstatements())
        }

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }
}

private fun TypeDefinition<*>.isPrimitive() =
    baseType == null &&
            // not very primitive types that yang considers primitive, but we don't want to
            this !is EnumTypeDefinition &&
            this !is EmptyTypeDefinition &&
            this !is UnionTypeDefinition &&
            this !is IdentityrefTypeDefinition &&
            this !is InstanceIdentifierTypeDefinition &&
            this !is LeafrefTypeDefinition