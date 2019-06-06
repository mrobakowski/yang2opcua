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
                is IdentityEffectiveStatement -> addIdentityType(it)
                is ContainerEffectiveStatement -> addContainer(it)
                is TypedefEffectiveStatement -> addTypedef(it)
                is ReferenceEffectiveStatement -> ignoredForNow
                is YangVersionEffectiveStatement -> ignoredForNow

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

    fun SchemaNode.getSymbolicName(suffix: String = ""): JQName =
        JQName(qName.namespace.toString(), path.pathFromRoot.joinToString("/") { it.localName } + suffix)

    fun opcuaRef(ref: String) = JQName(OPC_UA_BASE_URI, ref)

    /** Adds a primitive typedef */
    fun addTypedef(typedef: TypedefEffectiveStatement) {
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += DataTypeDesign().apply {
            symbolicName = typedef.typeDefinition.getSymbolicName()
            copyDescription(typedef as DocumentedNode)

            val base = typedef.asTypeEffectiveStatement().typeDefinition.baseType
            baseType = when (base) {
                is StringTypeDefinition -> "String"
                is BinaryTypeDefinition -> "ByteString"
                is BitsTypeDefinition -> "UInt32"
                is BooleanTypeDefinition -> "Boolean"
                is DecimalTypeDefinition -> "Int64"
                is EmptyTypeDefinition -> "Structure"
                is EnumTypeDefinition -> {
                    fields = ListOfFields().apply {
                        base.values.forEach {
                            field += Parameter().apply {
                                copyDescription(it)
                                name = it.name
                                identifier = it.value
                            }
                        }
                    }

                    "EnumeratedType"
                }
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

                else -> error("typedef with base type [${base.javaClass.simpleName}] not supported")
            }.let(::opcuaRef)
        }
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

    fun addContainer(container: ContainerEffectiveStatement) {
        container as ContainerSchemaNode

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += ObjectTypeDesign().apply {
            symbolicName = container.getSymbolicName()
            copyDescription(container)
            baseType = opcuaRef("BaseObjectType")

            children = makeChildren(container.effectiveSubstatements())
        }
    }

    fun ObjectTypeDesign.makeChildren(yangChildren: Collection<EffectiveStatement<*, *>>): ListOfChildren {
        return ListOfChildren().apply {
            yangChildren.forEach {
                when (it) {
                    is DescriptionEffectiveStatement -> ignoredForNow // handled one level above
                    is ReferenceEffectiveStatement -> ignoredForNow // TODO: maybe add to the description?
                    is ConfigEffectiveStatement -> ignoredForNow
                    is StatusEffectiveStatement -> ignoredForNow
                    is KeyEffectiveStatement -> ignoredForNow // should be handled in ListEffectiveStatement below
                    is UniqueEffectiveStatement -> ignoredForNow // TODO
                    is ListEffectiveStatement -> {
                        // so, the mapping from yang lists goes like this
                        // 1) create an object type for the list object
                        // 2) create an object type for the list element
                        // 3) create a reference type that will represent that elements belong to the list
                        // 4) add the list object of type from 1) to the current type design
                        // TODO: validate if this is a correct encoding

                        it as ListSchemaNode
                        val listType = addListObjectType(it)
                        val elementType = addListElementObjectType(it)
                        val referenceType = addListElementReferenceType(it)
                        objectOrVariableOrProperty += ObjectDesign().apply {
                            symbolicName = it.getSymbolicName()
                            copyDescription(it)
                            description.value += "\n\telement type: ${elementType.symbolicName}" +
                                    "\n\treference type: ${referenceType.symbolicName}" +
                                    "\n\telement key: ${it.keyDefinition.joinToString()}"
                            typeDefinition = listType.symbolicName
                        }
                    }
                    is LeafEffectiveStatement -> {
                        it as LeafSchemaNode
                        val leafType = addLeafType(it)

                        objectOrVariableOrProperty += VariableDesign().apply {
                            symbolicName = it.getSymbolicName()
                            copyDescription(it)
                            valueRank = ValueRank.SCALAR
                            typeDefinition = leafType.symbolicName
                        }
                    }
                    is LeafListEffectiveStatement -> {
                        it as LeafListSchemaNode
                        val leafListType = addLeafListType(it)

                        objectOrVariableOrProperty += VariableDesign().apply {
                            symbolicName = it.getSymbolicName()
                            copyDescription(it)
                            valueRank = ValueRank.ARRAY
                            typeDefinition = leafListType.symbolicName
                        }
                    }
                    is ContainerEffectiveStatement -> {
                        // TODO: genericize the module treatment of containers so nested containers work
                    }
                    // we process effective models, so this should already be inlined
                    is AugmentEffectiveStatement -> ignoredForNow
                    else -> TODO("container child of type [${it.javaClass.simpleName.ifEmpty { it.javaClass.name }}] not supported")
                }
            }
        }
    }

    fun addListObjectType(les: ListEffectiveStatement): ObjectTypeDesign {
        les as ListSchemaNode
        val res = ObjectTypeDesign().apply {
            symbolicName = les.getSymbolicName("ListType")
            copyDescription(les)
            baseType = opcuaRef("BaseObjectType")
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addListElementObjectType(les: ListEffectiveStatement): ObjectTypeDesign {
        les as ListSchemaNode
        val res = ObjectTypeDesign().apply {
            symbolicName = les.getSymbolicName("ListElementType")
            copyDescription(les)
            baseType = opcuaRef("BaseObjectType")

            children = makeChildren(les.effectiveSubstatements())
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addListElementReferenceType(les: ListEffectiveStatement): ReferenceTypeDesign {
        les as ListSchemaNode
        val res = ReferenceTypeDesign().apply {
            symbolicName = les.getSymbolicName("ListElementReferenceType")
            copyDescription(les)
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
            dataType = leaf.type.getSymbolicName()
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addLeafListType(leaf: LeafListEffectiveStatement): VariableTypeDesign {
        leaf as LeafListSchemaNode
        val res = VariableTypeDesign().apply {
            symbolicName = leaf.getSymbolicName("LeafType")
            copyDescription(leaf)
            valueRank = ValueRank.ARRAY
            baseType = opcuaRef("BaseDataVariableType")
            dataType = leaf.type.getSymbolicName()
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }
}