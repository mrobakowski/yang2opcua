@file:Suppress("UnstableApiUsage")

package dev.robakowski.yang2opcua

import com.google.gson.Gson
import org.opcfoundation.ua.modeldesign.ModelDesign
import org.opendaylight.yangtools.yang.data.codec.gson.JSONCodecFactorySupplier
import org.opendaylight.yangtools.yang.data.codec.gson.JsonParserStream
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNormalizedNodeStreamWriter
import org.opendaylight.yangtools.yang.data.impl.schema.NormalizedNodeResult
import org.opendaylight.yangtools.yang.model.parser.api.YangSyntaxErrorException
import org.opendaylight.yangtools.yang.model.repo.api.YangTextSchemaSource
import org.opendaylight.yangtools.yang.parser.impl.YangParserFactoryImpl
import java.io.File
import java.io.FileReader
import java.io.StringReader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.bind.JAXBContext
import javax.xml.bind.Marshaller


fun main() {
    val mainFile = YangTextSchemaSource.forFile(File("./src/main/resources/simple-list.yang"))
    val dateRegex = Regex("@(\\d\\d\\d\\d)-(\\d\\d)-(\\d\\d)")
    val sep = FileSystems.getDefault().separator
    // region library files
    val libs = File("./yang/standard").walkBottomUp()
        .onEnter {
            !it.toString().contains("yang${sep}standard${sep}mef") // files in mef folder cause the parser to break
        }
        .filter { it.isFile && it.extension == "yang" }
        .groupBy { it.path.split("@")[0] }
        .asSequence()
        .map { (_, v) ->
            v.singleOrNull() ?: v.map {
                dateRegex.find(it.path)!!.destructured.let { (year, month, day) ->
                    it to Triple(year.toInt(), month.toInt(), day.toInt())
                }
            }.maxWith(compareBy({ it.second.first }, { it.second.second }, { it.second.third }))
                .let { it!!.first }
        }
        .mapNotNull {
            try {
                YangTextSchemaSource.forFile(it)
            } catch (e: Exception) {
                println("unexpected error with $it\n${e.message}")
                null
            }
        }.toList()
    // endregion

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

    val outputPath = "./build/models"

    val yangModel = parser.buildEffectiveModel()
    val codecFactory = JSONCodecFactorySupplier.DRAFT_LHOTKA_NETMOD_YANG_JSON_02.createSimple(yangModel)
    val result = NormalizedNodeResult()
    val streamWriter = ImmutableNormalizedNodeStreamWriter.from(result)
    val jsonParserStream = JsonParserStream.create(streamWriter, codecFactory)

    jsonParserStream.parse(Gson().newJsonReader(FileReader("./src/main/resources/simple-list.json")))

    val nodes = result.result

    val opcuaModelBuilder = OpcuaModelBuilder(yangModel, outputPath, "rootModel", nodes)

    val opcuaModel = opcuaModelBuilder.build()

    val context = JAXBContext.newInstance(ModelDesign::class.java)
    val marshaller = context.createMarshaller().apply { setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true) }

    Files.createDirectories(Paths.get(outputPath))

    marshaller.marshal(opcuaModel, System.out)
    marshaller.marshal(opcuaModel, File(opcuaModel.fileName))
}