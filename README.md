<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/headers/shapex-header-dark.svg">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/headers/shapex-header-light.svg">
    <img alt="QuPath ShapeX extension" src="docs/assets/headers/shapex-header-light.svg" width="500">
  </picture>
</h1>

<img align="right" width="25%" alt="ShapeX logo" src="docs/assets/logo/shapex-logo-transparent.svg"/>

[![QuPath](https://img.shields.io/badge/QuPath-0.7.0-1e90ff)](https://qupath.github.io/)
[![scverse - anndata](https://img.shields.io/badge/scverse-anndata-8A2BE2)](https://anndata.scverse.org/en/stable/)

**Welcome to ShapeX, a [QuPath](https://qupath.github.io/) extension for turning image annotations into spatial sampling workflows.**

ShapeX helps you turn areas marked in [QuPath](https://qupath.github.io/) into an organized sampling plan. In one place, you can choose which areas to collect, assign each sample to a well, review the plate layout, and create the files needed for downstream sample preparation.

We initially developed ShapeX for laser microdissection workflows, in which marked image regions are cut and collected into wells. However, the Plate Designer is fully customizable, making ShapeX useful whenever samples need to be assigned to well plates.

We strongly support the [scverse](https://scverse.org/) community and its work to make scientific data easier to share and use across different tools. ShapeX therefore lets you save [QuPath](https://qupath.github.io/) shapes, sample information, and well locations in the [anndata](https://anndata.scverse.org/en/stable/) format for further analysis.

## How can ShapeX help me?

- **Design sampling plates** using standard 96-, 384-, or 1,536-well plate formats, or create custom layouts.
- **Assign annotations to wells** individually, pool annotations from the same class, or assign each slide to its own plate. You can also randomize the well assignments, add negative-control wells, and define exclusion zones for wells that should remain empty.
- **Tessellate annotations** into triangular, square, or hexagonal sampling regions, enabling complete spatial reconstruction at a selected resolution.
- **Export spatial sampling data** for use with laser capture microdissection and liquid dispensing systems, create comprehensive plate overviews, and save annotation geometry, sample metadata, and well locations as [anndata](https://anndata.scverse.org/en/stable/) (`.h5ad`) files.

## Installation

> [!NOTE]
> A complete guide to installing and using ShapeX is available in the [interactive ShapeX guide](https://schoof-lab.github.io/ShapeX/).

> [!IMPORTANT]
> ShapeX requires **QuPath 0.7.0 or later**.

### Step-by-step

1. Open **QuPath**.
2. Select **Extensions → Manage extensions**.
3. Click **Manage extension catalogs**.
4. In the **Catalog URL** text field, paste `https://github.com/Schoof-Lab/qupath-cdl-catalog`, then click **Add**.
5. Locate ShapeX in the newly added catalog, click the green **+**, and then click **Install**.

## Questions, feedback, and feature requests

Do you have a burning question, want to report a bug, or have a suggestion for an amazing feature we should add? Then please [open a GitHub issue](https://github.com/Schoof-Lab/ShapeX/issues). Before opening a new issue, search the existing issues to see whether the topic has already been discussed.

When opening an issue, provide a clear title and include the information relevant to your request:

- For a question, describe what you are trying to achieve and where you need help.
- For a bug report, include the versions of QuPath and ShapeX, your operating system, the steps required to reproduce the problem, and what you expected to happen.
- For a feature request or a request for system support, describe the intended workflow, the relevant system, and any file-format requirements or documentation that can be shared.

Currently, ShapeX can generate files only for Leica LMD systems and the i.DOT dispensing system. We would love to support additional systems, but doing so will require help from the community. If you can share file-format specifications, example files, or workflow documentation for another system, please submit a request for system support.

## How to cite

If you like ShapeX, please feel free to cite the preprint and leave a ⭐️.

> Daucke, R., Nielsen, M. W., Jacobsen, J. B., Tkach, V. V., Hammouda, N. M., Lund, E. L., Areškevičiūtė, A., Kristensen, B. W., Petrosius, V., & Schoof, E. M. (2026). *A one-pot workflow for spatial proteomics aided by ShapeX*. bioRxiv. https://www.biorxiv.org/content/10.64898/2026.11.24.768900v1

## Acknowledgments

ShapeX is built as an extension for [QuPath](https://qupath.github.io/), the open-source platform for bioimage analysis.

> Bankhead, P., Loughrey, M. B., Fernández, J. A., Dombrowski, Y., McArt, D. G., Dunne, P. D., McQuaid, S., Gray, R. T., Murray, L., Coleman, H. G., James, J. A., Salto‐Tellez, M., & Hamilton, P. W. (2017). *QuPath: Open source software for digital pathology image analysis.* [https://doi.org/10.1101/099796](https://doi.org/10.1101/099796)

ShapeX supports exporting spatial data to the [anndata](https://anndata.scverse.org/en/stable/) format.

> Virshup, I., Rybakov, S., Theis, F. J., Angerer, P., & Wolf, F. A. (2024). *anndata: Access and store annotated datamatrices.* Journal of Open Source Software, 9(101), 4371–4371. [https://doi.org/10.21105/joss.04371](https://doi.org/10.21105/joss.04371)


> [!NOTE]
> **This project was developed with the assistance of AI.**
