package com.goodcover.relay

import com.goodcover.relay.codegen.DocumentConverter
import sbt._
import sbt.util.CacheImplicits._
import sbt.util.{CacheStore, CacheStoreFactory}
import sjsonnew._

/**
  * Converts GraphQL definitions to Scala.js facades to be used with the sources generated by relay-compiler.
  */
object GraphQLConverter {

  // Increment when the code changes to bust the cache.
  private val Version = 3

  final case class Options(outputDir: File, typeMappings: Map[String, String])

  object Options {
    //noinspection TypeAnnotation
    implicit val iso = LList.iso[Options, File :*: Map[String, String] :*: LNil]( //
      { o: Options => //
        ("outputDir" -> o.outputDir) :*: ("typeMappings" -> o.typeMappings) :*: LNil
      }, {
        case (_, outputDir) :*: (_, typeMappings) :*: LNil => //
          Options(outputDir, typeMappings)
      }
    )
  }

  type Results = Set[File]

  private type Conversions = Map[File, Set[File]]

  object Conversions {
    def empty: Conversions = Map.empty
  }

  private final case class Analysis(version: Int, options: Options, conversions: Conversions)

  private object Analysis {
    def apply(options: Options): Analysis = Analysis(Version, options, Map.empty)

    //noinspection TypeAnnotation
    implicit val iso = LList.iso[Analysis, Int :*: Options :*: Conversions :*: LNil]( //
      { a: Analysis => //
        ("version" -> a.version) :*: ("options" -> a.options) :*: ("conversions" -> a.conversions) :*: LNil
      }, {
        case (_, version) :*: (_, options) :*: (_, conversions) :*: LNil => //
          Analysis(version, options, conversions)
      }
    )
  }

  private final case class Stores(
    last: CacheStore,
    resources: CacheStore,
    schema: CacheStore,
    dependencies: CacheStore,
    outputs: CacheStore
  )

  private object Stores {
    def apply(cacheStoreFactory: CacheStoreFactory): Stores = Stores(
      last = cacheStoreFactory.make("last"),
      resources = cacheStoreFactory.make("resources"),
      schema = cacheStoreFactory.make("schema"),
      dependencies = cacheStoreFactory.make("dependencies"),
      outputs = cacheStoreFactory.make("outputs")
    )
  }

  def convert(
    cacheStoreFactory: CacheStoreFactory,
    sources: Set[File],
    schemaFile: File,
    dependencies: Set[File],
    options: Options,
    logger: Logger
  ): Results = {
    logger.debug("Running GraphqlConverter...")
    val stores = Stores(cacheStoreFactory)
    val prevTracker = Tracked.lastOutput[Unit, Analysis](stores.last) { (_, maybePreviousAnalysis) =>
      val previousAnalysis = maybePreviousAnalysis.getOrElse(Analysis(options))
      logger.debug(s"Previous analysis:\n$maybePreviousAnalysis")
      // NOTE: Update clean if you change this.
      Tracked.diffInputs(stores.resources, FileInfo.lastModified)(sources) { resourcesReport =>
        logger.debug(s"Resources:\n$resourcesReport")
        // NOTE: Update clean if you change this.
        Tracked.diffInputs(stores.schema, FileInfo.lastModified)(Set(schemaFile)) { schemaReport =>
          logger.debug(s"Schema:\n$schemaReport")
          Tracked.diffInputs(stores.dependencies, FileInfo.lastModified)(dependencies) { dependenciesReport =>
            logger.debug(s"Dependencies:\n$dependenciesReport")
            // NOTE: Update clean if you change this.
            // There are 5 cases to handle:
            // 1) Version, schema, dependencies, or options changed - delete all previous conversions and re-convert everything
            // 2) Resource removed - delete the conversion
            // 3) Resource added - generate the conversion
            // 4) Resource modified - generate the conversion
            // 5) Conversion modified - generate the conversion
            val schema = GraphQLSchema(schemaFile, sources ++ dependencies)
            // TODO: We should be converting the schema once instead of copying all the types into the operations.
            val (modifiedConversions, unmodifiedConversions) =
              convertModified(resourcesReport, schemaReport, schema, previousAnalysis, options, logger)
            val modifiedOutputs   = modifiedConversions.values.flatten.toSet
            val unmodifiedOutputs = unmodifiedConversions.values.flatten.toSet
            val outputs           = modifiedOutputs ++ unmodifiedOutputs
            // NOTE: Update clean if you change this.
            Tracked.diffOutputs(stores.outputs, FileInfo.lastModified)(outputs) { outputsReport =>
              logger.debug(s"Outputs:\n$outputsReport")
              val unexpectedChanges = unmodifiedOutputs -- outputsReport.unmodified
              if (unexpectedChanges.nonEmpty) {
                logger.warn("Unexpected modifications found to files:")
                unexpectedChanges.foreach { file =>
                  logger.warn(s" ${file.absolutePath}")
                }
                logger.warn(
                  "Ensure that nothing is modifying these files so as to get the most benefit from the cache."
                )
                val inverse         = invertOneToManyOrThrow(unmodifiedConversions)
                val needsConversion = unexpectedChanges.flatMap(inverse.get)
                // Don't forget to delete the old ones since convert fails if it exists.
                IO.delete(unexpectedChanges)
                val unexpectedConversions = convertFiles(needsConversion, schema, options, logger)
                logger.warn(s"Converted an additional ${unexpectedConversions.size} GraphQL documents.")
                unexpectedConversions
              }
            }
            val extracts = modifiedConversions ++ unmodifiedConversions
            Analysis(Version, options, extracts)
          }
        }
      }
    }
    prevTracker(()).conversions.values.flatten.toSet
  }

  /**
    * If the version has changed this will delete all previous conversions and re-convert everything. Otherwise it will
    * remove old conversions for removed resources and convert anything that is new or was modified.
    *
    * @return a tuple where the first element are the new conversion and the second are the conversions from the
    *         previous analysis that have not been modified
    */
  private def convertModified(
    resourceReport: ChangeReport[File],
    schemaReport: ChangeReport[File],
    schema: GraphQLSchema,
    previousAnalysis: Analysis,
    options: Options,
    logger: Logger
  ): (Conversions, Conversions) = {
    def versionUnchanged = Version == previousAnalysis.version
    def schemaUnchanged  = schemaReport.modified.isEmpty
    def optionsUnchanged = options == previousAnalysis.options
    if (versionUnchanged && schemaUnchanged && optionsUnchanged) {
      logger.debug("Version, schema, and options have not changed")
      val outputsOfRemoved = previousAnalysis.conversions.filterKeys(resourceReport.removed.contains).values.flatten
      IO.delete(outputsOfRemoved)
      val addedOrChangedResources = resourceReport.modified -- resourceReport.removed
      // If we find multiple resources converting to the same file then this means there are two operations or
      // fragments with the same name which is not allowed.
      // So we need to delete any previous conversions.
      addedOrChangedResources.foreach { source =>
        previousAnalysis.conversions.get(source).foreach(IO.delete)
      }
      if (addedOrChangedResources.nonEmpty) logger.info("Converting GraphQL to Scala.js...")
      val modifiedConversions = convertFiles(addedOrChangedResources, schema, options, logger)
      if (addedOrChangedResources.nonEmpty) logger.info(s"Converted ${modifiedConversions.size} GraphQL documents.")
      val unmodifiedConversions = previousAnalysis.conversions.filterKeys(resourceReport.unmodified.contains)
      (modifiedConversions, unmodifiedConversions)
    } else {
      if (!versionUnchanged) logger.debug(s"Version changed:\n$Version")
      else if (!schemaUnchanged) logger.debug("Schema changed.")
      else logger.debug(s"Options changed:\n$options")
      val previousOutputs = previousAnalysis.conversions.values.flatten
      IO.delete(previousOutputs)
      val modifiedConversions = convertFiles(resourceReport.checked, schema, options, logger)
      (modifiedConversions, Map.empty)
    }
  }

  // TODO: Add parallelism.
  private def convertFiles(
    files: Iterable[File],
    schema: GraphQLSchema,
    options: Options,
    logger: Logger
  ): Conversions = {
    logger.debug(s"Converting schema: ${schema.file}")
    val converter          = new DocumentConverter(options.outputDir, schema, options.typeMappings, Set.empty[File])
    val outputs            = converter.convertSchema()
    val initialConversions = Map(schema.file -> outputs)
    val (conversions, _) = files.foldLeft((initialConversions, outputs)) {
      case ((conversions, outputs), file) =>
        logger.debug(s"Converting file: $file")
        // This is kind of silly since only the outputs change but oh well, it saves passing them around everywhere.
        val converter       = new DocumentConverter(options.outputDir, schema, options.typeMappings, outputs)
        val newOutputs      = converter.convert(file)
        val nextConversions = conversions + (file -> newOutputs)
        val nextOutputs     = outputs ++ newOutputs
        (nextConversions, nextOutputs)
    }
    conversions
  }

  def clean(cacheStoreFactory: CacheStoreFactory): Unit = {
    val Stores(last, resources, schema, dependencies, outputs) = Stores(cacheStoreFactory)
    last.delete()
    Tracked.diffInputs(resources, FileInfo.lastModified).clean()
    Tracked.diffInputs(schema, FileInfo.lastModified).clean()
    Tracked.diffInputs(dependencies, FileInfo.lastModified).clean()
    Tracked.diffOutputs(outputs, FileInfo.lastModified).clean()
  }
}
