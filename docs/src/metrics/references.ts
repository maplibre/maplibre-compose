export interface MetricReference {
  label: string;
  explanation: string;
  threshold?: number;
  sources: { label: string; url: string }[];
}
const detekt = "https://detekt.dev/docs/rules/complexity/";
const martin = "https://condor.depaul.edu/dmumaugh/OOT/Design-Principles/oodmetrc.pdf";
const references: Record<string, MetricReference> = {
  functionCognitiveComplexity: {
    threshold: 15,
    label: "Review reference: >15 per function · Detekt default",
    explanation:
      "Detekt's CognitiveComplexMethod rule allows 15 and reports scores above it. This is a configurable review heuristic, not a scientifically validated failure boundary or a target for an aggregate percentile. A p90 of 4 means at least 90% of functions score at most 4; inspect the number above 15 to assess the tail. Empirical studies support some relationship with comprehension time, but do not establish a universal safe cutoff or prove superiority over simpler measures.",
    sources: [
      { label: "Detekt: CognitiveComplexMethod", url: detekt + "#cognitivecomplexmethod" },
      {
        label: "Sonar: why the default is 15",
        url: "https://community.sonarsource.com/t/webinar-refactoring-with-cognitive-complexity/45331",
      },
      {
        label: "Muñoz Barón et al. (2020): empirical validation",
        url: "https://arxiv.org/abs/2007.12520",
      },
      {
        label: "Empirical evaluation against traditional measures",
        url: "https://www.sciencedirect.com/science/article/abs/pii/S0164121222002370",
      },
    ],
  },
  functionCyclomaticComplexity: {
    threshold: 14,
    label: "Review reference: >14 per function · Detekt default",
    explanation:
      "Detekt's Kotlin rule allows 14 and reports scores above it. NIST SP 500-235 §2.5 discusses a classical cyclomatic limit of 10, with justified exceptions up to 15 and additional testing. Detekt also counts Kotlin scope functions and other syntax, so the classical control-flow measure and this analyzer's score are not interchangeable. The line shown here is the matching tool default, not a repository quality gate.",
    sources: [
      { label: "Detekt: CyclomaticComplexMethod", url: detekt + "#cyclomaticcomplexmethod" },
      {
        label: "NIST SP 500-235, §2.5 (1996)",
        url: "https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication500-235.pdf#page=35",
      },
    ],
  },
  functionLines: {
    threshold: 60,
    label: "Review reference: >60 code lines · Detekt default",
    explanation:
      "Detekt's LongMethod default allows 60 code-bearing lines. Use it to locate functions worth reviewing, not as evidence that 59 lines is good and 61 is bad. Splitting a cohesive function solely to lower its line count can make the code harder to follow.",
    sources: [{ label: "Detekt: LongMethod", url: detekt + "#longmethod" }],
  },
  functionParameters: {
    label: "Context: Detekt's default parameter limit is 5",
    explanation:
      "Detekt's LongParameterList rule defaults to 5 function parameters, but uses type resolution and configurable exclusions (including default parameters). This reporter counts raw declared parameters, so an above-5 count would not equal a count of rule findings. Compose APIs commonly expose optional parameters; inspect the API's role before judging the count.",
    sources: [{ label: "Detekt: LongParameterList", url: detekt + "#longparameterlist" }],
  },
  typeLines: {
    label: "Context: Detekt's LargeClass default is 600 lines",
    explanation:
      "Detekt's LargeClass rule uses 600 code-bearing lines by default. This distribution includes interfaces and named objects as well as classes, so it is broader than the rule. Treat the number as a review prompt, not a universal type-size limit.",
    sources: [{ label: "Detekt: LargeClass", url: detekt + "#largeclass" }],
  },
  meanPackageInstability: {
    label: "0 = incoming only · 1 = outgoing only · no ideal average",
    explanation:
      "For a connected package, I = outgoing / (incoming + outgoing). A value of 0.578 describes its dependency balance, not defect probability; here the chart is an unweighted mean of those package ratios. Martin's guidance relates stability to architectural role and abstractness, rather than minimizing every package's I. This reporter counts package-import neighbors instead of Martin's class couplings, omits external edges, and assigns isolated packages 0. Inspect individual packages and dependency direction; the average alone cannot judge architecture.",
    sources: [
      { label: "Martin (1994): OO Design Quality Metrics, pp. 6–7", url: martin + "#page=6" },
    ],
  },
  packagesInCycles: {
    label: "Architectural reference: 0 cycles between independent components",
    explanation:
      "The acyclic-dependencies principle calls for no cycles between independently reusable components. Kotlin packages are not necessarily those components, so a package-level cycle is a place to inspect boundaries, not an automatic design defect. Review the listed cycle members and whether they should change or ship together.",
    sources: [
      {
        label: "Martin (2000): Design Principles and Design Patterns",
        url: "https://objectmentor.com/resources/articles/Principles_and_Patterns.pdf",
      },
    ],
  },
  cyclomaticPer1000Lloc: {
    label: "Context metric · no calibrated cutoff for this ratio",
    explanation:
      "Do not apply per-function complexity limits to this ratio. It divides the file-level total by a heuristic logical-line count. Use changes within the same scope and reporter version to investigate shifts; function distributions provide more interpretable review references.",
    sources: [
      {
        label: "Detekt: metric implementation",
        url: "https://github.com/detekt/detekt/tree/v2.0.0-alpha.6/detekt-metrics/src/main/kotlin/dev/detekt/metrics",
      },
    ],
  },
};
const context: MetricReference = {
  label: "Context metric · compare with this scope's history",
  explanation:
    "This dashboard applies no universal good/bad cutoff to this count or ratio. Growth can reflect added functionality, changes to scope or reorganized code. Compare the same scope and reporter version, then inspect the underlying packages, declarations or source sets.",
  sources: [],
};
export function metricReference(key: string): MetricReference {
  return references[key] ?? context;
}

export function appendReferenceDetails(host: HTMLElement, reference: MetricReference) {
  const explanation = document.createElement("p");
  explanation.textContent = reference.explanation;
  host.append(explanation);
  const sources = document.createElement("ul");
  sources.className = "reference-sources";
  for (const source of reference.sources) {
    const item = document.createElement("li");
    const link = document.createElement("a");
    link.textContent = source.label;
    link.href = source.url;
    item.append(link);
    sources.append(item);
  }
  if (sources.childElementCount) host.append(sources);
}
