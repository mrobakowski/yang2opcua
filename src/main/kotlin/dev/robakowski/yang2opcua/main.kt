@file:Suppress("UnstableApiUsage")

package dev.robakowski.yang2opcua

import org.opcfoundation.ua.modeldesign.*
import org.opendaylight.yangtools.yang.model.api.ContainerSchemaNode
import org.opendaylight.yangtools.yang.model.api.DocumentedNode
import org.opendaylight.yangtools.yang.model.api.IdentitySchemaNode
import org.opendaylight.yangtools.yang.model.api.meta.EffectiveStatement
import org.opendaylight.yangtools.yang.model.api.stmt.*
import org.opendaylight.yangtools.yang.model.api.type.*
import org.opendaylight.yangtools.yang.model.parser.api.YangSyntaxErrorException
import org.opendaylight.yangtools.yang.model.repo.api.YangTextSchemaSource
import org.opendaylight.yangtools.yang.parser.impl.YangParserFactoryImpl
import java.io.File
import java.time.Instant
import javax.xml.bind.JAXBContext
import javax.xml.bind.Marshaller
import javax.xml.namespace.QName

const val OPC_UA_BASE_URI = "http://opcfoundation.org/UA/"

fun main() {
    val mainFile = YangTextSchemaSource.forFile(File("./yang/experimental/ieee/802.1/ieee802-dot1q-psfp.yang"))
    val libs = File("./yang/standard").walkBottomUp()
        .onEnter {
            !it.toString().contains("yang/standard/mef") // files in mef folder cause the parser to break
        }
        .filter { it.isFile && it.extension == "yang" }
        .mapNotNull {
            try {
                YangTextSchemaSource.forFile(it)
            } catch (e: Exception) {
                println("unexpected error with $it\n${e.message}")
                null
            }
        }.toList()

    val parser = YangParserFactoryImpl().createParser().apply {
        addSource(mainFile)
        libs.forEach { lib ->
            try {
                addLibSource(lib)
            } catch (e: YangSyntaxErrorException) {
                println("syntax error: ${e.message}")
            }
        }
    }

    val yangModel = parser.buildEffectiveModel()
    val opcuaModel = newModelDesign("ieee802-dot1q-psfp")

    yangModel.moduleStatements.forEach { (moduleName, module) ->
        println("processing module $moduleName")
        opcuaModel.addYangModule(module)
    }

    val context = JAXBContext.newInstance(ModelDesign::class.java)
    val marshaller = context.createMarshaller().apply { setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true) }

    marshaller.marshal(opcuaModel, System.out)
    marshaller.marshal(opcuaModel, File("./build/ieee802-dot1q-psfp.Model.xml"))
}

fun newModelDesign(namespace: String): ModelDesign {
    return ModelDesign().apply {
        targetNamespace = "urn:yang2opcua:$namespace"

        namespaces {
            namespace {
                name = "OpcUa"
                version = "1.03"
                publicationDate = "2013-12-02T00:00:00Z"
                prefix = "Opc.Ua"
                internalPrefix = "Opc.Ua.Server"
                xmlNamespace = "http://opcfoundation.org/UA/2008/02/Types.xsd"
                xmlPrefix = "OpcUa"
                value = OPC_UA_BASE_URI
            }

            namespace {
                name = namespace
                version = "0.1"
                publicationDate = Instant.now().toString()
                prefix = namespace
                xmlPrefix = namespace
                xmlNamespace = this@apply.targetNamespace
            }
        }
    }
}

@Deprecated("This should probably be handled in the future.")
val ignoredForNow = Unit

fun ModelDesign.addYangModule(yangModule: ModuleEffectiveStatement) {

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

            // hopefully other effective statements have groupings already inlined by opendaylight, so this is safe to
            // ignore
            is GroupingEffectiveStatement -> ignoredForNow

            // hopefully other effective statements have augmentations already inlined by opendaylight, so this is safe
            // to ignore
            is AugmentEffectiveStatement -> ignoredForNow
            else -> TODO("Effective Statement of type ${it.javaClass.simpleName} not supported")
        }
    }
}

fun org.opendaylight.yangtools.yang.common.QName.toJavaQName(): QName =
    QName(namespace.toString(), localName)

fun ModelDesign.addIdentityType(identity: IdentityEffectiveStatement) {
    identity as IdentitySchemaNode

    this.objectTypeOrVariableTypeOrReferenceType += ObjectTypeDesign().apply {
        symbolicName = identity.identifier.toJavaQName()
        copyDescription(identity)

        if (identity.baseIdentities.isNotEmpty()) {
            baseType = identity.baseIdentities
                .singleOrNull().let { it ?: error("multiple inheritance is not supported [$identity]") }
                .qName.toJavaQName()
        }
    }
}

fun ModelDesign.addContainer(container: ContainerEffectiveStatement) {
    container as ContainerSchemaNode

    this.objectTypeOrVariableTypeOrReferenceType += ObjectTypeDesign().apply {
        symbolicName = container.identifier.toJavaQName()
        copyDescription(container)
        baseType = QName(OPC_UA_BASE_URI, "BaseObjectType")

        children = makeContainerChildren(container.effectiveSubstatements())
    }
}

fun makeContainerChildren(containerChildren: Collection<EffectiveStatement<*, *>>) =
    ListOfChildren().apply {
        containerChildren.forEach {
            when (it) {
                is DescriptionEffectiveStatement -> ignoredForNow
                is ListEffectiveStatement -> {
                    objectOrVariableOrProperty += VariableDesign().apply {
                        valueRank = ValueRank.ARRAY
                        // TODO: variables only seem able to store DataType instances, and we probably want Objects here...
                        //  do we just store NodeIds of the objects? this whole OPC UA is very confusing
                    }
                }
                else -> TODO("container child of type [${it.javaClass.simpleName}] not supported")
            }
        }
    }

fun NodeDesign.copyDescription(documentedNode: DocumentedNode) {
    documentedNode.description?.orElse(null)?.let { desc ->
        description = LocalizedText().apply { value = desc }
    }
}

fun ModelDesign.addTypedef(typedef: TypedefEffectiveStatement) {
    objectTypeOrVariableTypeOrReferenceType += DataTypeDesign().apply {
        symbolicName = typedef.argument()!!.toJavaQName()
        copyDescription(typedef as DocumentedNode)

        val base = typedef.asTypeEffectiveStatement().typeDefinition.baseType
        fun t(type: String) = QName(OPC_UA_BASE_URI, type)
        var isEnumeration = false
        baseType = when (base) {
            is StringTypeDefinition -> t("String")
            is BinaryTypeDefinition -> t("ByteString")
            is BitsTypeDefinition -> t("UInt32")
            is BooleanTypeDefinition -> t("Boolean")
            is DecimalTypeDefinition -> t("Int64")
            is EmptyTypeDefinition -> TODO("Implement empty as a structure with no fields")
            is EnumTypeDefinition -> {
                isEnumeration = true
                t("EnumeratedType")
            }
            is IdentityrefTypeDefinition -> t("NodeId") // TODO identityrefs should probably be implemented as references, not data types
            is IdentityTypeDefinition -> TODO("I think this is impossible?")
            is InstanceIdentifierTypeDefinition -> t("ExpandedNodeId") // TODO: is this correct???

            is Int8TypeDefinition -> t("SByte")
            is Int16TypeDefinition -> t("Int16")
            is Int32TypeDefinition -> t("Int32")
            is Int64TypeDefinition -> t("Int64")

            is Uint8TypeDefinition -> t("Byte")
            is Uint16TypeDefinition -> t("UInt16")
            is Uint32TypeDefinition -> t("UInt32")
            is Uint64TypeDefinition -> t("UInt64")

            is LeafrefTypeDefinition -> t("NodeId") // TODO: is this correct???
            is UnionTypeDefinition -> t("Variant") // TODO: can we somehow further restrict this?

            else -> error("typedef with base type [${base.javaClass.simpleName}] not supported")
        }

        if (isEnumeration) {
            fields = ListOfFields().apply {
                (base as EnumTypeDefinition).values.forEach {
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