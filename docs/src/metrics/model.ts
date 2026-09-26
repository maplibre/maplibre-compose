export type Summary = Record<string, number>;
export interface Entry {
  commit: string;
  commitDate: string;
  title: string;
  tags: string[];
  path: string;
}
export interface Scope {
  id: string;
  group: string;
  module: string | null;
  sourceSet: string | null;
  path: string;
}
export interface History {
  schemaVersion: number;
  reporterVersion: string;
  since: string;
  step: number;
  totalCommits: number;
  snapshots: Entry[];
  scopes: Scope[];
}
export interface Point {
  commit: string;
  summary: Summary;
}
export interface Ranked {
  name: string;
  value: number;
}
export interface Package {
  name: string;
  files: number;
  loc: number;
  types: number;
  functions: number;
  sourceSets: string[];
  dependsOn: string[];
  dependedOnBy: string[];
  instability: number;
  externalImports: number;
}
export interface SourceSet {
  module: string;
  name: string;
  isTest: boolean;
  files: number;
  loc: number;
  cyclomaticComplexity: number;
  cognitiveComplexity: number;
  functions: number;
}
export interface Distribution {
  count: number;
  mean: number;
  p50: number;
  p75: number;
  p90: number;
  p99: number;
  max: number;
  maxName: string | null;
  histogram: Record<string, number>;
}
export interface Snapshot {
  commit: string;
  summary: Summary;
  packages: Package[];
  sourceSets: SourceSet[];
  packageGraph: { cycles: string[][] };
  largest: Record<string, Ranked[]>;
  distributions: Record<string, Distribution>;
}

export const repository = "https://github.com/maplibre/maplibre-compose";

export interface Panel {
  title: string;
  unit: string;
  keys: string[];
  labels: string[];
  description: string;
  distribution?: string;
}
export const distributionMetrics: Record<
  string,
  {
    title: string;
    unit: string;
    population: string;
    stem: string;
    description: string;
    boundaries: number[];
  }
> = {
  functionCognitiveComplexity: {
    title: "Function cognitive complexity",
    unit: "score",
    population: "functions",
    stem: "functionCognitive",
    description:
      "Detekt's cognitive complexity score counts breaks in control flow and adds a penalty for nesting. A straight-line function can score 0; nested branches cost more than flat ones. This is a syntax-based estimate of reading difficulty, not a quality grade. Each function declaration is one observation, including local functions and object-literal methods; lambdas are part of their enclosing function.",
    boundaries: [0, 1, 2, 3, 5, 10, 20, 50],
  },
  functionCyclomaticComplexity: {
    title: "Function cyclomatic complexity",
    unit: "score",
    population: "functions",
    stem: "functionCyclomatic",
    description:
      "Detekt starts with one for a function, then counts branches, loops, when entries, jumps, catches, boolean and Elvis operators, and calls such as let, run and forEach with a trailing lambda. Its default visitor excludes object-literal methods, which therefore score 0 here. Local functions are observations too; their syntax can also contribute to enclosing functions.",
    boundaries: [0, 1, 2, 3, 5, 10, 20, 50],
  },
  functionLines: {
    title: "Function length",
    unit: "code lines",
    population: "functions",
    stem: "functionLines",
    description:
      "Lines containing a code token in each function declaration, using Detekt's linesOfCode. Blank and comment-only lines are excluded. Local functions and object-literal methods are included; an enclosing function's span also includes its nested declarations. This differs from physical file length.",
    boundaries: [0, 1, 5, 10, 20, 40, 80, 160],
  },
  functionParameters: {
    title: "Function parameters",
    unit: "parameters",
    population: "functions",
    stem: "functionParameters",
    description:
      "Declared value parameters per function. Defaulted and vararg parameters each count once. Extension receivers, context receivers and constructor parameters are not counted. Each function declaration is one observation, including local functions and object-literal methods.",
    boundaries: [0, 1, 2, 3, 4, 5, 8, 12],
  },
  fileLoc: {
    title: "File length",
    unit: "physical lines",
    population: "files",
    stem: "fileLoc",
    description:
      "Physical lines in each production Kotlin file, including blank lines and comments. One file is one observation, regardless of its number of declarations.",
    boundaries: [0, 1, 50, 100, 200, 400, 800, 1600],
  },
  typeLines: {
    title: "Type length",
    unit: "code lines",
    population: "types",
    stem: "typeLines",
    description:
      "Lines containing a code token in each class, interface or named object declaration, excluding blank and comment-only lines. Nested and companion types are measured separately, so their lines also occur in the enclosing type's span. Enum entries are not types. Local classes are excluded by this reporter's declaration traversal.",
    boundaries: [0, 1, 20, 50, 100, 200, 400, 800],
  },
};
function distributionPanel(key: string): Panel {
  const metric = distributionMetrics[key];
  return {
    ...metric,
    distribution: key,
    keys: ["P50", "P90", "P99"].map((suffix) => metric.stem + suffix),
    labels: ["median", "p90", "p99"],
  };
}
const cognitive = distributionPanel("functionCognitiveComplexity");
const length = distributionPanel("functionLines");
const density = {
  title: "Cyclomatic complexity density",
  unit: "per 1,000 logical lines",
  keys: ["cyclomaticPer1000Lloc"],
  labels: ["complexity"],
  description:
    "Total file-level cyclomatic complexity × 1,000 / logical lines of production code. Logical lines use Detekt's line/brace-based estimate of statements and declarations, excluding blanks, comments, package and import lines. A change can come from either complexity or code size; this is not a percentage.",
};
const cycles = {
  title: "Packages in dependency cycles",
  unit: "packages",
  keys: ["packagesInCycles"],
  labels: ["packages"],
  description:
    "Number of distinct packages belonging to an import cycle: following directed imports can lead back to the starting package. Only groups of two or more packages within this scope count. These are syntax-based imports, not runtime calls; imports outside the scope are excluded.",
};
const loc = {
  title: "Code size",
  unit: "lines",
  keys: ["loc", "testLoc"],
  labels: ["production", "tests"],
  description:
    "Total physical Kotlin lines, including comments and blank lines. Production and test source sets are counted separately. Filtering to a production source set excludes tests. These totals measure size, not coverage or code quality.",
};
const fileSize = distributionPanel("fileLoc");
export const panels: Record<string, Panel[]> = {
  overview: [cognitive, length, density, cycles, loc, fileSize],
  complexity: [
    cognitive,
    distributionPanel("functionCyclomaticComplexity"),
    density,
    distributionPanel("functionParameters"),
  ],
  dependencies: [
    cycles,
    {
      title: "Package import edges",
      unit: "edges",
      keys: ["packageEdges"],
      labels: ["edges"],
      description: "Distinct directed imports between packages inside the selected scope.",
    },
    {
      title: "Dependency cycle groups",
      unit: "groups",
      keys: ["packageCycles"],
      labels: ["groups"],
      description: "Strongly connected components containing at least two packages.",
    },
    {
      title: "Bidirectional package pairs",
      unit: "pairs",
      keys: ["bidirectionalPackagePairs"],
      labels: ["pairs"],
      description: "Pairs of packages that directly import each other.",
    },
    {
      title: "Mean package instability",
      unit: "ratio",
      keys: ["meanPackageInstability"],
      labels: ["mean"],
      description:
        "Imports out divided by imports in plus out. Dependency direction, not a quality score.",
    },
    {
      title: "Source sets per package",
      unit: "source sets",
      keys: ["packageSourceSetsMean", "packageSourceSetsMax"],
      labels: ["mean", "max"],
      description: "How widely packages span modules and source sets.",
    },
  ],
  size: [
    loc,
    length,
    fileSize,
    distributionPanel("typeLines"),
    {
      title: "Declarations",
      unit: "declarations",
      keys: ["functions", "types"],
      labels: ["functions", "types"],
      description: "Production declarations in the selected scope.",
    },
    {
      title: "Test / production size",
      unit: "ratio",
      keys: ["testToMainLocRatio"],
      labels: ["ratio"],
      description: "Test lines divided by production lines. This is not test coverage.",
    },
  ],
};
export const hotspotKinds: Record<string, string> = {
  functionsByCognitiveComplexity: "Function cognitive complexity",
  functionsByLines: "Function length",
  filesByLoc: "File length",
  typesByLines: "Type length",
  packagesByTypes: "Types per package",
};
export function format(value: number, digits = 1): string {
  return new Intl.NumberFormat("en", { maximumFractionDigits: digits }).format(value);
}
export function delta(value: number, baseline: number, digits = 1): string {
  const n = value - baseline;
  return `${n > 0 ? "+" : n < 0 ? "−" : ""}${format(Math.abs(n), digits)}`;
}
export function sourceLink(commit: string, name: string): string | null {
  const match = /^(.*\.kt)(?::(\d+))?/.exec(name);
  return match
    ? `${repository}/blob/${commit}/${match[1].split("/").map(encodeURIComponent).join("/")}${match[2] ? `#L${match[2]}` : ""}`
    : null;
}

const summaryDefinitions: Record<string, string> = {
  files: "Number of production Kotlin files in this scope.",
  loc: "Physical production lines: newline count plus one per file, including blanks and comments.",
  sloc: "Detekt source lines: nonblank lines whose trimmed text does not start with //, /*, */ or *. This is a line-based heuristic.",
  lloc: "Detekt logical lines: a line/brace-based approximation of statements and declarations, excluding blank, comment, package and import lines. Not a parsed statement count.",
  cloc: "Lines in comment nodes, including documentation comments. Inline comments count; a line containing both code and a comment can contribute to both counts.",
  testLoc: "Physical lines in test source sets, including blanks and comments.",
  testToMainLocRatio:
    "Test physical lines divided by production physical lines. Zero if there are no production lines. This is not test coverage.",
  commentToSourceRatio:
    "Comment lines divided by source lines (SLOC). Zero if there are no source lines. This is not a percentage or a documentation-quality score.",
  packages:
    "Distinct production package names in this scope, merged across modules and source sets.",
  types:
    "Class, interface and named object declarations, including nested and companion types. Excludes enum entries and local classes.",
  functions:
    "Function declarations, including local functions, expression-bodied functions and object-literal methods. Lambdas are not separate observations.",
  cyclomaticComplexity:
    "Sum of Detekt's cyclomatic score measured over each production file. This differs from summing per-function scores because nested declarations can contribute to more than one function's score.",
  cognitiveComplexity:
    "Sum of Detekt's cognitive score measured over each production file. It weights control-flow interruptions and nesting; it is not a percentage.",
  cyclomaticPer1000Lloc: density.description,
  packageEdges:
    "Distinct directed imports between different production packages within the scope. Multiple imports between the same pair count as one edge.",
  packageCycles:
    "Strongly connected import groups of at least two packages: every member can reach every other member by following imports.",
  packagesInCycles: cycles.description,
  bidirectionalPackagePairs:
    "Unordered pairs of packages that directly import one another. Each pair is counted once.",
  meanPackageInstability:
    "Unweighted mean across packages of imports out / (imports in + imports out), counting distinct packages. Packages with no edges contribute 0. Measures dependency direction, not quality.",
  packageSourceSetsMean:
    "Mean number of distinct module/source-set combinations containing each package.",
  packageSourceSetsMax:
    "Largest number of distinct module/source-set combinations containing one package.",
  expectDeclarations: "Production declarations with an explicit expect modifier.",
  actualDeclarations: "Production declarations with an explicit actual modifier.",
  todoComments:
    "Occurrences of the words TODO, FIXME, HACK or XXX in comments. Includes production and test code; multiple occurrences in a comment count separately.",
  suppressAnnotations:
    "Annotations whose short name is Suppress. Includes production and test code; counts annotations, not individual suppressed rules.",
};
export function metricDefinition(key: string): string {
  for (const metric of Object.values(distributionMetrics)) {
    if (!key.startsWith(metric.stem)) continue;
    const suffix = key.slice(metric.stem.length);
    if (suffix === "Max") return `Highest single observation. ${metric.description}`;
    if (/^P\d+$/.test(suffix))
      return `${suffix === "P50" ? "Median (p50)" : suffix.toLowerCase()}: nearest-rank value at or below which at least ${suffix.slice(1)}% of the production ${metric.population} fall. ${metric.description}`;
  }
  return summaryDefinitions[key] ?? "No definition available for this reporter field.";
}
