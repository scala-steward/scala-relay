package com.dispalt.relay

import sbt.*
import sbt.util.CacheImplicits.*
import sbt.util.{CacheStore, CacheStoreFactory}
import sjsonnew.*

/**
  * Converts GraphQL definitions to Scala.js facades to be used with the sources generated by relay-compiler.
  */
object GraphQLConverter {

  // Increment when the code changes to bust the cache.
  private val Version = 1

  final case class Options(outputDir: File)

  object Options {
    //noinspection TypeAnnotation
    implicit val iso = LList.iso[Options, File :*: LNil]( //
      { o: Options => //
        ("outputDir" -> o.outputDir) :*: LNil
      }, {
        case (_, outputDir) :*: LNil => //
          Options(outputDir)
      }
    )
  }

  type Results = Set[File]

  private type Conversions = Map[File, Set[File]]

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

  private final case class Stores(last: CacheStore, resources: CacheStore, schema: CacheStore, outputs: CacheStore)

  private object Stores {
    def apply(cacheStoreFactory: CacheStoreFactory): Stores = Stores(
      last = cacheStoreFactory.make("last"),
      resources = cacheStoreFactory.make("sources"),
      schema = cacheStoreFactory.make("schema"),
      outputs = cacheStoreFactory.make("outputs")
    )
  }

  def convert(
    cacheStoreFactory: CacheStoreFactory,
    sources: Set[File],
    schemaFile: File,
    options: Options,
    logger: Logger
  ): Results = {
    logger.info("Converting GraphQL to Scala.js...")
    val stores = Stores(cacheStoreFactory)
    val prevTracker = Tracked.lastOutput[Unit, Analysis](stores.last) { (_, maybePreviousAnalysis) =>
      val previousAnalysis = maybePreviousAnalysis.getOrElse(Analysis(options))
      logger.debug(s"Previous analysis:\n$previousAnalysis")
      // NOTE: Update clean if you change this.
      Tracked.diffInputs(stores.resources, FileInfo.lastModified)(sources) { resourcesReport =>
        logger.debug(s"Resources:\n$resourcesReport")
        // NOTE: Update clean if you change this.
        Tracked.diffInputs(stores.schema, FileInfo.lastModified)(Set(schemaFile)) { schemaReport =>
          logger.debug(s"Schema:\n$schemaReport")
          // There are 5 cases to handle:
          // 1) Version, schema, or options changed - delete all previous extracts and re-generate everything
          // 2) Source removed - delete the extract
          // 3) Source added - generate the extract
          // 4) Source modified - generate the extract
          // 5) Extract modified - generate the extract
          val schema = GraphQLSchema(schemaFile)
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
              val inverse = unmodifiedConversions.flatMap {
                case (source, extracts) => extracts.map(_ -> source)
              }
              val needsConversion = unexpectedChanges.flatMap(inverse.get)
              convertFiles(needsConversion, schema, options, logger)
            }
          }
          val extracts = modifiedConversions ++ unmodifiedConversions
          Analysis(Version, options, extracts)
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
      val modifiedConversions     = convertFiles(addedOrChangedResources, schema, options, logger)
      val unmodifiedConversions   = previousAnalysis.conversions.filterKeys(resourceReport.unmodified.contains)
      (modifiedConversions, unmodifiedConversions)
    } else {
      def whatChanged =
        if (!versionUnchanged) "Version"
        else if (!schemaUnchanged) "Schema"
        else "Options"
      logger.debug(s"$whatChanged changed")
      val previousOutputs = previousAnalysis.conversions.values.flatten
      IO.delete(previousOutputs)
      val modifiedConversions = convertFiles(resourceReport.checked, schema, options, logger)
      (modifiedConversions, Map.empty)
    }
  }

  private def convertFiles(
    files: Iterable[File],
    schema: GraphQLSchema,
    options: Options,
    logger: Logger
  ): Conversions =
    files.map { file =>
      file -> convertFile(file, schema, options, logger)
    }.toMap

  private def convertFile(file: File, schema: GraphQLSchema, options: Options, logger: Logger): Set[File] = {
    logger.debug(s"Checking file for graphql definitions: $file")
    ???
  }

  private def writeSchema(options: Options, schema: GraphQLSchema): Set[File] = {
    ???
  }

  private def writeScala(options: Options, schema: GraphQLSchema, definitions: Iterable[String]): Set[File] = {
    val writer = new ScalaWriter(options.outputDir, schema)
    definitions.flatMap(writer.write).toSet
  }

  def clean(cacheStoreFactory: CacheStoreFactory): Unit = {
    val Stores(last, resources, schema, outputs) = Stores(cacheStoreFactory)
    last.delete()
    Tracked.diffInputs(resources, FileInfo.lastModified).clean()
    Tracked.diffInputs(schema, FileInfo.lastModified).clean()
    Tracked.diffOutputs(outputs, FileInfo.lastModified).clean()
  }
}
