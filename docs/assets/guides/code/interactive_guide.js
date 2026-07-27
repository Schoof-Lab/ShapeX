const controls = [...document.querySelectorAll("[data-control]")];
const controlsById = new Map(controls.map((control) => [control.dataset.control, control]));
const highlights = [...document.querySelectorAll("[data-highlight]")];

const installationLayout = document.querySelector?.(".installation-layout");
const installationStepsOverview = document.getElementById?.("installation-steps-overview");
const installationCatalogDetail = document.getElementById?.("installation-catalog-detail");
const installationShapexDetail = document.getElementById?.("installation-shapex-detail");
const installationCatalogCard = document.getElementById?.("installation-catalog-card");
const installationRequirement = document.querySelector?.(".installation-requirement");
const installationCatalogPreview = document.getElementById?.("installation-catalog-preview");
const installationShapexPreview = document.getElementById?.("installation-shapex-preview");
const installationCatalogScreenshot = document.getElementById?.("installation-catalog-screenshot");
const installationShapexScreenshot = document.getElementById?.("installation-shapex-screenshot");
const installationDetailTriggers = [
  ...document.querySelectorAll("[data-installation-detail-trigger]"),
];
const installationDetailBackControls = [
  ...document.querySelectorAll("[data-installation-detail-back]"),
];
const installationCatalogCopy = document.querySelector?.("[data-copy-catalog-url]");
const installationCatalogCopyStatus = document.querySelector?.("[data-copy-catalog-status]");
const installationCatalogCopyAction = document.querySelector?.("[data-installation-copy-action]");
const installationCatalogStateControls = [
  ...document.querySelectorAll("[data-installation-catalog-state]"),
];
const installationCatalogHighlights = [
  ...document.querySelectorAll("[data-installation-highlight-state]"),
];
const installationShapexStateControls = [
  ...document.querySelectorAll("[data-installation-shapex-state]"),
];
const installationShapexHighlights = [
  ...document.querySelectorAll("[data-installation-shapex-highlight-state]"),
];
const instrumentGuideTriggers = [
  ...document.querySelectorAll("[data-instrument-guide]"),
];
const instrumentGuideModal = document.querySelector?.("[data-instrument-guide-modal]");
const instrumentGuideCloseControls = [
  ...document.querySelectorAll("[data-instrument-guide-close]"),
];
const instrumentGuideType = document.querySelector?.("[data-instrument-guide-type]");
const instrumentGuideTitle = document.querySelector?.("[data-instrument-guide-title]");
const instrumentGuideStatus = document.querySelector?.("[data-instrument-guide-status]");
const instrumentGuideIntroduction = document.querySelector?.(
  "[data-instrument-guide-introduction]",
);
const instrumentGuideFileSection = document.querySelector?.(
  "[data-instrument-guide-file-section]",
);
const instrumentGuideFile = document.querySelector?.("[data-instrument-guide-file]");
const instrumentGuideDetails = document.querySelector?.("[data-instrument-guide-details]");
const instrumentGuideStepsSection = document.querySelector?.(
  "[data-instrument-guide-steps-section]",
);
const instrumentGuideDetailsSection = document.querySelector?.(
  "[data-instrument-guide-details-section]",
);
const instrumentGuideSteps = document.querySelector?.("[data-instrument-guide-steps]");
const instrumentGuideChecksSection = document.querySelector?.(
  "[data-instrument-guide-checks-section]",
);
const instrumentGuideChecks = document.querySelector?.("[data-instrument-guide-checks]");
const instrumentGuideUnavailable = document.querySelector?.(
  "[data-instrument-guide-unavailable]",
);
const calibrationGuideSection = document.querySelector?.("[data-calibration-guide-section]");
const calibrationGuideInstruction = document.querySelector?.(
  "[data-calibration-guide-instruction]",
);
const calibrationGuideImage = document.querySelector?.("[data-calibration-guide-image]");
const calibrationGuidePoints = [
  ...document.querySelectorAll("[data-calibration-guide-point]"),
];
const calibrationGuideOverview = document.querySelector?.("[data-calibration-guide-overview]");

const installationCatalogStates = {
  manager: {
    source: "../installation/catalog/guide_installation_catalog1.png",
    width: "868",
    height: "468",
    alt: "QuPath's Extension Manager with the Manage extension catalogs button at the bottom.",
  },
  catalog: {
    source: "../installation/catalog/guide_installation_catalog2.png",
    width: "868",
    height: "368",
    alt: "QuPath's Extension Catalog Manager with the Schoof Lab catalog URL ready to be added.",
  },
};

const installationShapexStates = {
  select: {
    source: "../installation/shapex/guide_installation_shapex_select.png",
    width: "868",
    height: "468",
    alt: "QuPath's Extension Manager with the green install button beside QuPath ShapeX extension.",
  },
  install: {
    source: "../installation/shapex/guide_installation_shapex_install.png",
    width: "611",
    height: "342",
    alt: "The Install extension dialog showing ShapeX v0.1.0 and the Install button.",
  },
};

let activeInstallationDetail = null;

const instrumentGuides = new Map([
  [
    "calibration-points",
    {
      type: "Workflow guide",
      title: "Placing calibration points",
      status: "Guide",
      file: null,
      introduction:
        "Place the three points far apart so that they form a large triangle, and use distinctive tissue landmarks that will be easy to find again on the microscope.",
      calibration: true,
    },
  ],
  [
    "leica-lmd",
    {
      type: "Laser microdissection",
      title: "Leica LMD",
      status: "Supported",
      file: "<output folder>/xml/Plate <number>/<xml_name>.xml",
      introduction:
        "Use the generated Leica XML files to transfer calibrated cutting outlines and collection-well assignments from ShapeX to the LMD system.",
      steps: [
        "Open the Leica LMD software, then insert the slides and plate.",
        {
          parts: [
            "Navigate to ",
            { strong: "File" },
            " → ",
            { strong: "Import Shapes..." },
          ],
        },
        {
          parts: [
            "Click ",
            { strong: "OK" },
            " to import all shapes.",
          ],
        },
        {
          parts: [
            "The following two pop-ups ask whether you want to load previously saved reference points. If you have saved reference points, click ",
            { strong: "Yes" },
            " to load them. Otherwise, click ",
            { strong: "No" },
            ".",
          ],
        },
        "The following three pop-ups guide you through the reference-point calibration. Enter the reference points one at a time and in the correct order. Entering them in the wrong order may flip or distort the annotations. To indicate the required order, ShapeX creates three numbered points in QuPath over the calibration points used for the export. These points are labeled 1, 2, and 3. Enter the reference points in that order.",
        "Decide whether to save the reference points for future use. If you save them, you can skip the calibration step in future runs.",
        {
          parts: [
            "Switch to the objective used for cutting, ensure that the samples are in focus, and click ",
            { strong: "OK" },
            ", followed by ",
            { strong: "Yes" },
            ".",
          ],
        },
        "The annotations are now ready to be cut. Depending on the number of annotations, loading them may take a few minutes.",
      ],
    },
  ],
  [
    "idot",
    {
      type: "Liquid dispensing",
      title: "Dispendix i.DOT",
      status: "Supported",
      file: "<output folder>/idot/Plate N/*_IDOT.csv",
      introduction:
        "Use the i.DOT CSV files to dispense the liquids and volumes defined in ShapeX into the wells assigned by the Plate Designer.",
      steps: [
        "Open the output folder created for this run, then open the idot folder and the folder for the plate you are preparing.",
        "Choose the CSV file for the first dispensing step in your protocol. The file name includes the project, plate number, and step name.",
        "Import the CSV file into the i.DOT software and review the source and target plate setup.",
        "Run the dispensing files in the same order as the steps defined in ShapeX.",
        "Repeat the process for each plate generated by the Plate Designer.",
      ],
      checks: [
        "The source and target plate types match the physical plates on the instrument.",
        "The liquid name and volume are correct for each dispensing step.",
        "The target wells match the ShapeX plate overview.",
        "The source wells contain enough liquid to complete the dispensing step.",
      ],
    },
  ],
  [
    "plate-overview",
    {
      type: "Plate documentation",
      title: "Plate overview",
      status: "Supported",
      file: "<output folder>/Overview/",
      introduction:
        "The plate overview is a readable record of how ShapeX assigned samples and annotations to plates and wells.",
      details: [
        {
          text: "Spreadsheet formats (Excel, Numbers, and OpenDocument) keep everything in one file with separate sheets. CSV, TSV, and TXT create a separate file for each sheet.",
        },
        {
          heading: "What each sheet or file contains",
          items: [
            {
              label: "Overview",
              text: "Lists each sample and negative control with its plate, well, shape count, area, and color.",
            },
            {
              label: "Settings",
              text: "Summarizes the plate distribution, total samples, plate use, and randomization seed.",
            },
            {
              label: "Plate1, Plate2, …",
              text: "Shows the sample and negative-control assignments in a plate-shaped well grid.",
            },
          ],
        },
      ],
    },
  ],
  [
    "anndata",
    {
      type: "Spatial data",
      title: "scverse anndata",
      status: "Supported",
      file: "<output folder>/anndata/ShapeXAnnotations.h5ad",
      introduction:
        "The AnnData file stores one observation for each exported annotation so it can be used directly in Python and other scverse tools.",
      details: [
        {
          text: "ShapeX copies all image metadata and annotation-table keys exposed by QuPath into adata.obs. The same AnnData container also holds annotation geometry, measurements, spatial coordinates, bounding boxes, plate assignments, and ShapeX export settings.",
        },
        {
          heading: "Load it in Python",
          code: `import anndata as ad

adata = ad.read_h5ad("ShapeXAnnotations.h5ad")
print(adata)
print(adata.obs.head())`,
        },
      ],
    },
  ],
]);

const unavailableInstrumentGuides = new Map([
  ["mmi-cellcut", ["MMI CellCut", "Laser microdissection"]],
  ["laxco-accuva-cellect", ["Laxco Accuva Cellect", "Laser microdissection"]],
  ["zeiss-palm-microbeam", ["ZEISS PALM MicroBeam", "Laser microdissection"]],
  ["meteor-cosmosort", ["Meteor Biotech CosmoSort", "Laser microdissection"]],
  ["thermo-arcturus-lcm", ["Thermo Fisher Arcturus LCM", "Laser microdissection"]],
]);

let previewId = null;
let previewHotspot = null;
let pinnedId = null;
let activeInstrumentGuideTrigger = null;
let activeCalibrationGuidePoint = null;

const calibrationGuideStates = {
  overview: {
    source: "../workflow/calibration/guide_calibration_points_overview.png",
    alt: "QuPath overview showing three widely separated calibration points across the tissue.",
  },
  "point-1": {
    source: "../workflow/calibration/guide_calibration_point_1_closeup.png",
    alt: "Close-up of calibration point 1 placed at a distinctive tissue edge.",
  },
  "point-2": {
    source: "../workflow/calibration/guide_calibration_point_2_closeup.png",
    alt: "Close-up of calibration point 2 placed on a distinctive circular tissue structure.",
  },
  "point-3": {
    source: "../workflow/calibration/guide_calibration_point_3_closeup.png",
    alt: "Close-up of calibration point 3 placed at the edge of a distinctive gland lumen.",
  },
};

function getInstrumentGuide(guideId) {
  const supportedGuide = instrumentGuides.get(guideId);
  if (supportedGuide) {
    return supportedGuide;
  }

  const unavailableGuide = unavailableInstrumentGuides.get(guideId);
  if (!unavailableGuide) {
    return null;
  }

  const [title, type] = unavailableGuide;
  return {
    type,
    title,
    status: "Unavailable",
    file: null,
    introduction: `ShapeX does not currently generate output files for ${title} systems.`,
    details: [],
    steps: [],
    checks: [],
  };
}

function populateInstrumentGuideDetails(container, details) {
  if (!container) {
    return;
  }

  container.replaceChildren(
    ...details.map((detail) => {
      const block = document.createElement("section");
      block.className = "instrument-guide-detail";

      if (detail.heading) {
        const heading = document.createElement("h4");
        heading.textContent = detail.heading;
        block.append(heading);
      }

      if (detail.text) {
        const paragraph = document.createElement("p");
        paragraph.textContent = detail.text;
        block.append(paragraph);
      }

      if (detail.items) {
        const list = document.createElement("ul");
        for (const item of detail.items) {
          const listItem = document.createElement("li");
          const label = document.createElement("strong");
          label.textContent = item.label;
          listItem.append(label, ` — ${item.text}`);
          list.append(listItem);
        }
        block.append(list);
      }

      if (detail.code) {
        const preformatted = document.createElement("pre");
        const code = document.createElement("code");
        code.textContent = detail.code;
        preformatted.append(code);
        block.append(preformatted);
      }

      return block;
    }),
  );
}

function populateInstrumentGuideList(list, items) {
  if (!list) {
    return;
  }

  list.replaceChildren(
    ...items.map((item) => {
      const listItem = document.createElement("li");
      if (typeof item === "string") {
        listItem.textContent = item;
      } else {
        for (const part of item.parts) {
          if (typeof part === "string") {
            listItem.append(part);
          } else {
            const strong = document.createElement("strong");
            strong.textContent = part.strong;
            listItem.append(strong);
          }
        }
      }
      return listItem;
    }),
  );
}

function showCalibrationGuideState(stateName, focusTarget = false) {
  const state = calibrationGuideStates[stateName];
  if (
    !state
    || !calibrationGuideImage
    || !calibrationGuideInstruction
    || !calibrationGuideOverview
  ) {
    return;
  }

  const isOverview = stateName === "overview";
  calibrationGuideImage.src = state.source;
  calibrationGuideImage.alt = state.alt;
  calibrationGuideInstruction.hidden = !isOverview;
  calibrationGuideOverview.hidden = isOverview;

  for (const point of calibrationGuidePoints) {
    point.hidden = !isOverview;
  }

  if (!focusTarget) {
    return;
  }

  if (isOverview) {
    calibrationGuidePoints
      .find((point) => point.dataset.calibrationGuidePoint === activeCalibrationGuidePoint)
      ?.focus();
  } else {
    calibrationGuideOverview?.focus();
  }
}

function openInstrumentGuide(trigger) {
  const guide = getInstrumentGuide(trigger?.dataset.instrumentGuide);
  if (!guide || !instrumentGuideModal) {
    return;
  }

  const steps = guide.steps ?? [];
  const checks = guide.checks ?? [];
  const details = guide.details ?? [];
  const isUnavailable = guide.status === "Unavailable";
  const isAvailable = !isUnavailable;
  const isBlue = trigger.classList.contains("accent-blue");
  activeInstrumentGuideTrigger = trigger;
  instrumentGuideModal.classList.toggle("accent-blue", isBlue);
  instrumentGuideModal.classList.toggle("accent-grey", isUnavailable);
  instrumentGuideModal.classList.toggle("has-calibration-guide", Boolean(guide.calibration));
  instrumentGuideType.textContent = guide.type;
  instrumentGuideTitle.textContent = guide.title;
  instrumentGuideStatus.textContent = guide.status;
  instrumentGuideStatus.classList.toggle("is-supported", isAvailable);
  instrumentGuideIntroduction.textContent = guide.introduction;

  instrumentGuideFileSection.hidden = !guide.file;
  if (guide.file && instrumentGuideFile) {
    instrumentGuideFile.textContent = guide.file;
  }

  instrumentGuideDetailsSection.hidden = details.length === 0;
  instrumentGuideStepsSection.hidden = steps.length === 0;
  instrumentGuideChecksSection.hidden = checks.length === 0;
  populateInstrumentGuideDetails(instrumentGuideDetails, details);
  populateInstrumentGuideList(instrumentGuideSteps, steps);
  populateInstrumentGuideList(instrumentGuideChecks, checks);
  calibrationGuideSection.hidden = !guide.calibration;
  if (guide.calibration) {
    activeCalibrationGuidePoint = null;
    showCalibrationGuideState("overview");
  }
  instrumentGuideUnavailable.hidden = isAvailable;

  trigger.setAttribute("aria-expanded", "true");
  instrumentGuideModal.hidden = false;
  document.body.classList.add("has-open-instrument-guide");
  instrumentGuideCloseControls
    .find((control) => control.classList.contains("instrument-guide-close"))
    ?.focus();
}

function closeInstrumentGuide(restoreFocus = true) {
  if (!instrumentGuideModal || instrumentGuideModal.hidden) {
    return;
  }

  instrumentGuideModal.hidden = true;
  document.body.classList.remove("has-open-instrument-guide");
  instrumentGuideModal.classList.remove("has-calibration-guide");
  activeInstrumentGuideTrigger?.setAttribute("aria-expanded", "false");

  if (restoreFocus) {
    activeInstrumentGuideTrigger?.focus();
  }
  activeInstrumentGuideTrigger = null;
}

function showInstallationCatalogState(stateName) {
  const state = installationCatalogStates[stateName];
  if (!state) {
    return;
  }

  if (installationCatalogScreenshot) {
    installationCatalogScreenshot.src = state.source;
    installationCatalogScreenshot.setAttribute("width", state.width);
    installationCatalogScreenshot.setAttribute("height", state.height);
    installationCatalogScreenshot.alt = state.alt;
  }
  for (const control of installationCatalogStateControls) {
    const isSelected = control.dataset.installationCatalogState === stateName;
    control.classList.toggle("is-active", isSelected);
    control.setAttribute("aria-pressed", String(isSelected));
  }
  for (const highlight of installationCatalogHighlights) {
    highlight.classList.toggle(
      "is-active",
      highlight.dataset.installationHighlightState === stateName,
    );
  }
  if (installationCatalogCopyAction) {
    installationCatalogCopyAction.hidden = stateName !== "catalog";
  }
}

function showInstallationShapexState(stateName) {
  const state = installationShapexStates[stateName];
  if (!state) {
    return;
  }

  if (installationShapexScreenshot) {
    installationShapexScreenshot.src = state.source;
    installationShapexScreenshot.setAttribute("width", state.width);
    installationShapexScreenshot.setAttribute("height", state.height);
    installationShapexScreenshot.alt = state.alt;
  }
  for (const control of installationShapexStateControls) {
    const isSelected = control.dataset.installationShapexState === stateName;
    control.classList.toggle("is-active", isSelected);
    control.setAttribute("aria-pressed", String(isSelected));
  }
  for (const highlight of installationShapexHighlights) {
    highlight.classList.toggle(
      "is-active",
      highlight.dataset.installationShapexHighlightState === stateName,
    );
  }
}

function getInstallationDetail(detailName) {
  if (detailName === "catalog") {
    return {
      detail: installationCatalogDetail,
      preview: installationCatalogPreview,
      initialState: () => showInstallationCatalogState("manager"),
    };
  }
  if (detailName === "shapex") {
    return {
      detail: installationShapexDetail,
      preview: installationShapexPreview,
      initialState: () => showInstallationShapexState("select"),
    };
  }
  return null;
}

function openInstallationDetail(detailName) {
  const selectedDetail = getInstallationDetail(detailName);
  if (!installationStepsOverview || !selectedDetail?.detail || !selectedDetail.preview) {
    return;
  }

  installationStepsOverview.hidden = true;
  installationCatalogDetail.hidden = detailName !== "catalog";
  installationShapexDetail.hidden = detailName !== "shapex";
  if (installationCatalogCard) {
    installationCatalogCard.hidden = true;
  }
  if (installationRequirement) {
    installationRequirement.hidden = true;
  }
  installationCatalogPreview.hidden = detailName !== "catalog";
  installationShapexPreview.hidden = detailName !== "shapex";
  installationLayout?.classList.toggle("is-catalog-detail", true);
  for (const trigger of installationDetailTriggers) {
    trigger.setAttribute(
      "aria-expanded",
      String(trigger.dataset.installationDetailTrigger === detailName),
    );
  }
  selectedDetail.initialState();
  activeInstallationDetail = detailName;
  document.querySelector?.(`[data-installation-detail-back="${detailName}"]`)?.focus();
}

function closeInstallationDetail(restoreFocus = true) {
  if (!installationStepsOverview) {
    return;
  }

  const previousTrigger = installationDetailTriggers.find(
    (trigger) => trigger.dataset.installationDetailTrigger === activeInstallationDetail,
  );
  if (installationCatalogDetail) {
    installationCatalogDetail.hidden = true;
  }
  if (installationShapexDetail) {
    installationShapexDetail.hidden = true;
  }
  if (installationCatalogPreview) {
    installationCatalogPreview.hidden = true;
  }
  if (installationShapexPreview) {
    installationShapexPreview.hidden = true;
  }
  if (installationCatalogCard) {
    installationCatalogCard.hidden = false;
  }
  if (installationRequirement) {
    installationRequirement.hidden = false;
  }
  installationStepsOverview.hidden = false;
  installationLayout?.classList.toggle("is-catalog-detail", false);
  for (const trigger of installationDetailTriggers) {
    trigger.setAttribute("aria-expanded", "false");
  }
  showInstallationCatalogState("manager");
  showInstallationShapexState("select");
  activeInstallationDetail = null;

  if (restoreFocus) {
    previousTrigger?.focus();
  }
}

async function copyInstallationCatalogUrl() {
  const catalogUrl = installationCatalogCopy?.dataset.copyValue;
  if (!catalogUrl || !installationCatalogCopy) {
    return;
  }

  let copied = false;
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(catalogUrl);
      copied = true;
    } catch {
      // Fall back for local files and browsers that restrict Clipboard API access.
    }
  }

  if (!copied) {
    const copyField = document.createElement("textarea");
    copyField.value = catalogUrl;
    copyField.setAttribute("readonly", "");
    copyField.style.position = "fixed";
    copyField.style.opacity = "0";
    document.body.append(copyField);
    copyField.select();
    try {
      copied = document.execCommand("copy");
    } catch {
      copied = false;
    } finally {
      copyField.remove();
    }
  }

  installationCatalogCopy.textContent = copied ? "Copied!" : "Copy failed";
  installationCatalogCopy.classList.toggle("is-copied", copied);
  if (installationCatalogCopyStatus) {
    installationCatalogCopyStatus.textContent = copied
      ? "CDL catalog link copied to the clipboard."
      : "The CDL catalog link could not be copied.";
  }

  window.setTimeout(() => {
    installationCatalogCopy.textContent = "Copy catalog link";
    installationCatalogCopy.classList.remove("is-copied");
    if (installationCatalogCopyStatus) {
      installationCatalogCopyStatus.textContent = "";
    }
  }, 1800);
}

const mainWindowLayout = document.querySelector?.(".main-window-layout");
const mainControlsOverview = document.getElementById?.("main-controls-overview");
const mainThemeDetail = document.getElementById?.("main-theme-detail");
const mainThemeBack = document.querySelector?.('[data-main-detail-back="main-theme-detail"]');
const mainThemeTrigger = controlsById.get("1");
const mainRunDetail = document.getElementById?.("main-run-detail");
const mainRunBack = document.querySelector?.('[data-main-detail-back="main-run-detail"]');
const mainRunTrigger = controlsById.get("4");
const mainRunSubpanels = [...document.querySelectorAll("[data-main-run-panel]")];
const mainRunPanels = [mainRunDetail, ...mainRunSubpanels].filter(Boolean);
const mainRunPanelBackButtons = [
  ...document.querySelectorAll("[data-main-run-panel-back]"),
];
const mainRunPanelTriggers = controls.filter((control) => control.dataset.mainRunTarget);
const mainRunPanelScopes = new Map([
  ["main-run-detail", "run"],
  ["main-run-manual-detail", "run-manual"],
  ["main-run-completed-detail", "run-completed"],
  ["main-run-automatic-detail", "run-automatic"],
]);
const mainWindowScreenshot = document.getElementById?.("main-window-screenshot");
const outputsScreenshot = document.getElementById?.("outputs-screenshot");
const plateDesignerLayout = document.querySelector?.(".plate-designer-layout");
const plateControlsOverview = document.getElementById?.("plate-controls-overview");
const plateDesignerScreenshot = document.getElementById?.("plate-designer-screenshot");
const plateDetailBackButtons = [...document.querySelectorAll("[data-detail-back]")];
const plateDetailConfigurations = new Map(
  controls
    .filter((control) => control.dataset.detailTarget?.startsWith("plate-"))
    .map((trigger) => {
      const detailId = trigger.dataset.detailTarget;
      return [
        detailId,
        {
          panel: document.getElementById?.(detailId),
          back: plateDetailBackButtons.find((button) => button.dataset.detailBack === detailId),
          trigger,
        },
      ];
    }),
);
const tessellatorLayout = document.querySelector?.(".tessellator-layout");
const tessellatorControlsOverview = document.getElementById?.("tessellator-controls-overview");
const tessellatorScreenshot = document.getElementById?.("shape-tessellator-screenshot");
const tessellatorDetailBackButtons = [...document.querySelectorAll("[data-tess-detail-back]")];
const tessellatorDetailConfigurations = new Map(
  controls
    .filter((control) => control.dataset.detailTarget?.startsWith("tess-"))
    .map((trigger) => {
      const detailId = trigger.dataset.detailTarget;
      return [
        detailId,
        {
          panel: document.getElementById?.(detailId),
          back: tessellatorDetailBackButtons.find(
            (button) => button.dataset.tessDetailBack === detailId,
          ),
          trigger,
        },
      ];
    }),
);

let activePlateDetailId = null;
let activeTessellatorDetailId = null;
let activeMainRunPanelId = null;
let mainRunPanelHistory = [];

const mainWindowOverviewImage = {
  source: "../main/guide_main.png",
  width: "1098",
  height: "307",
  alt: "The ShapeX main window with theme and guide buttons, Run controls, and the Outputs, Shape Tessellator, and Plate Designer sections.",
};
const mainThemeImageSources = [
  "../main/colorpicker/guide_color_picker_primary.png",
  "../main/colorpicker/guide_color_picker_secondary.png",
];
const mainThemeStateControl = controlsById.get("main-theme-6");
let mainThemeImageState = 0;

const guideOverviewImages = {
  run: {
    screenshot: mainWindowScreenshot,
    source: "../main/Run/guide_run_geometry_attention.png",
    width: "588",
    height: "368",
    alt: "The ShapeX geometry-attention dialog with repair, skip, and manual-review choices.",
  },
  "run-manual": {
    screenshot: mainWindowScreenshot,
    source: "../main/Run/guide_run_manual_combined_repair.png",
    width: "1048",
    height: "746",
    alt: "Manual geometry review showing a combined component-and-hole repair.",
  },
  "run-completed": {
    screenshot: mainWindowScreenshot,
    source: "../main/Run/guide_run_generation_completed.png",
    width: "481",
    height: "261",
    alt: "ShapeX confirming generation and offering to review or add automatic fixes.",
  },
  "run-automatic": {
    screenshot: mainWindowScreenshot,
    source: "../main/Run/guide_run_automatic_fixes.png",
    width: "1048",
    height: "746",
    alt: "Automatic geometry-fix review showing filled and removed regions.",
  },
  outputs: {
    screenshot: outputsScreenshot,
    source: "../outputs/guide_outputs_overview.png",
    width: "1098",
    height: "434",
    alt: "The expanded ShapeX Outputs section with laser-microdissection, plate-overview, i.DOT dispensing, and AnnData output choices.",
  },
  plate: {
    screenshot: plateDesignerScreenshot,
    source: "../platedesigner/guide_plate_designer.png",
    width: "1098",
    height: "872",
    alt: "The expanded ShapeX Plate Designer with plate settings, a well-assignment preview, image selection, and plate controls.",
  },
  tessellator: {
    screenshot: tessellatorScreenshot,
    source: "../shapetessellator/guide_shape_tessellator.png",
    width: "1098",
    height: "759",
    alt: "The expanded ShapeX Shape Tessellator with tessellation modes, display options, grid controls, previews, and annotation selection.",
  },
};

const checkboxGroupConfigurations = new Map([
  ["tess-display", {
    scope: "tessellator",
    options: {
      "hide-grid": {
        bit: 1,
        labels: ["Grid visible", "Grid hidden"],
      },
      "show-image": {
        bit: 2,
        labels: ["Source image hidden", "Source image shown"],
      },
      "assign-classes": {
        bit: 4,
        labels: ["Classes not assigned", "Classes assigned"],
      },
    },
    images: {
      0: "../shapetessellator/display/guide_shape_tessellator_display_default.png",
      1: "../shapetessellator/display/guide_shape_tessellator_display_hide_grid.png",
      2: "../shapetessellator/display/guide_shape_tessellator_display_show_image.png",
      3: "../shapetessellator/display/guide_shape_tessellator_display_hide_grid_show_image.png",
      4: "../shapetessellator/display/guide_shape_tessellator_display_assign_classes.png",
      5: "../shapetessellator/display/guide_shape_tessellator_display_hide_grid_assign_classes.png",
      6: "../shapetessellator/display/guide_shape_tessellator_display_show_image_assign_classes.png",
      7: "../shapetessellator/display/guide_shape_tessellator_display_hide_grid_show_image_assign_classes.png",
    },
    width: "1098",
    height: "759",
  }],
  ["tess-flip", {
    scope: "tessellator",
    options: {
      vertical: {
        bit: 2,
        labels: ["Vertical flip off", "Vertical flip on"],
      },
      horizontal: {
        bit: 1,
        labels: ["Horizontal flip off", "Horizontal flip on"],
      },
    },
    images: {
      0: guideOverviewImages.tessellator.source,
      1: "../shapetessellator/orientation/guide_shape_tessellator_flip_horizontal.png",
      2: "../shapetessellator/orientation/guide_shape_tessellator_flip_vertical.png",
      3: "../shapetessellator/orientation/guide_shape_tessellator_flip_both_axes.png",
    },
    width: "1098",
    height: "759",
  }],
]);
const checkboxGroupStates = new Map(
  [...checkboxGroupConfigurations.keys()].map((name) => [name, 0]),
);

const imageSequenceConfigurations = new Map([
  ["run-attention", {
    scope: "run",
    sources: [guideOverviewImages.run.source],
    labels: ["Geometry handling choices"],
    alts: [guideOverviewImages.run.alt],
    width: "588",
    height: "368",
    initialState: -1,
    restoreState: 0,
    idleLabel: "Select to view",
  }],
  ["run-manual-combined", {
    scope: "run-manual",
    sources: [
      "../main/Run/guide_run_manual_combined_repair.png",
      "../main/Run/guide_run_manual_combined_repair_show_image.png",
      "../main/Run/guide_run_manual_combined_skip.png",
      "../main/Run/guide_run_manual_combined_skip_show_image.png",
    ],
    labels: [
      "Repair selected, image hidden",
      "Repair selected, image shown",
      "Skip selected, image hidden",
      "Skip selected, image shown",
    ],
    alts: [
      "Manual geometry review showing a combined component-and-hole repair with the source image hidden.",
      "Manual geometry review showing a combined component-and-hole repair over the source image.",
      "Manual geometry review showing a combined component-and-hole annotation excluded from output with the source image hidden.",
      "Manual geometry review showing a combined component-and-hole annotation excluded from output over the source image.",
    ],
    width: "1048",
    height: "746",
    initialState: -1,
    restoreState: 0,
    idleLabel: "Select to view",
  }],
  ["run-manual-components", {
    scope: "run-manual",
    sources: [
      "../main/Run/guide_run_manual_components_merge.png",
      "../main/Run/guide_run_manual_components_merge_show_image.png",
      "../main/Run/guide_run_manual_components_keep.png",
      "../main/Run/guide_run_manual_components_keep_show_image.png",
      "../main/Run/guide_run_manual_components_skip.png",
      "../main/Run/guide_run_manual_components_skip_show_image.png",
    ],
    labels: [
      "Merge selected, image hidden",
      "Merge selected, image shown",
      "Keep selected, image hidden",
      "Keep selected, image shown",
      "Skip selected, image hidden",
      "Skip selected, image shown",
    ],
    alts: [
      "Manual geometry review showing disconnected components merged with the source image hidden.",
      "Manual geometry review showing disconnected components merged over the source image.",
      "Manual geometry review showing disconnected components kept separately with the source image hidden.",
      "Manual geometry review showing disconnected components kept separately over the source image.",
      "Manual geometry review showing disconnected components excluded from output with the source image hidden.",
      "Manual geometry review showing disconnected components excluded from output over the source image.",
    ],
    width: "1048",
    height: "746",
    initialState: -1,
    idleLabel: "Select to view",
  }],
  ["run-automatic-fixes", {
    scope: "run-automatic",
    sources: [
      "../main/Run/guide_run_automatic_fixes.png",
      "../main/Run/guide_run_automatic_fixes_show_image.png",
    ],
    labels: ["Source image hidden", "Source image shown"],
    alts: [
      "Automatic geometry-fix review showing filled and removed regions with the source image hidden.",
      "Automatic geometry-fix review showing filled and removed regions over the source image.",
    ],
    width: "1048",
    height: "746",
    initialState: -1,
    restoreState: 0,
    idleLabel: "Select to view",
  }],
  ["run-completed", {
    scope: "run-completed",
    sources: ["../main/Run/guide_run_generation_completed.png"],
    labels: ["Generation completed"],
    alts: ["ShapeX confirming generation and offering to review or add automatic fixes."],
    width: "481",
    height: "261",
    initialState: -1,
    restoreState: 0,
    idleLabel: "Select to view",
  }],
  ["outputs-sections", {
    scope: "outputs",
    sources: [
      guideOverviewImages.outputs.source,
      "../outputs/guide_outputs_laser_microdissection.png",
      "../outputs/guide_outputs_plate_overview_formats.png",
      "../outputs/guide_outputs_idot_dispensing.png",
    ],
    labels: [
      "Outputs overview",
      "Laser-microdissection systems",
      "Plate-overview formats",
      "i.DOT dispensing steps",
    ],
    alts: [
      guideOverviewImages.outputs.alt,
      "The ShapeX laser-microdissection output settings with Leica Microsystems LMD selected.",
      "The ShapeX plate-overview settings with the available spreadsheet and delimited-text formats.",
      "The ShapeX i.DOT dispensing settings with liquid names, volumes, and add or remove controls.",
    ],
    widths: ["1098", "1098", "1098", "1098"],
    heights: ["434", "613", "613", "563"],
    initialState: 0,
    restoreState: 0,
  }],
  ["plate-format", {
    scope: "plate",
    sources: [
      "../platedesigner/dimensions/guide_plate_designer_format_96.png",
      guideOverviewImages.plate.source,
      "../platedesigner/dimensions/guide_plate_designer_format_1536.png",
      "../platedesigner/dimensions/guide_plate_designer_format_custom.png",
    ],
    labels: ["96-well plate", "384-well plate", "1536-well plate", "Custom 15 × 15 plate"],
    initialState: 1,
  }],
  ["plate-legend-samples", {
    scope: "plate",
    sources: [
      guideOverviewImages.plate.source,
      "../platedesigner/legend/guide_plate_designer_samples_uniform.png",
      "../platedesigner/legend/guide_plate_designer_samples_symbols.png",
    ],
    labels: ["Samples colored by class", "Uniform sample color", "Sample symbols"],
  }],
  ["plate-legend-available", {
    scope: "plate",
    sources: [guideOverviewImages.plate.source, "../platedesigner/legend/guide_plate_designer_available_markers.png"],
    labels: ["Available wells", "Available wells highlighted"],
  }],
  ["plate-slide-selection", {
    scope: "plate",
    sources: [guideOverviewImages.plate.source, "../platedesigner/images/guide_plate_designer_additional_slide_selected.png"],
    labels: ["Initial image selection", "Additional image selected"],
  }],
  ["plate-randomization", {
    scope: "plate",
    sources: [guideOverviewImages.plate.source, "../platedesigner/randomization/guide_plate_designer_randomization_disabled.png"],
    labels: ["Randomization enabled", "Randomization disabled"],
    titles: ["Randomize Samples", "Sequential assignment"],
    descriptions: [
      "Shuffle sample-to-well assignments instead of placing samples in their original order.",
      "Assign samples to wells sequentially instead of randomizing their placement.",
    ],
  }],
  ["plate-new-seed", {
    scope: "plate",
    sources: [guideOverviewImages.plate.source, "../platedesigner/randomization/guide_plate_designer_new_seed.png"],
    labels: ["Original seed", "New randomized seed"],
  }],
  ["tess-mode", {
    scope: "tessellator",
    sources: [
      "../shapetessellator/mode/guide_shape_tessellator_fit_to_shape.png",
      guideOverviewImages.tessellator.source,
      "../shapetessellator/mode/guide_shape_tessellator_remove_polygons.png",
    ],
    labels: ["Fit to Shape", "Add Polygons", "Remove Polygons"],
    titles: ["Fit to Shape", "Add Polygons", "Remove Polygons"],
    descriptions: [
      "Clip the grid so polygons follow the annotation boundary.",
      "Keep partial boundary polygons that overlap the annotation.",
      "Remove partial polygons that cross the annotation boundary.",
    ],
    initialState: 1,
  }],
  ["tess-grid-shape", {
    scope: "tessellator",
    sources: [
      "../shapetessellator/grid/guide_shape_tessellator_grid_triangle.png",
      "../shapetessellator/grid/guide_shape_tessellator_grid_square.png",
      guideOverviewImages.tessellator.source,
    ],
    labels: ["Triangular grid", "Square grid", "Hexagonal grid"],
    initialState: 2,
  }],
  ["tess-preview-availability", {
    scope: "tessellator",
    sources: [
      guideOverviewImages.tessellator.source,
      "../shapetessellator/grid/guide_shape_tessellator_grid_disabled.png",
    ],
    labels: ["Preview enabled", "Preview disabled"],
  }],
]);
const imageSequenceStates = new Map(
  [...imageSequenceConfigurations].map(([name, configuration]) => [
    name,
    configuration.initialState ?? 0,
  ]),
);

if (typeof Image !== "undefined") {
  const sequenceSources = [...imageSequenceConfigurations.values()].flatMap(
    (configuration) => configuration.sources,
  );
  const checkboxGroupSources = [...checkboxGroupConfigurations.values()].flatMap(
    (configuration) => Object.values(configuration.images),
  );
  for (const source of [...new Set([
    ...sequenceSources,
    ...checkboxGroupSources,
    ...mainThemeImageSources,
  ])]) {
    const image = new Image();
    image.src = source;
  }
}

function renderActiveControl() {
  const activeId = previewId ?? pinnedId;

  for (const control of controls) {
    const isActive = control.dataset.control === activeId;
    const isPinned = control.dataset.control === pinnedId;
    control.classList.toggle("is-active", isActive);
    control.setAttribute("aria-pressed", String(isPinned));
  }

  for (const highlight of highlights) {
    const sequenceName = highlight.dataset.sequenceHighlight;
    const sequenceState = Number.parseInt(highlight.dataset.sequenceState ?? "", 10);
    const sequenceStates = highlight.dataset.sequenceStates
      ?.split(/\s+/)
      .map((state) => Number.parseInt(state, 10));
    const currentSequenceState = imageSequenceStates.get(sequenceName);
    const isCurrentSequenceState = !sequenceName
      || (sequenceStates?.length
        ? sequenceStates.includes(currentSequenceState)
        : currentSequenceState === sequenceState);
    const directHighlightGroup = previewHotspot?.dataset.highlightGroup;
    const hasDirectHotspotPreview =
      previewHotspot?.dataset.highlight === activeId;
    const isActive = hasDirectHotspotPreview
      ? (directHighlightGroup
        ? highlight.dataset.highlightGroup === directHighlightGroup && isCurrentSequenceState
        : previewHotspot === highlight)
      : highlight.dataset.highlight === activeId && isCurrentSequenceState;
    highlight.classList.toggle("is-current-sequence-state", isCurrentSequenceState);
    highlight.classList.toggle("is-active", isActive);
  }
}

function preview(control) {
  previewHotspot = null;
  previewId = control.dataset.control;
  renderActiveControl();
}

function stopPreview(control) {
  if (previewId === control.dataset.control) {
    previewId = null;
    renderActiveControl();
  }
}

function clearActiveControl() {
  previewHotspot = null;
  previewId = null;
  pinnedId = null;
  renderActiveControl();
}

function setDetailHotspotScope(detailId) {
  for (const highlight of highlights) {
    if (highlight.dataset.detailScope) {
      highlight.classList.toggle("is-interactive", highlight.dataset.detailScope === detailId);
    }
  }
}

function updateMainThemeImage() {
  const isPrimary = mainThemeImageState === 0;

  if (mainWindowScreenshot) {
    mainWindowScreenshot.src = mainThemeImageSources[mainThemeImageState];
    mainWindowScreenshot.setAttribute("width", "654");
    mainWindowScreenshot.setAttribute("height", "340");
    mainWindowScreenshot.alt =
      `The ShapeX Custom Colors dialog with the ${isPrimary ? "Primary" : "Secondary"} theme color selected.`;
  }

  const dots = mainThemeStateControl?.querySelectorAll?.("[data-state-dot]") ?? [];
  for (const [index, dot] of [...dots].entries()) {
    dot.classList.toggle("is-active", index === mainThemeImageState);
  }

  const label = mainThemeStateControl?.querySelector?.("[data-state-label]");
  if (label) {
    label.textContent = isPrimary ? "Primary color" : "Secondary color";
  }
}

function restoreMainWindowImage() {
  mainThemeImageState = 0;

  if (mainWindowScreenshot) {
    mainWindowScreenshot.src = mainWindowOverviewImage.source;
    mainWindowScreenshot.setAttribute("width", mainWindowOverviewImage.width);
    mainWindowScreenshot.setAttribute("height", mainWindowOverviewImage.height);
    mainWindowScreenshot.alt = mainWindowOverviewImage.alt;
  }

  const dots = mainThemeStateControl?.querySelectorAll?.("[data-state-dot]") ?? [];
  for (const [index, dot] of [...dots].entries()) {
    dot.classList.toggle("is-active", index === 0);
  }

  const label = mainThemeStateControl?.querySelector?.("[data-state-label]");
  if (label) {
    label.textContent = "Primary color";
  }
}

function cycleMainThemeImage() {
  mainThemeImageState = (mainThemeImageState + 1) % mainThemeImageSources.length;
  updateMainThemeImage();
}

function updateImageSequenceIndicator(sequenceName, state) {
  const configuration = imageSequenceConfigurations.get(sequenceName);
  const sequenceControls = controls.filter(
    (candidate) => candidate.dataset.imageSequence === sequenceName,
  );
  if (!configuration || sequenceControls.length === 0) {
    return;
  }

  for (const control of sequenceControls) {
    const requestedState = Number.parseInt(control.dataset.imageState ?? "", 10);
    const indicatorState = Number.isInteger(requestedState)
      ? Number(state === requestedState)
      : state;
    const dots = control.querySelectorAll?.("[data-state-dot]") ?? [];
    for (const [index, dot] of [...dots].entries()) {
      const dotState = Number.parseInt(dot.dataset.stateDot ?? "", 10);
      const representedState = Number.isInteger(dotState) ? dotState : index;
      dot.classList.toggle("is-active", representedState === indicatorState);
    }

    const label = control.querySelector?.("[data-state-label]");
    if (label) {
      label.textContent = Number.isInteger(requestedState)
        ? (indicatorState === 1 ? "Expanded" : "Collapsed")
        : (configuration.labels[state] ?? configuration.idleLabel ?? "");
    }

    const title = control.querySelector?.("[data-state-title]");
    if (title && configuration.titles?.[state]) {
      title.textContent = configuration.titles[state];
    }

    const description = control.querySelector?.("[data-state-description]");
    if (description && configuration.descriptions?.[state]) {
      description.textContent = configuration.descriptions[state];
    }
  }
}

function updateCheckboxGroupIndicators(groupName) {
  const configuration = checkboxGroupConfigurations.get(groupName);
  const state = checkboxGroupStates.get(groupName) ?? 0;
  if (!configuration) {
    return;
  }

  for (const control of controls.filter(
    (candidate) => candidate.dataset.checkboxGroup === groupName,
  )) {
    const option = configuration.options[control.dataset.checkboxOption];
    if (!option) {
      continue;
    }

    const isEnabled = Boolean(state & option.bit);
    for (const dot of control.querySelectorAll?.("[data-state-dot]") ?? []) {
      dot.classList.toggle(
        "is-active",
        Number.parseInt(dot.dataset.stateDot ?? "", 10) === Number(isEnabled),
      );
    }

    const label = control.querySelector?.("[data-state-label]");
    if (label) {
      label.textContent = option.labels[Number(isEnabled)];
    }
  }
}

function resetCheckboxGroups(scope) {
  for (const [groupName, configuration] of checkboxGroupConfigurations) {
    if (configuration.scope !== scope) {
      continue;
    }
    checkboxGroupStates.set(groupName, 0);
    updateCheckboxGroupIndicators(groupName);
  }
}

function updateCheckboxGroupImage(groupName) {
  const configuration = checkboxGroupConfigurations.get(groupName);
  const state = checkboxGroupStates.get(groupName) ?? 0;
  const overview = guideOverviewImages[configuration?.scope];
  const source = configuration?.images[state];
  if (!configuration || !overview?.screenshot || !source) {
    return;
  }

  overview.screenshot.src = source;
  overview.screenshot.setAttribute("width", configuration.width ?? overview.width);
  overview.screenshot.setAttribute("height", configuration.height ?? overview.height);

  const enabledOptions = Object.entries(configuration.options)
    .filter(([, option]) => Boolean(state & option.bit))
    .map(([optionName]) => optionName.replaceAll("-", " "));
  overview.screenshot.alt = enabledOptions.length === 0
    ? overview.alt
    : `${overview.alt} Enabled display options: ${enabledOptions.join(", ")}.`;
}

function toggleCheckboxGroupOption(control) {
  const groupName = control.dataset.checkboxGroup;
  const configuration = checkboxGroupConfigurations.get(groupName);
  const option = configuration?.options[control.dataset.checkboxOption];
  if (!configuration || !option) {
    return;
  }

  const nextState = (checkboxGroupStates.get(groupName) ?? 0) ^ option.bit;
  checkboxGroupStates.set(groupName, nextState);
  updateCheckboxGroupIndicators(groupName);

  for (const [name, candidate] of imageSequenceConfigurations) {
    if (candidate.scope !== configuration.scope) {
      continue;
    }
    const initialState = candidate.initialState ?? 0;
    imageSequenceStates.set(name, initialState);
    updateImageSequenceIndicator(name, initialState);
  }

  updateCheckboxGroupImage(groupName);
}

function restoreGuideImage(scope) {
  const overview = guideOverviewImages[scope];
  if (!overview) {
    return;
  }

  if (overview.screenshot) {
    overview.screenshot.src = overview.source;
    overview.screenshot.setAttribute("width", overview.width);
    overview.screenshot.setAttribute("height", overview.height);
    overview.screenshot.alt = overview.alt;
  }

  for (const [name, configuration] of imageSequenceConfigurations) {
    if (configuration.scope !== scope) {
      continue;
    }
    const initialState = configuration.restoreState ?? configuration.initialState ?? 0;
    imageSequenceStates.set(name, initialState);
    updateImageSequenceIndicator(name, initialState);
  }
  resetCheckboxGroups(scope);
}

function cycleGuideImage(sequenceName) {
  const configuration = imageSequenceConfigurations.get(sequenceName);
  if (!configuration) {
    return;
  }

  const nextState = ((imageSequenceStates.get(sequenceName) ?? 0) + 1)
    % configuration.sources.length;
  setGuideImageState(sequenceName, nextState);
}

function setGuideImageState(sequenceName, nextState) {
  const configuration = imageSequenceConfigurations.get(sequenceName);
  if (
    !configuration
    || !Number.isInteger(nextState)
    || nextState < 0
    || nextState >= configuration.sources.length
  ) {
    return;
  }

  for (const [name, candidate] of imageSequenceConfigurations) {
    if (candidate.scope !== configuration.scope || name === sequenceName) {
      continue;
    }
    const initialState = candidate.initialState ?? 0;
    imageSequenceStates.set(name, initialState);
    updateImageSequenceIndicator(name, initialState);
  }

  imageSequenceStates.set(sequenceName, nextState);
  updateImageSequenceIndicator(sequenceName, nextState);
  resetCheckboxGroups(configuration.scope);

  const overview = guideOverviewImages[configuration.scope];
  if (overview?.screenshot) {
    overview.screenshot.src = configuration.sources[nextState];
    const width = configuration.widths?.[nextState] ?? configuration.width;
    const height = configuration.heights?.[nextState] ?? configuration.height;
    if (width && height) {
      overview.screenshot.setAttribute("width", width);
      overview.screenshot.setAttribute("height", height);
    }
    overview.screenshot.alt = configuration.alts?.[nextState]
      ?? `${overview.alt} Current state: ${configuration.labels[nextState]}.`;
  }
}

function activateSequenceHotspot(highlight) {
  const sequenceName = highlight.dataset.sequenceControl;
  const configuration = imageSequenceConfigurations.get(sequenceName);
  if (!configuration) {
    return;
  }

  const savedState = imageSequenceStates.get(sequenceName);
  const fallbackState = configuration.restoreState ?? 0;
  const currentState = Number.isInteger(savedState) && savedState >= 0
    ? savedState
    : fallbackState;
  let nextState = currentState;

  if (highlight.dataset.sequenceTargetState !== undefined) {
    nextState = Number.parseInt(highlight.dataset.sequenceTargetState, 10);
  } else if (highlight.dataset.sequenceOptionState !== undefined) {
    const optionState = Number.parseInt(highlight.dataset.sequenceOptionState, 10);
    nextState = optionState + (currentState % 2);
  } else if (highlight.dataset.sequenceAction === "toggle-image") {
    nextState = currentState % 2 === 0 ? currentState + 1 : currentState - 1;
  }

  setGuideImageState(sequenceName, nextState);
  pinnedId = null;
  renderActiveControl();
}

function openMainThemeDetail() {
  if (!mainControlsOverview || !mainThemeDetail) {
    return;
  }

  mainControlsOverview.hidden = true;
  mainThemeDetail.hidden = false;
  mainWindowLayout?.classList.toggle("is-detail-view", true);
  mainThemeTrigger?.setAttribute("aria-expanded", "true");
  mainThemeImageState = 0;
  updateMainThemeImage();
  setDetailHotspotScope("main-theme-detail");
  clearActiveControl();
  mainThemeBack?.focus();
}

function closeMainThemeDetail(restoreFocus = true) {
  if (!mainControlsOverview || !mainThemeDetail) {
    return;
  }

  mainThemeDetail.hidden = true;
  mainControlsOverview.hidden = false;
  mainWindowLayout?.classList.toggle("is-detail-view", false);
  mainThemeTrigger?.setAttribute("aria-expanded", "false");
  setDetailHotspotScope(null);
  restoreMainWindowImage();
  clearActiveControl();

  if (restoreFocus) {
    mainThemeTrigger?.focus();
    mainThemeTrigger?.scrollIntoView({ block: "nearest" });
  }
}

function openMainRunDetail() {
  if (!mainControlsOverview || !mainRunDetail) {
    return;
  }

  mainControlsOverview.hidden = true;
  mainWindowLayout?.classList.toggle("is-detail-view", true);
  mainWindowLayout?.classList.toggle("is-run-detail", true);
  mainRunTrigger?.setAttribute("aria-expanded", "true");
  activeMainRunPanelId = null;
  mainRunPanelHistory = [];
  showMainRunPanel("main-run-detail", true, null, false);
}

function showMainRunPanel(
  panelId,
  focusBack = true,
  navigationTrigger = null,
  recordHistory = true,
) {
  const panel = document.getElementById?.(panelId);
  if (!panel || !mainRunPanels.includes(panel)) {
    return;
  }

  if (
    recordHistory
    && activeMainRunPanelId
    && activeMainRunPanelId !== panelId
  ) {
    mainRunPanelHistory.push({
      panelId: activeMainRunPanelId,
      trigger: navigationTrigger,
    });
  }

  for (const candidate of mainRunPanels) {
    candidate.hidden = candidate !== panel;
  }
  for (const trigger of mainRunPanelTriggers) {
    trigger.setAttribute("aria-expanded", String(trigger === navigationTrigger));
  }

  activeMainRunPanelId = panelId;
  setDetailHotspotScope(panelId);
  restoreGuideImage(mainRunPanelScopes.get(panelId));
  clearActiveControl();

  if (focusBack) {
    panel.querySelector?.(".detail-back")?.focus();
  }
}

function returnToPreviousMainRunPanel(fallbackPanelId, restoreFocus = true) {
  const currentPanelId = activeMainRunPanelId;
  const historyEntry = mainRunPanelHistory.pop();
  const returnTrigger = historyEntry?.trigger
    ?? mainRunPanelTriggers.find(
      (trigger) => trigger.dataset.mainRunTarget === currentPanelId,
    );
  const targetPanelId = historyEntry?.panelId ?? fallbackPanelId;
  if (!targetPanelId) {
    return;
  }

  const navigationTrigger = mainRunPanelHistory.at(-1)?.trigger ?? null;
  showMainRunPanel(
    targetPanelId,
    false,
    navigationTrigger,
    false,
  );

  if (restoreFocus) {
    returnTrigger?.focus();
    returnTrigger?.scrollIntoView({ block: "nearest" });
  }
}

function closeMainRunDetail(restoreFocus = true) {
  if (!mainControlsOverview || !mainRunDetail) {
    return;
  }

  for (const panel of mainRunPanels) {
    panel.hidden = true;
  }
  for (const trigger of mainRunPanelTriggers) {
    trigger.setAttribute("aria-expanded", "false");
  }
  mainControlsOverview.hidden = false;
  mainWindowLayout?.classList.toggle("is-detail-view", false);
  mainWindowLayout?.classList.toggle("is-run-detail", false);
  mainRunTrigger?.setAttribute("aria-expanded", "false");
  activeMainRunPanelId = null;
  mainRunPanelHistory = [];
  setDetailHotspotScope(null);
  restoreMainWindowImage();
  clearActiveControl();

  if (restoreFocus) {
    mainRunTrigger?.focus();
    mainRunTrigger?.scrollIntoView({ block: "nearest" });
  }
}

function openPlateDetail(detailId) {
  const configuration = plateDetailConfigurations.get(detailId);
  if (!plateControlsOverview || !configuration?.panel) {
    return;
  }

  plateControlsOverview.hidden = true;
  for (const [candidateId, candidate] of plateDetailConfigurations) {
    if (candidate.panel) {
      candidate.panel.hidden = candidateId !== detailId;
    }
    candidate.trigger?.setAttribute("aria-expanded", String(candidateId === detailId));
  }

  activePlateDetailId = detailId;
  plateDesignerLayout?.classList.toggle("is-detail-view", true);
  if (plateDesignerLayout?.dataset) {
    plateDesignerLayout.dataset.activeDetail = detailId;
  }
  setDetailHotspotScope(detailId);
  restoreGuideImage("plate");
  clearActiveControl();
  configuration.back?.focus();
}

function closePlateDetail(restoreFocus = true) {
  if (!plateControlsOverview) {
    return;
  }

  const configuration = plateDetailConfigurations.get(activePlateDetailId);

  for (const candidate of plateDetailConfigurations.values()) {
    if (candidate.panel) {
      candidate.panel.hidden = true;
    }
    candidate.trigger?.setAttribute("aria-expanded", "false");
  }

  plateControlsOverview.hidden = false;
  plateDesignerLayout?.classList.toggle("is-detail-view", false);
  if (plateDesignerLayout?.dataset) {
    delete plateDesignerLayout.dataset.activeDetail;
  }
  setDetailHotspotScope(null);
  restoreGuideImage("plate");
  clearActiveControl();
  activePlateDetailId = null;

  if (restoreFocus) {
    configuration?.trigger?.focus();
    configuration?.trigger?.scrollIntoView({ block: "nearest" });
  }
}

function openTessellatorDetail(detailId) {
  const configuration = tessellatorDetailConfigurations.get(detailId);
  if (!tessellatorControlsOverview || !configuration?.panel) {
    return;
  }

  tessellatorControlsOverview.hidden = true;
  for (const [candidateId, candidate] of tessellatorDetailConfigurations) {
    if (candidate.panel) {
      candidate.panel.hidden = candidateId !== detailId;
    }
    candidate.trigger?.setAttribute("aria-expanded", String(candidateId === detailId));
  }

  activeTessellatorDetailId = detailId;
  tessellatorLayout?.classList.toggle("is-detail-view", true);
  setDetailHotspotScope(detailId);
  restoreGuideImage("tessellator");
  if (detailId === "tess-options-detail") {
    updateCheckboxGroupImage("tess-display");
  }
  clearActiveControl();
  configuration.back?.focus();
}

function closeTessellatorDetail(restoreFocus = true) {
  if (!tessellatorControlsOverview) {
    return;
  }

  const configuration = tessellatorDetailConfigurations.get(activeTessellatorDetailId);
  for (const candidate of tessellatorDetailConfigurations.values()) {
    if (candidate.panel) {
      candidate.panel.hidden = true;
    }
    candidate.trigger?.setAttribute("aria-expanded", "false");
  }

  tessellatorControlsOverview.hidden = false;
  tessellatorLayout?.classList.toggle("is-detail-view", false);
  setDetailHotspotScope(null);
  restoreGuideImage("tessellator");
  clearActiveControl();
  activeTessellatorDetailId = null;

  if (restoreFocus) {
    configuration?.trigger?.focus();
    configuration?.trigger?.scrollIntoView({ block: "nearest" });
  }
}

function activateControl(control) {
  if (control.dataset.tabTarget) {
    const targetTab = document.getElementById?.(control.dataset.tabTarget);
    if (targetTab) {
      activateTab(targetTab);
    }
    return;
  }

  if (control.dataset.detailClose === "main-theme-detail") {
    closeMainThemeDetail();
    return;
  }

  if (control.dataset.detailClose === "main-run-detail") {
    closeMainRunDetail();
    return;
  }

  if (control.dataset.mainRunBack) {
    returnToPreviousMainRunPanel(control.dataset.mainRunBack);
    return;
  }

  if (control.dataset.detailTarget) {
    if (control.dataset.detailTarget === "main-theme-detail") {
      openMainThemeDetail();
    } else if (control.dataset.detailTarget === "main-run-detail") {
      openMainRunDetail();
    } else if (control.dataset.detailTarget.startsWith("tess-")) {
      openTessellatorDetail(control.dataset.detailTarget);
    } else {
      openPlateDetail(control.dataset.detailTarget);
    }
    return;
  }

  if (control.dataset.mainRunTarget) {
    showMainRunPanel(control.dataset.mainRunTarget, true, control);
    return;
  }

  if ("themeImageSequence" in control.dataset) {
    cycleMainThemeImage();
    pinnedId = null;
    renderActiveControl();
    return;
  }

  if (control.dataset.checkboxGroup) {
    toggleCheckboxGroupOption(control);
    pinnedId = null;
    renderActiveControl();
    return;
  }

  if (control.dataset.imageSequence) {
    const requestedState = Number.parseInt(control.dataset.imageState ?? "", 10);
    if (Number.isInteger(requestedState)) {
      const collapseState = Number.parseInt(control.dataset.imageCollapseState ?? "", 10);
      const currentState = imageSequenceStates.get(control.dataset.imageSequence);
      const nextState = Number.isInteger(collapseState) && currentState === requestedState
        ? collapseState
        : requestedState;
      setGuideImageState(control.dataset.imageSequence, nextState);
    } else {
      cycleGuideImage(control.dataset.imageSequence);
    }
    pinnedId = null;
    renderActiveControl();
    return;
  }

  if (control.dataset.imageResetScope) {
    restoreGuideImage(control.dataset.imageResetScope);
  }

  pinnedId = null;
  renderActiveControl();
}

for (const control of controls) {
  control.addEventListener("pointerenter", () => preview(control));
  control.addEventListener("pointerleave", () => stopPreview(control));
  control.addEventListener("focus", () => preview(control));
  control.addEventListener("blur", () => stopPreview(control));
  control.addEventListener("click", () => activateControl(control));
}

for (const highlight of highlights) {
  const id = highlight.dataset.highlight;

  highlight.addEventListener("pointerenter", () => {
    previewHotspot = highlight;
    previewId = id;
    renderActiveControl();
    controlsById.get(id)?.scrollIntoView({ block: "nearest" });
  });
  highlight.addEventListener("pointerleave", () => {
    if (previewHotspot === highlight) {
      previewHotspot = null;
      previewId = null;
      renderActiveControl();
    }
  });
  highlight.addEventListener("click", () => {
    if (highlight.dataset.tabTarget) {
      const targetTab = document.getElementById?.(highlight.dataset.tabTarget);
      if (targetTab) {
        activateTab(targetTab);
      }
      return;
    }
    if (highlight.dataset.mainRunHotspotTarget) {
      showMainRunPanel(
        highlight.dataset.mainRunHotspotTarget,
        true,
        controlsById.get(id),
      );
      return;
    }
    if (highlight.dataset.sequenceControl) {
      activateSequenceHotspot(highlight);
      return;
    }

    const control = controlsById.get(id);
    if (control) {
      activateControl(control);
    }
  });
}

for (const backButton of plateDetailBackButtons) {
  backButton.addEventListener("click", () => closePlateDetail());
}
for (const backButton of tessellatorDetailBackButtons) {
  backButton.addEventListener("click", () => closeTessellatorDetail());
}
mainThemeBack?.addEventListener("click", () => closeMainThemeDetail());
mainRunBack?.addEventListener("click", () => closeMainRunDetail());
for (const backButton of mainRunPanelBackButtons) {
  backButton.addEventListener("click", () => {
    returnToPreviousMainRunPanel(backButton.dataset.mainRunPanelBack);
  });
}
for (const trigger of installationDetailTriggers) {
  trigger.addEventListener("click", () => {
    openInstallationDetail(trigger.dataset.installationDetailTrigger);
  });
}
for (const backControl of installationDetailBackControls) {
  backControl.addEventListener("click", () => closeInstallationDetail());
}
installationCatalogCopy?.addEventListener("click", copyInstallationCatalogUrl);
for (const control of installationCatalogStateControls) {
  control.addEventListener("click", () => {
    showInstallationCatalogState(control.dataset.installationCatalogState);
  });
}
for (const control of installationShapexStateControls) {
  control.addEventListener("click", () => {
    showInstallationShapexState(control.dataset.installationShapexState);
  });
}
for (const trigger of instrumentGuideTriggers) {
  trigger.setAttribute("aria-expanded", "false");
  trigger.addEventListener("click", () => openInstrumentGuide(trigger));
}
for (const point of calibrationGuidePoints) {
  point.addEventListener("click", () => {
    activeCalibrationGuidePoint = point.dataset.calibrationGuidePoint;
    showCalibrationGuideState(activeCalibrationGuidePoint, true);
  });
}
calibrationGuideOverview?.addEventListener("click", () => {
  showCalibrationGuideState("overview", true);
});
for (const closeControl of instrumentGuideCloseControls) {
  closeControl.addEventListener("click", () => closeInstrumentGuide());
}
instrumentGuideModal?.addEventListener("keydown", (event) => {
  if (event.key !== "Tab") {
    return;
  }

  const focusableElements = [
    ...instrumentGuideModal.querySelectorAll("a[href], button:not([disabled])"),
  ].filter((element) => element.offsetParent !== null);
  if (focusableElements.length === 0) {
    return;
  }

  const firstElement = focusableElements[0];
  const lastElement = focusableElements.at(-1);
  if (event.shiftKey && document.activeElement === firstElement) {
    event.preventDefault();
    lastElement.focus();
  } else if (!event.shiftKey && document.activeElement === lastElement) {
    event.preventDefault();
    firstElement.focus();
  }
});

document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") {
    return;
  }

  if (instrumentGuideModal && !instrumentGuideModal.hidden) {
    closeInstrumentGuide();
    return;
  }

  clearActiveControl();
});

const tabs = [...document.querySelectorAll('[role="tab"]')];
const tabPanels = [...document.querySelectorAll('[role="tabpanel"]')];
const interactiveGuide = document.getElementById?.("interactive-guide");

function activateTab(tab, moveFocus = true) {
  const panelId = tab.getAttribute("aria-controls");

  for (const candidate of tabs) {
    const isSelected = candidate === tab;
    candidate.setAttribute("aria-selected", String(isSelected));
    candidate.tabIndex = isSelected ? 0 : -1;
  }

  for (const panel of tabPanels) {
    panel.hidden = panel.id !== panelId;
  }

  closePlateDetail(false);
  closeTessellatorDetail(false);
  closeMainThemeDetail(false);
  closeMainRunDetail(false);
  closeInstallationDetail(false);
  closeInstrumentGuide(false);
  clearActiveControl();

  if (panelId === "panel-outputs") {
    restoreGuideImage("outputs");
    setDetailHotspotScope("outputs");
  }

  if (moveFocus) {
    tab.focus({ preventScroll: true });
  }

  interactiveGuide?.scrollIntoView({ block: "start", inline: "nearest" });
}

for (const [index, tab] of tabs.entries()) {
  tab.addEventListener("click", () => activateTab(tab, false));
  tab.addEventListener("keydown", (event) => {
    let nextIndex;

    if (event.key === "ArrowRight") {
      nextIndex = (index + 1) % tabs.length;
    } else if (event.key === "ArrowLeft") {
      nextIndex = (index - 1 + tabs.length) % tabs.length;
    } else if (event.key === "Home") {
      nextIndex = 0;
    } else if (event.key === "End") {
      nextIndex = tabs.length - 1;
    } else {
      return;
    }

    event.preventDefault();
    activateTab(tabs[nextIndex]);
  });
}

for (const tabLink of document.querySelectorAll(".workflow-tab-link[data-tab-target]")) {
  tabLink.addEventListener("click", (event) => {
    const targetTab = document.getElementById?.(tabLink.dataset.tabTarget);
    if (!targetTab) {
      return;
    }

    event.preventDefault();
    activateTab(targetTab, false);
    if (tabLink.hasAttribute("data-open-main-run-detail")) {
      openMainRunDetail();
    }
  });
}
