// @flow

require('babel-polyfill');

const {
  CodegenRunner,
  ConsoleReporter,
  WatchmanClient,
} = require('relay-compiler/lib/GraphQLCompilerPublic');

const RelayJSModuleParser = require('relay-compiler/lib/RelayJSModuleParser');
const ScalaFileWriter = require('./codegen/ScalaFileWriter');
const {FileWriter} = require('relay-compiler');
const RelayIRTransforms = require('relay-compiler/lib/RelayIRTransforms');

const formatModule  = require('./codegen/formatScalaModule');
const fs = require('fs');
const path = require('path');
const yargs = require('yargs');

const {
  buildASTSchema,
  buildClientSchema,
  parse,
  printSchema,
} = require('graphql');

const {
  commonTransforms,
  codegenTransforms,
  fragmentTransforms,
  printTransforms,
  queryTransforms,
  schemaExtensions,
} = RelayIRTransforms;

const SCRIPT_NAME = 'relay-compiler';

import type {GetWriterOptions} from 'relay-compiler/lib/GraphQLCompilerPublic';

const SJS = require('./transforms/SJSTransform');

const WATCH_EXPRESSION = [
  'allof',
  ['type', 'f'],
  ['suffix', 'scala'],
  ['not', ['match', '**/__mocks__/**', 'wholename']],
  ['not', ['match', '**/__tests__/**', 'wholename']],
  ['not', ['match', '**/__generated__/**', 'wholename']],
];

// Inject
printTransforms.unshift(SJS.transformRemoveSjs);

function getSchema(schemaPath: string) {
  try {
    let source = fs.readFileSync(schemaPath, 'utf8');
    if (path.extname(schemaPath) === '.json') {
      source = printSchema(buildClientSchema(JSON.parse(source).data));
    }
    source = `
    directive @include(if: Boolean) on FRAGMENT_SPREAD | FIELD
    directive @skip(if: Boolean) on FRAGMENT_SPREAD | FIELD
    directive @scalajs(extends: String) on FIELD | INLINE_FRAGMENT | FRAGMENT_DEFINITION

    ${source}
  `;
    return buildASTSchema(parse(source), {assumeValid: true});
  } catch (error) {
    throw new Error(
      `
Error loading schema. Expected the schema to be a .graphql or a .json
file, describing your GraphQL server's API. Error detail:
${error.stack}
    `.trim(),
    );
  }
}

function getScalaFileWriter(baseDir: string, outputDir: string, useNulls: boolean) {
  return ({
    onlyValidate,
    schema,
    documents,
    baseDocuments,
    sourceControl,
    reporter,
  }: GetWriterOptions) =>
  new ScalaFileWriter({
    config: {
      baseDir,
      compilerTransforms: {
        commonTransforms,
        codegenTransforms,
        fragmentTransforms,
        printTransforms,
        queryTransforms,
      },
      customScalars: {},
      formatModule,
      inputFieldWhiteListForFlow: [],
      schemaExtensions,
      useHaste: false,
      noFutureProofEnums: false,
      outputDir,
      useNulls,
    },
    onlyValidate,
    schema,
    baseDocuments,
    documents,
    reporter,
    sourceControl
  });
}

// $FlowFixMe
function compileAll(srcDir: string, schemaPath: string, writer, parser, fileFilter, getFilepathsFromGlob, verbose: boolean) {
  const files = getFilepathsFromGlob(srcDir, {include: ["**"], extensions: ["scala"]});

  const parserConfigs = {
    default: {
      baseDir: srcDir,
      getFileFilter: fileFilter,
      getParser: parser,
      getSchema: () => getSchema(schemaPath),
      watchmanExpression: null,
      filepaths: files,
    },
  };
  const writerConfigs = {
    default: {
      getWriter: writer,
      parser: 'default',
      isGeneratedFile: (filePath) => true
    },
  };

  const reporter = new ConsoleReporter({
    verbose,
    quiet: false,
  });

  const codegenRunner = new CodegenRunner({
    reporter,
    parserConfigs,
    writerConfigs,
    onlyValidate: false,
    sourceControl: null,
  });

  codegenRunner.compileAll().then(
    (result) => {
      if (result === 'ERROR') {
        process.exit(100);
      }
    },
    error => {
      console.error(String(error.stack || error));
      process.exit(101);
    });
}



module.exports = {
  getSchema,
  getScalaFileWriter,
  WATCH_EXPRESSION,
  SCRIPT_NAME,
  compileAll
}
