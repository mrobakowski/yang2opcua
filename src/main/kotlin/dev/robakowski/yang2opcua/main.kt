@file:Suppress("UnstableApiUsage")

package dev.robakowski.yang2opcua

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.google.gson.Gson
import org.opcfoundation.ua.modeldesign.ModelDesign
import org.opendaylight.yangtools.yang.data.codec.gson.JSONCodecFactorySupplier
import org.opendaylight.yangtools.yang.data.codec.gson.JsonParserStream
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNormalizedNodeStreamWriter
import org.opendaylight.yangtools.yang.data.impl.schema.NormalizedNodeResult
import org.opendaylight.yangtools.yang.model.parser.api.YangSyntaxErrorException
import org.opendaylight.yangtools.yang.model.repo.api.YangTextSchemaSource
import org.opendaylight.yangtools.yang.parser.impl.YangParserFactoryImpl
import java.io.FileReader
import javax.xml.bind.JAXBContext
import javax.xml.bind.Marshaller

class Yang2Opcua : CliktCommand() {
    val libs by option("-l", "--lib").file().multiple()
    val out by option("-o", "--out").file()
    val data by option("-d", "--data").file()
    val suppressErrors by option().flag()
    val mainFile by argument().file()

    override fun run() {
        val mainFile = YangTextSchemaSource.forFile(mainFile)
        val parser = YangParserFactoryImpl().createParser().apply {
            addSource(mainFile)
            libs.forEach { lib ->
                try {
                    addLibSource(YangTextSchemaSource.forFile(lib))
                } catch (e: YangSyntaxErrorException) {
                    if (!suppressErrors) {
                        throw e
                    } else {
                        println("syntax error: ${e.message}")
                    }
                }
            }
        }

        val yangModel = parser.buildEffectiveModel()

        val nodes = data?.let {
            val codecFactory = JSONCodecFactorySupplier.DRAFT_LHOTKA_NETMOD_YANG_JSON_02.createSimple(yangModel)
            val result = NormalizedNodeResult()
            val streamWriter = ImmutableNormalizedNodeStreamWriter.from(result)
            val jsonParserStream = JsonParserStream.create(streamWriter, codecFactory)

            jsonParserStream.parse(Gson().newJsonReader(FileReader(it)))

            result.result
        }

        val opcuaModelBuilder = OpcuaModelBuilder(yangModel, "rootModel", nodes)
        val opcuaModel = opcuaModelBuilder.build()
        val context = JAXBContext.newInstance(ModelDesign::class.java)
        val marshaller = context.createMarshaller().apply { setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true) }

        if (out != null) {
            marshaller.marshal(opcuaModel, out)
        } else {
            marshaller.marshal(opcuaModel, System.out)
        }
    }
}

fun main(args: Array<String>) = Yang2Opcua().main(args)