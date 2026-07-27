package qupath.ext.shapex.logo;

import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generates the three transparent ShapeX SVG logo variants.
 *
 * <p>The expensive geometry model is built once and reused for every output variant.</p>
 */
public final class ShapeXLogo {

    private static final int VIEWBOX_SIZE = 512;

    // Canvas. The negative top expands the view upward for the escaping tail.
    private static final double VIEWBOX_LEFT = 0.0;
    private static final double VIEWBOX_TOP = -180.0;
    private static final double VIEWBOX_WIDTH = 640.0;
    // Extra bottom space keeps the annotation's outer glow from being clipped.
    private static final double VIEWBOX_HEIGHT = 712.0;
    private static final double WORDMARK_VIEWBOX_HEIGHT = 860.0;

    // Main annotation silhouette size.
    private static final double ANNOTATION_HEIGHT_RATIO = 0.94;
    private static final double ANNOTATION_WIDTH_TO_HEIGHT = 1.05;

    // Main tessellation hexagons. Increase MAIN_HEX_SIDE for larger cells.
    // MAIN_HEX_GAP preserves visible spacing between neighboring cells.
    private static final double MAIN_HEX_SIDE = 42.0;
    private static final double MAIN_HEX_GAP = 3.0;

    // Subtle upward/outward spacing warp near the opening.
    private static final double SPACING_PUSH_X = 22.0;
    private static final double SPACING_PUSH_Y = 15.0;
    private static final double SEPARATED_NUDGE_X = 8.0;
    private static final double SEPARATED_NUDGE_Y = 6.0;
    private static final double SPACING_FADE_DISTANCE = 170.0;

    // Diagonal cut/opening rectangle.
    private static final double CUT_X = -200.0;
    private static final double CUT_Y = -210.0;
    private static final double CUT_WIDTH = 1000.0;
    private static final double CUT_HEIGHT = 420.0;
    private static final double CUT_OVERLAP = 3.0;
    private static final double CUT_TRANSLATE_X = 25.0;
    private static final double CUT_TRANSLATE_Y = -78.0;
    private static final double CUT_ROTATION_DEGREES = 10.0;
    private static final double CUT_ROTATION_CENTER_X = 256.0;
    private static final double CUT_ROTATION_CENTER_Y = 256.0;
    private static final double CUT_ROTATION_RADIANS = Math.toRadians(CUT_ROTATION_DEGREES);
    private static final double CUT_ROTATION_COS = Math.cos(CUT_ROTATION_RADIANS);
    private static final double CUT_ROTATION_SIN = Math.sin(CUT_ROTATION_RADIANS);

    // Virtual boundary for where hexagons start separating and become unclipped.
    // Positive values move this boundary down from the dashed outline clip.
    private static final double HEX_SEPARATION_EXTRA_DEPTH = 65.0;
    private static final LineSegment HEX_SEPARATION_LINE = createHexSeparationLine();

    // Main grid loop bounds. Expand these if larger hexes leave empty edges.
    private static final int GRID_MIN_X = -2;
    private static final int GRID_MAX_X = 12;
    private static final int GRID_MIN_Y = -2;
    private static final int GRID_MAX_Y = 10;

    // Main tessellation styling.
    private static final String MAIN_HEX_FILL = "#E87706";
    private static final double MAIN_HEX_FILL_OPACITY = 0.88;
    private static final String MAIN_HEX_STROKE = "#F09301";
    private static final double MAIN_HEX_STROKE_WIDTH = 2.5;

    // Escaping tail styling.
    private static final String TAIL_HEX_STROKE = "#ffc331";
    private static final double TAIL_HEX_STROKE_WIDTH = 2.1;

    // White dashed outline styling.
    private static final String OUTLINE_COLOR = "#fff4e8";
    private static final double OUTLINE_WIDTH = 4.3;
    private static final double OUTLINE_DASH_LENGTH = 16.0;
    private static final double OUTLINE_GAP_LENGTH = 14.0;

    // Glow and shading.
    private static final double SHAPE_GLOW_STROKE_WIDTH = 18.0;
    private static final double SHAPE_GLOW_OPACITY = 0.35;

    // Generated files.
    private static final List<OutputVariant> OUTPUT_VARIANTS = List.of(
            new OutputVariant("shapex-logo-transparent.svg", Wordmark.NONE),
            new OutputVariant("shapex-logo-with-text-transparent.svg", Wordmark.WHITE),
            new OutputVariant("shapex-logo-with-text-transparent-black.svg", Wordmark.BLACK)
    );
    private static final Path OUTPUT_DIRECTORY = Path.of("docs/assets/logo");

    // Ordered visual elements. List order is SVG paint order.
    private static final List<Trail> TRAILS = List.of(
            new Trail(50, -22, 312, 2.2, 0.92),
            new Trail(92, -58, 236, 2.0, 0.82),
            new Trail(150, -34, 214, 1.9, 0.72),
            new Trail(220, -104, 204, 1.8, 0.68),
            new Trail(278, -152, 236, 1.7, 0.62),
            new Trail(324, -176, 184, 2.0, 0.66),
            new Trail(405, -166, 104, 1.8, 0.56)
    );

    private static final List<HotHexagon> HOT_HEXAGONS = List.of(
            new HotHexagon(350, 40, 29, 0.96),
            new HotHexagon(500, 34, 29, 0.96),
            new HotHexagon(433, -14, 25, 0.95),
            new HotHexagon(510, -40, 23, 0.92),
            new HotHexagon(450, -90, 21, 0.92),
            new HotHexagon(510, -140, 20, 0.86)
    );

    private static final List<ParticleSquare> PARTICLE_SQUARES = List.of(
            new ParticleSquare(452, -151, 7, 0.72),
            new ParticleSquare(557, -128, 8, 0.72),
            new ParticleSquare(410, -116, 6, 0.70),
            new ParticleSquare(544, -28, 7, 0.82),
            new ParticleSquare(360, 19, 11, 0.95),
            new ParticleSquare(586, 54, 8, 0.84),
            new ParticleSquare(320, 46, 9, 0.84),
            new ParticleSquare(428, 80, 10, 0.88),
            new ParticleSquare(261, 120, 11, 0.84),
            new ParticleSquare(416, 165, 8, 0.70),
            new ParticleSquare(398, 236, 8, 0.74)
    );

    private static final List<ParticleDot> PARTICLE_DOTS = List.of(
            new ParticleDot(444, -156, 2.2, 0.64),
            new ParticleDot(557, -108, 2.4, 0.66),
            new ParticleDot(350, -74, 2.0, 0.68),
            new ParticleDot(394, -53, 2.4, 0.66),
            new ParticleDot(381, 60, 2.8, 0.72),
            new ParticleDot(322, 95, 3.0, 0.68),
            new ParticleDot(335, 207, 3.3, 0.86),
            new ParticleDot(246, 263, 4.3, 0.76)
    );

    private static final List<CubicCurve> ANNOTATION_CURVES = List.of(
            new CubicCurve(0.04, 0.43, 0.14, 0.27, 0.29, 0.24),
            new CubicCurve(0.41, 0.22, 0.48, 0.27, 0.55, 0.18),
            new CubicCurve(0.61, 0.10, 0.69, 0.05, 0.80, 0.08),
            new CubicCurve(0.93, 0.11, 1.00, 0.23, 0.97, 0.36),
            new CubicCurve(0.95, 0.48, 0.89, 0.57, 0.83, 0.63),
            new CubicCurve(0.77, 0.71, 0.74, 0.78, 0.76, 0.86),
            new CubicCurve(0.78, 0.94, 0.70, 0.99, 0.60, 0.98),
            new CubicCurve(0.46, 0.97, 0.31, 0.98, 0.21, 0.90),
            new CubicCurve(0.11, 0.82, 0.06, 0.70, 0.08, 0.57)
    );

    private ShapeXLogo() {
    }

    // Generation model and small value types.
    private record PolyPoint(double x, double y, double distance) {
    }

    private record Point(double x, double y) {
    }

    private record OutlineDash(Path2D path, Area area) {
    }

    private record OutputVariant(String fileName, Wordmark wordmark) {
    }

    private enum Wordmark {
        NONE(null),
        WHITE("#ffffff"),
        BLACK("#000000");

        private final String color;

        Wordmark(String color) {
            this.color = color;
        }

        private boolean isPresent() {
            return color != null;
        }

        private String color() {
            return color;
        }
    }

    private enum GridLayer {
        TEXTURED_FILL,
        STROKE,
        MASK_FILL,
        MASK_STROKE
    }

    private record Trail(double x, double y1, double y2, double width, double opacity) {
    }

    private record HotHexagon(double centerX, double centerY, double sideLength, double opacity) {
    }

    private record ParticleSquare(double x, double y, double size, double opacity) {
    }

    private record ParticleDot(double centerX, double centerY, double radius, double opacity) {
    }

    private record CubicCurve(double x1, double y1, double x2, double y2, double x, double y) {
    }

    private record AnnotationBounds(double centerX, double centerY, double left, double top, double width, double height) {
    }

    private record GridTile(String polygonPoints, String clippedPath) {
        private boolean isClipped() {
            return clippedPath != null;
        }
    }

    private record LogoGeometry(String annotationPath, List<GridTile> gridTiles, List<String> outlineDashPaths) {
    }

    private record LineSegment(double ax, double ay, double bx, double by, double length) {
        private double signedDistanceTo(double x, double y) {
            double crossProduct = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
            return length == 0.0 ? 0.0 : crossProduct / length;
        }
    }

    public static void main(String[] ignored) throws IOException {
        LogoGeometry geometry = createLogoGeometry();
        Files.createDirectories(OUTPUT_DIRECTORY);
        for (OutputVariant variant : OUTPUT_VARIANTS) {
            writeSvg(
                    OUTPUT_DIRECTORY.resolve(variant.fileName()),
                    createSvg(geometry, variant.wordmark())
            );
        }
    }

    private static void writeSvg(Path output, String svg) throws IOException {
        Files.writeString(output, svg, StandardCharsets.UTF_8);
    }

    private static String createSvg(LogoGeometry geometry, Wordmark wordmark) {
        double viewBoxHeight = wordmark.isPresent() ? WORDMARK_VIEWBOX_HEIGHT : VIEWBOX_HEIGHT;
        StringBuilder svg = new StringBuilder(wordmark.isPresent() ? 62_000 : 56_000);

        line(svg, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        line(svg, format(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"%s %s %s %s\" width=\"%s\" height=\"%s\" role=\"img\" aria-labelledby=\"title desc\">",
                number(VIEWBOX_LEFT), number(VIEWBOX_TOP), number(VIEWBOX_WIDTH), number(viewBoxHeight),
                number(VIEWBOX_WIDTH), number(viewBoxHeight)
        ));
        line(svg, "  <title id=\"title\">ShapeX hexagonal tessellation logo</title>");
        line(svg, "  <desc id=\"desc\">An annotation silhouette tessellated with orange hexagons, with glowing hexagonal samples escaping upward.</desc>");

        appendDefs(svg, viewBoxHeight);
        appendAnnotation(svg, geometry, viewBoxHeight);
        appendHexTail(svg);
        appendParticles(svg);
        if (wordmark.isPresent()) {
            appendWordmark(svg, wordmark.color());
        }

        line(svg, "</svg>");
        return svg.toString();
    }

    private static void appendDefs(StringBuilder svg, double viewBoxHeight) {
        line(svg, "  <defs>");

        // Color gradients.
        line(svg, "    <linearGradient id=\"trailNeedleGradient\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">");
        line(svg, "      <stop offset=\"0%\" stop-color=\"#ff7a00\" stop-opacity=\"0\"/>");
        line(svg, "      <stop offset=\"24%\" stop-color=\"#ff7a00\" stop-opacity=\"0.05\"/>");
        line(svg, "      <stop offset=\"48%\" stop-color=\"#ff7a00\" stop-opacity=\"0.22\"/>");
        line(svg, "      <stop offset=\"72%\" stop-color=\"#ff8a00\" stop-opacity=\"0.56\"/>");
        line(svg, "      <stop offset=\"94%\" stop-color=\"#ffb22a\" stop-opacity=\"0.92\"/>");
        line(svg, "      <stop offset=\"100%\" stop-color=\"#ff6a00\" stop-opacity=\"0.12\"/>");
        line(svg, "    </linearGradient>");

        line(svg, "    <linearGradient id=\"trailCoreGradient\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">");
        line(svg, "      <stop offset=\"0%\" stop-color=\"#ffb02e\" stop-opacity=\"0\"/>");
        line(svg, "      <stop offset=\"34%\" stop-color=\"#ff8a00\" stop-opacity=\"0.05\"/>");
        line(svg, "      <stop offset=\"64%\" stop-color=\"#ff8a00\" stop-opacity=\"0.34\"/>");
        line(svg, "      <stop offset=\"90%\" stop-color=\"#ffc44d\" stop-opacity=\"0.74\"/>");
        line(svg, "      <stop offset=\"100%\" stop-color=\"#ff7900\" stop-opacity=\"0\"/>");
        line(svg, "    </linearGradient>");

        line(svg, "    <linearGradient id=\"hotHexGradient\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">");
        line(svg, "      <stop offset=\"0%\" stop-color=\"#ffa411\"/>");
        line(svg, "      <stop offset=\"100%\" stop-color=\"#e86c00\"/>");
        line(svg, "    </linearGradient>");

        line(svg, "    <linearGradient id=\"wordmarkOrangeGradient\" x1=\"0\" y1=\"540\" x2=\"0\" y2=\"710\" gradientUnits=\"userSpaceOnUse\">");
        line(svg, "      <stop offset=\"0%\" stop-color=\"#ffa20a\"/>");
        line(svg, "      <stop offset=\"54%\" stop-color=\"#ff8700\"/>");
        line(svg, "      <stop offset=\"100%\" stop-color=\"#df6100\"/>");
        line(svg, "    </linearGradient>");

        // Hard and softened masks around the diagonal cut.
        line(svg, "    <clipPath id=\"cutAbove\">");
        appendCutRect(svg, "      ", 0.0);
        line(svg, "    </clipPath>");

        line(svg, "    <mask id=\"belowCutMask\">");
        appendCanvasRect(svg, "      ", "white", viewBoxHeight);
        line(svg, "      <g clip-path=\"url(#cutAbove)\">");
        appendCanvasRect(svg, "        ", "black", viewBoxHeight);
        line(svg, "      </g>");
        line(svg, "    </mask>");

        line(svg, "    <filter id=\"softCutMaskBlur\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\">");
        line(svg, "      <feGaussianBlur stdDeviation=\"9\"/>");
        line(svg, "    </filter>");

        line(svg, "    <mask id=\"softBelowCutMask\">");
        appendCanvasRect(svg, "      ", "white", viewBoxHeight);
        line(svg, "      <g fill=\"black\" filter=\"url(#softCutMaskBlur)\">");
        appendCutRect(svg, "        ", 0.0);
        line(svg, "      </g>");
        line(svg, "    </mask>");

        // Main-grid shading.
        line(svg, "    <linearGradient id=\"hexDarkFadeGradient\" x1=\"315\" y1=\"540\" x2=\"190\" y2=\"-20\" gradientUnits=\"userSpaceOnUse\">");
        line(svg, "      <stop offset=\"0%\" stop-color=\"#000000\" stop-opacity=\"98\"/>");
        line(svg, "      <stop offset=\"9%\" stop-color=\"#000000\" stop-opacity=\"0.95\"/>");
        line(svg, "      <stop offset=\"18%\" stop-color=\"#000000\" stop-opacity=\"0.92\"/>");
        line(svg, "      <stop offset=\"34%\" stop-color=\"#000000\" stop-opacity=\"0.90\"/>");
        line(svg, "      <stop offset=\"50%\" stop-color=\"#000000\" stop-opacity=\"0.48\"/>");
        line(svg, "      <stop offset=\"64%\" stop-color=\"#000000\" stop-opacity=\"0.24\"/>");
        line(svg, "      <stop offset=\"78%\" stop-color=\"#000000\" stop-opacity=\"0.08\"/>");
        line(svg, "      <stop offset=\"100%\" stop-color=\"#000000\" stop-opacity=\"0\"/>");
        line(svg, "    </linearGradient>");

        // Glow, texture, and outline filters.
        line(svg, "    <filter id=\"shapeOuterGlow\" x=\"-80%\" y=\"-80%\" width=\"260%\" height=\"260%\">");
        line(svg, "      <feGaussianBlur stdDeviation=\"14\" result=\"blur\"/>");
        line(svg, "    </filter>");

        line(svg, "    <filter id=\"trailGlow\" x=\"-220%\" y=\"-10%\" width=\"540%\" height=\"125%\">");
        line(svg, "      <feGaussianBlur stdDeviation=\"3\" result=\"blur\"/>");
        line(svg, "      <feMerge>");
        line(svg, "        <feMergeNode in=\"blur\"/>");
        line(svg, "        <feMergeNode in=\"SourceGraphic\"/>");
        line(svg, "      </feMerge>");
        line(svg, "    </filter>");

        line(svg, "    <filter id=\"hexNoise\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\">");
        line(svg, "      <feTurbulence type=\"fractalNoise\" baseFrequency=\"0.06\" numOctaves=\"2\" seed=\"3\" result=\"noise\"/>");
        line(svg, "      <feGaussianBlur stdDeviation=\"6\" result=\"soft\"/>");
        line(svg, "      <feDiffuseLighting in=\"soft\" lighting-color=\"#ED7E02\" surfaceScale=\"35\" result=\"light\">");
        line(svg, "        <feDistantLight azimuth=\"235\" elevation=\"60\"/>");
        line(svg, "      </feDiffuseLighting>");
        line(svg, "      <feComposite in=\"light\" in2=\"SourceAlpha\" operator=\"in\" result=\"lightClipped\"/>");
        line(svg, "      <feComposite in=\"SourceGraphic\" in2=\"lightClipped\" operator=\"arithmetic\" k1=\"0\" k2=\"1\" k3=\"0.2\" k4=\"0\"/>");
        line(svg, "    </filter>");

        line(svg, "    <filter id=\"hexGlow\" x=\"-90%\" y=\"-90%\" width=\"280%\" height=\"280%\">");
        line(svg, "      <feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"6\" flood-color=\"#ff9900\" flood-opacity=\"0.62\"/>");
        line(svg, "      <feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"16\" flood-color=\"#f26a00\" flood-opacity=\"0.26\"/>");
        line(svg, "    </filter>");

        line(svg, "    <filter id=\"whiteDashGlow\" x=\"-35%\" y=\"-35%\" width=\"170%\" height=\"170%\">");
        line(svg, "      <feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"2.4\" flood-color=\"#ffffff\" flood-opacity=\"0.38\"/>");
        line(svg, "    </filter>");

        line(svg, "  </defs>");
    }

    private static void appendCanvasRect(StringBuilder svg, String indent, String fill, double viewBoxHeight) {
        line(svg, format(
                "%s<rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\" fill=\"%s\"/>",
                indent,
                number(VIEWBOX_LEFT),
                number(VIEWBOX_TOP),
                number(VIEWBOX_WIDTH),
                number(viewBoxHeight),
                fill
        ));
    }

    private static void appendTrails(StringBuilder svg) {
        line(svg, "  <g filter=\"url(#trailGlow)\">");
        for (Trail trail : TRAILS) {
            appendTrail(svg, trail);
        }
        line(svg, "  </g>");
    }

    private static LogoGeometry createLogoGeometry() {
        AnnotationBounds bounds = annotationBounds(VIEWBOX_SIZE);
        Path2D annotationShape = createAnnotationShape(bounds);
        Area annotationArea = new Area(annotationShape);
        List<OutlineDash> visibleOutlineDashes = visibleOutlineDashes(annotationShape, createCutArea(0.0));
        Area visibleOutlineArea = createOutlineDashArea(visibleOutlineDashes);
        Area hexSeparationArea = createHexSeparationArea(CUT_OVERLAP);

        List<GridTile> gridTiles = createGridTiles(
                bounds,
                annotationArea,
                visibleOutlineArea,
                hexSeparationArea
        );
        List<String> outlineDashPaths = visibleOutlineDashes.stream()
                .map(OutlineDash::path)
                .map(ShapeXLogo::pathToSvgPath)
                .toList();

        return new LogoGeometry(annotationPath(bounds), gridTiles, outlineDashPaths);
    }

    private static void appendAnnotation(StringBuilder svg, LogoGeometry geometry, double viewBoxHeight) {

        line(svg, "  <g mask=\"url(#softBelowCutMask)\">");
        line(svg, format(
                "    <path d=\"%s\" fill=\"none\" stroke=\"#ff6a00\" stroke-width=\"%s\" opacity=\"%s\" filter=\"url(#shapeOuterGlow)\"/>",
                geometry.annotationPath(),
                number(SHAPE_GLOW_STROKE_WIDTH),
                number(SHAPE_GLOW_OPACITY)
        ));
        line(svg, "  </g>");

        appendTrails(svg);

        line(svg, "  <g>");
        appendGridLayer(svg, geometry.gridTiles(), GridLayer.TEXTURED_FILL);
        appendGridLayer(svg, geometry.gridTiles(), GridLayer.STROKE);
        line(svg, "  </g>");

        // Mask for shading ALL main grid hexagons, including those escaping outside the annotation.
        // This does not affect the white dashed outline, particles, or the single particle-hexagon.
        line(svg, "  <defs>");
        line(svg, format(
                "    <mask id=\"hexShadeMask\" maskUnits=\"userSpaceOnUse\" x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\">",
                number(VIEWBOX_LEFT),
                number(VIEWBOX_TOP),
                number(VIEWBOX_WIDTH),
                number(viewBoxHeight)
        ));
        appendCanvasRect(svg, "      ", "black", viewBoxHeight);
        line(svg, "      <g>");
        appendGridLayer(svg, geometry.gridTiles(), GridLayer.MASK_FILL);
        appendGridLayer(svg, geometry.gridTiles(), GridLayer.MASK_STROKE);
        line(svg, "      </g>");
        line(svg, "    </mask>");
        line(svg, "  </defs>");

        line(svg, format(
                "  <rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\" fill=\"url(#hexDarkFadeGradient)\" mask=\"url(#hexShadeMask)\" opacity=\"0.96\"/>",
                number(VIEWBOX_LEFT),
                number(VIEWBOX_TOP),
                number(VIEWBOX_WIDTH),
                number(viewBoxHeight)
        ));

        line(svg, format(
                "  <g mask=\"url(#belowCutMask)\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\" stroke-linejoin=\"round\" filter=\"url(#whiteDashGlow)\">",
                OUTLINE_COLOR,
                number(OUTLINE_WIDTH)
        ));
        appendVisibleOutlineDashes(svg, geometry.outlineDashPaths());
        line(svg, "  </g>");
    }

    private static List<GridTile> createGridTiles(
            AnnotationBounds bounds,
            Area annotationArea,
            Area visibleOutlineArea,
            Area hexSeparationArea
    ) {
        List<GridTile> tiles = new ArrayList<>();
        double gridSide = MAIN_HEX_SIDE + MAIN_HEX_GAP;
        double dx = 1.5 * gridSide;
        double dy = Math.sqrt(3.0) * gridSide;

        for (int ix = GRID_MIN_X; ix <= GRID_MAX_X; ix++) {
            double baseX = bounds.left() + ix * dx;
            double yOffset = (ix % 2 == 0) ? 0.0 : dy / 2.0;

            for (int iy = GRID_MIN_Y; iy <= GRID_MAX_Y; iy++) {
                if (isRemovedFlyingHex(ix, iy)) {
                    continue;
                }

                double baseY = bounds.top() + iy * dy + yOffset;

                double progress = spacingProgress(baseX, baseY);
                double eased = smoothStep(0.0, 1.0, progress);

                double lateral = clamp01((baseX - bounds.centerX()) / (bounds.width() * 0.5));
                double signedLateral = lateral * 2.0 - 1.0;

                double centerX = baseX
                        + SPACING_PUSH_X * eased * signedLateral
                        + SEPARATED_NUDGE_X * eased;
                double centerY = baseY
                        - SPACING_PUSH_Y * eased
                        - SEPARATED_NUDGE_Y * eased;

                String polygonPoints = regularPolygonPoints(centerX, centerY, MAIN_HEX_SIDE, 6, 0.0);
                Path2D hexPath = polygonPathFromSerializedPoints(polygonPoints);
                Area hexArea = new Area(hexPath);

                if (!areaIntersects(hexArea, annotationArea)) {
                    continue;
                }

                boolean touchesVisibleOutline = areaIntersects(hexArea, visibleOutlineArea);

                Area outsideCutInsideAnnotation = new Area(hexArea);
                outsideCutInsideAnnotation.intersect(annotationArea);

                Area cutPart = new Area(hexArea);
                cutPart.intersect(hexSeparationArea);

                Area normallyVisible = new Area();
                normallyVisible.add(outsideCutInsideAnnotation);
                normallyVisible.add(cutPart);

                boolean fullyDrawnAfterCut = containsAll(normallyVisible, hexArea);

                if (fullyDrawnAfterCut && !touchesVisibleOutline) {
                    tiles.add(new GridTile(polygonPoints, null));
                } else {
                    Area clippedToAnnotation = new Area(hexArea);
                    clippedToAnnotation.intersect(annotationArea);
                    if (!clippedToAnnotation.isEmpty()) {
                        tiles.add(new GridTile(null, areaToSvgPath(clippedToAnnotation)));
                    }
                }
            }
        }

        return List.copyOf(tiles);
    }

    private static void appendGridLayer(StringBuilder svg, List<GridTile> tiles, GridLayer layer) {
        String attributes = gridLayerAttributes(layer);
        for (GridTile tile : tiles) {
            if (tile.isClipped()) {
                line(svg, format("      <path d=\"%s\"%s/>", tile.clippedPath(), attributes));
            } else {
                line(svg, format("      <polygon points=\"%s\"%s/>", tile.polygonPoints(), attributes));
            }
        }
    }

    private static String gridLayerAttributes(GridLayer layer) {
        return switch (layer) {
            case TEXTURED_FILL -> format(
                    " fill=\"%s\" fill-opacity=\"%s\" filter=\"url(#hexNoise)\"",
                    MAIN_HEX_FILL,
                    number(MAIN_HEX_FILL_OPACITY)
            );
            case STROKE -> format(
                    " fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\"",
                    MAIN_HEX_STROKE,
                    number(MAIN_HEX_STROKE_WIDTH)
            );
            case MASK_FILL -> " fill=\"white\" fill-opacity=\"1\"";
            case MASK_STROKE -> format(
                    " fill=\"none\" stroke=\"white\" stroke-width=\"%s\" stroke-linejoin=\"round\"",
                    number(MAIN_HEX_STROKE_WIDTH)
            );
        };
    }

    private static void appendHexTail(StringBuilder svg) {
        line(svg, format(
                "  <g filter=\"url(#hexGlow)\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\">",
                TAIL_HEX_STROKE,
                number(TAIL_HEX_STROKE_WIDTH)
        ));

        for (HotHexagon hexagon : HOT_HEXAGONS) {
            appendHotHexagon(svg, hexagon);
        }

        line(svg, "  </g>");
    }

    private static boolean isRemovedFlyingHex(int ix, int iy) {
        return (ix == 4 && iy == 1)
                || (ix == 5 && iy == 0)
                || (ix == 8 && iy == 3);
    }

    private static void appendVisibleOutlineDashes(StringBuilder svg, List<String> dashPaths) {
        for (String dashPath : dashPaths) {
            line(svg, format("    <path d=\"%s\"/>", dashPath));
        }
    }

    private static List<OutlineDash> visibleOutlineDashes(Path2D outlineShape, Area cutAboveArea) {
        List<OutlineDash> dashes = new ArrayList<>();
        List<PolyPoint> points = flattenPath(outlineShape, 0.35);
        if (points.size() < 2) {
            return dashes;
        }

        double totalLength = points.get(points.size() - 1).distance();
        double patternLength = OUTLINE_DASH_LENGTH + OUTLINE_GAP_LENGTH;
        BasicStroke outlineStroke = outlineStroke();

        for (double dashStart = 0.0; dashStart < totalLength; dashStart += patternLength) {
            double dashEnd = Math.min(dashStart + OUTLINE_DASH_LENGTH, totalLength);
            if (dashEnd - dashStart <= 0.01) {
                continue;
            }

            Path2D dashPath = subPath(points, dashStart, dashEnd);
            Area dashArea = new Area(outlineStroke.createStrokedShape(dashPath));
            if (areaIntersects(dashArea, cutAboveArea)) {
                continue;
            }

            dashes.add(new OutlineDash(dashPath, dashArea));
        }

        return dashes;
    }

    private static Area createOutlineDashArea(List<OutlineDash> dashes) {
        Area outlineArea = new Area();
        for (OutlineDash dash : dashes) {
            outlineArea.add(dash.area());
        }
        return outlineArea;
    }

    private static List<PolyPoint> flattenPath(Path2D path, double flatness) {
        List<PolyPoint> points = new ArrayList<>();
        PathIterator iterator = path.getPathIterator(null, flatness);
        double[] coords = new double[6];

        double startX = 0.0;
        double startY = 0.0;
        double lastX = 0.0;
        double lastY = 0.0;
        double distance = 0.0;
        boolean hasCurrentPoint = false;

        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    startX = coords[0];
                    startY = coords[1];
                    lastX = startX;
                    lastY = startY;
                    hasCurrentPoint = true;
                    if (points.isEmpty()) {
                        points.add(new PolyPoint(lastX, lastY, distance));
                    }
                }
                case PathIterator.SEG_LINETO -> {
                    if (hasCurrentPoint) {
                        double segmentLength = Math.hypot(coords[0] - lastX, coords[1] - lastY);
                        if (segmentLength > 0.0) {
                            distance += segmentLength;
                            lastX = coords[0];
                            lastY = coords[1];
                            points.add(new PolyPoint(lastX, lastY, distance));
                        }
                    }
                }
                case PathIterator.SEG_CLOSE -> {
                    if (hasCurrentPoint) {
                        double segmentLength = Math.hypot(startX - lastX, startY - lastY);
                        if (segmentLength > 0.0) {
                            distance += segmentLength;
                            lastX = startX;
                            lastY = startY;
                            points.add(new PolyPoint(lastX, lastY, distance));
                        }
                    }
                }
                default -> throw new IllegalStateException("Unexpected flattened path segment: " + type);
            }
            iterator.next();
        }

        return points;
    }

    private static Path2D subPath(List<PolyPoint> points, double startDistance, double endDistance) {
        Point start = pointAtDistance(points, startDistance);
        Point end = pointAtDistance(points, endDistance);

        Path2D path = new Path2D.Double();
        path.moveTo(start.x(), start.y());

        for (int i = 1; i < points.size(); i++) {
            PolyPoint point = points.get(i);
            if (point.distance() > startDistance && point.distance() < endDistance) {
                path.lineTo(point.x(), point.y());
            }
        }

        path.lineTo(end.x(), end.y());
        return path;
    }

    private static Point pointAtDistance(List<PolyPoint> points, double distance) {
        if (distance <= 0.0) {
            PolyPoint first = points.get(0);
            return new Point(first.x(), first.y());
        }

        PolyPoint last = points.get(points.size() - 1);
        if (distance >= last.distance()) {
            return new Point(last.x(), last.y());
        }

        for (int i = 1; i < points.size(); i++) {
            PolyPoint previous = points.get(i - 1);
            PolyPoint next = points.get(i);
            if (distance <= next.distance()) {
                double segmentLength = next.distance() - previous.distance();
                double t = segmentLength == 0.0 ? 0.0 : (distance - previous.distance()) / segmentLength;
                double x = previous.x() + (next.x() - previous.x()) * t;
                double y = previous.y() + (next.y() - previous.y()) * t;
                return new Point(x, y);
            }
        }

        return new Point(last.x(), last.y());
    }

    private static boolean areaIntersects(Area a, Area b) {
        Area test = new Area(a);
        test.intersect(b);
        return !test.isEmpty();
    }

    private static boolean containsAll(Area container, Area candidate) {
        Area remainder = new Area(candidate);
        remainder.subtract(container);
        return remainder.isEmpty();
    }

    private static String areaToSvgPath(Area area) {
        return pathIteratorToSvgPath(area.getPathIterator(null, 0.25));
    }

    private static String pathToSvgPath(Path2D path) {
        return pathIteratorToSvgPath(path.getPathIterator(null, 0.25));
    }

    private static String pathIteratorToSvgPath(PathIterator iterator) {
        StringBuilder path = new StringBuilder();
        double[] coords = new double[6];

        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO ->
                        path.append("M ").append(number(coords[0])).append(' ').append(number(coords[1])).append(' ');
                case PathIterator.SEG_LINETO ->
                        path.append("L ").append(number(coords[0])).append(' ').append(number(coords[1])).append(' ');
                case PathIterator.SEG_QUADTO ->
                        path.append("Q ").append(number(coords[0])).append(' ').append(number(coords[1])).append(' ')
                                .append(number(coords[2])).append(' ').append(number(coords[3])).append(' ');
                case PathIterator.SEG_CUBICTO ->
                        path.append("C ").append(number(coords[0])).append(' ').append(number(coords[1])).append(' ')
                                .append(number(coords[2])).append(' ').append(number(coords[3])).append(' ')
                                .append(number(coords[4])).append(' ').append(number(coords[5])).append(' ');
                case PathIterator.SEG_CLOSE -> path.append("Z ");
                default -> throw new IllegalStateException("Unexpected path segment: " + type);
            }
            iterator.next();
        }

        return path.toString().trim();
    }

    private static BasicStroke outlineStroke() {
        return new BasicStroke((float) OUTLINE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private static Area createCutArea(double overlap) {
        return createTransformedRectArea(
                CUT_X - overlap,
                CUT_Y - overlap,
                CUT_WIDTH + overlap * 2.0,
                CUT_HEIGHT + overlap * 2.0
        );
    }

    private static Area createHexSeparationArea(double overlap) {
        return createTransformedRectArea(
                CUT_X - overlap,
                CUT_Y - overlap,
                CUT_WIDTH + overlap * 2.0,
                CUT_HEIGHT + HEX_SEPARATION_EXTRA_DEPTH + overlap * 2.0
        );
    }

    private static Area createTransformedRectArea(double x, double y, double width, double height) {
        double x1 = x;
        double y1 = y;
        double x2 = x + width;
        double y2 = y + height;

        Point p1 = transformCutPoint(x1, y1);
        Point p2 = transformCutPoint(x2, y1);
        Point p3 = transformCutPoint(x2, y2);
        Point p4 = transformCutPoint(x1, y2);

        Path2D path = new Path2D.Double();
        path.moveTo(p1.x(), p1.y());
        path.lineTo(p2.x(), p2.y());
        path.lineTo(p3.x(), p3.y());
        path.lineTo(p4.x(), p4.y());
        path.closePath();

        return new Area(path);
    }

    private static void appendCutRect(StringBuilder svg, String indent, double overlap) {
        line(svg, format(
                "%s<rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\" transform=\"translate(%s,%s) rotate(%s %s %s)\"/>",
                indent,
                number(CUT_X - overlap),
                number(CUT_Y - overlap),
                number(CUT_WIDTH + overlap * 2.0),
                number(CUT_HEIGHT + overlap * 2.0),
                number(CUT_TRANSLATE_X),
                number(CUT_TRANSLATE_Y),
                number(CUT_ROTATION_DEGREES),
                number(CUT_ROTATION_CENTER_X),
                number(CUT_ROTATION_CENTER_Y)
        ));
    }

    private static double spacingProgress(double x, double y) {
        double distance = HEX_SEPARATION_LINE.signedDistanceTo(x, y);

        if (distance <= 0.0) {
            return 0.0;
        }

        return clamp01(distance / SPACING_FADE_DISTANCE);
    }

    private static LineSegment createHexSeparationLine() {
        double separationY = CUT_Y + CUT_HEIGHT + HEX_SEPARATION_EXTRA_DEPTH;
        Point start = transformCutPoint(CUT_X + CUT_WIDTH, separationY);
        Point end = transformCutPoint(CUT_X, separationY);
        double length = Math.hypot(end.x() - start.x(), end.y() - start.y());
        return new LineSegment(start.x(), start.y(), end.x(), end.y(), length);
    }

    private static Point transformCutPoint(double x, double y) {
        double tx = x + CUT_TRANSLATE_X;
        double ty = y + CUT_TRANSLATE_Y;

        double dx = tx - CUT_ROTATION_CENTER_X;
        double dy = ty - CUT_ROTATION_CENTER_Y;

        double rx = dx * CUT_ROTATION_COS - dy * CUT_ROTATION_SIN + CUT_ROTATION_CENTER_X;
        double ry = dx * CUT_ROTATION_SIN + dy * CUT_ROTATION_COS + CUT_ROTATION_CENTER_Y;

        return new Point(rx, ry);
    }

    private static double smoothStep(double edge0, double edge1, double x) {
        if (edge0 == edge1) {
            return x < edge0 ? 0.0 : 1.0;
        }
        double t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static Path2D polygonPathFromSerializedPoints(String points) {
        // Clipping must use the same two-decimal vertices written to the SVG.
        Path2D path = new Path2D.Double();
        String[] coords = points.split(" ");
        for (int i = 0; i < coords.length; i++) {
            String[] xy = coords[i].split(",");
            double x = Double.parseDouble(xy[0]);
            double y = Double.parseDouble(xy[1]);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }

        path.closePath();
        return path;
    }

    private static AnnotationBounds annotationBounds(double viewboxSize) {
        double centerX = viewboxSize / 2.0;
        double centerY = viewboxSize / 2.0;
        double height = viewboxSize * ANNOTATION_HEIGHT_RATIO;
        double width = height * ANNOTATION_WIDTH_TO_HEIGHT;
        double left = centerX - width / 2.0;
        double top = centerY - height / 2.0;
        return new AnnotationBounds(centerX, centerY, left, top, width, height);
    }

    private static Path2D createAnnotationShape(AnnotationBounds bounds) {
        Path2D path = new Path2D.Double();

        path.moveTo(
                bounds.left() + 0.08 * bounds.width(),
                bounds.top() + 0.57 * bounds.height()
        );
        for (CubicCurve curve : ANNOTATION_CURVES) {
            appendCurve(path, bounds, curve);
        }

        path.closePath();
        return path;
    }

    private static void appendCurve(Path2D path, AnnotationBounds bounds, CubicCurve curve) {
        path.curveTo(
                bounds.left() + curve.x1() * bounds.width(),
                bounds.top() + curve.y1() * bounds.height(),
                bounds.left() + curve.x2() * bounds.width(),
                bounds.top() + curve.y2() * bounds.height(),
                bounds.left() + curve.x() * bounds.width(),
                bounds.top() + curve.y() * bounds.height()
        );
    }

    private static void appendParticles(StringBuilder svg) {
        line(svg, "  <g fill=\"#ffa000\" stroke=\"#ffc238\" stroke-width=\"1\" filter=\"url(#hexGlow)\">");
        for (ParticleSquare square : PARTICLE_SQUARES) {
            appendSquare(svg, square);
        }
        line(svg, "  </g>");

        line(svg, "  <g fill=\"#ffac12\" filter=\"url(#hexGlow)\">");
        for (ParticleDot dot : PARTICLE_DOTS) {
            appendDot(svg, dot);
        }
        line(svg, "  </g>");
    }

    private static void appendWordmark(StringBuilder svg, String color) {
        line(svg, "  <g font-family=\"Helvetica Neue, Arial, sans-serif\" font-size=\"136\" font-weight=\"1000\">");
        line(svg, format(
                "    <text x=\"44\" y=\"630\" textLength=\"456\" lengthAdjust=\"spacingAndGlyphs\" fill=\"%s\">Shape</text>",
                color
        ));
        line(svg, "    <path d=\"M 514 532 L 542 532 L 558 562 L 574 532 L 602 532 L 572 581 L 604 630 L 576 630 L 558 599 L 538 630 L 510 630 L 542 581 Z\" fill=\"url(#wordmarkOrangeGradient)\"/>");
        line(svg, "  </g>");
    }

    private static void appendTrail(StringBuilder svg, Trail trail) {
        double x = trail.x();
        double y1 = trail.y1();
        double y2 = trail.y2();
        double width = trail.width();
        double opacity = trail.opacity();
        double height = y2 - y1;
        double shoulderY = y1 + height * 0.94;
        double lowerControlY = y1 + height * 0.78;
        double upperControlY = y1 + height * 0.45;
        double halfWidth = width;
        double upperHalfWidth = width * 0.18;
        double coreHalfWidth = Math.max(0.25, width * 0.32);

        line(svg, format(
                "    <path d=\"M %s %s C %s %s %s %s %s %s L %s %s C %s %s %s %s %s %s Z\" fill=\"url(#trailNeedleGradient)\" opacity=\"%s\"/>",
                number(x), number(y1),
                number(x + upperHalfWidth), number(upperControlY),
                number(x + halfWidth), number(lowerControlY),
                number(x + halfWidth), number(shoulderY),
                number(x), number(y2),
                number(x - halfWidth), number(shoulderY),
                number(x - halfWidth), number(lowerControlY),
                number(x - upperHalfWidth), number(upperControlY),
                number(opacity)
        ));
        line(svg, format(
                "    <path d=\"M %s %s C %s %s %s %s %s %s L %s %s C %s %s %s %s %s %s Z\" fill=\"url(#trailCoreGradient)\" opacity=\"%s\"/>",
                number(x), number(y1 + height * 0.08),
                number(x + coreHalfWidth * 0.25), number(upperControlY),
                number(x + coreHalfWidth), number(lowerControlY),
                number(x + coreHalfWidth), number(shoulderY),
                number(x), number(y2),
                number(x - coreHalfWidth), number(shoulderY),
                number(x - coreHalfWidth), number(lowerControlY),
                number(x - coreHalfWidth * 0.25), number(upperControlY),
                number(Math.min(1.0, opacity + 0.16))
        ));
    }

    private static void appendHotHexagon(StringBuilder svg, HotHexagon hexagon) {
        appendHexagon(
                svg,
                hexagon.centerX(),
                hexagon.centerY(),
                hexagon.sideLength(),
                format(" fill=\"url(#hotHexGradient)\" opacity=\"%s\"", number(hexagon.opacity()))
        );
    }

    private static void appendHexagon(StringBuilder svg, double centerX, double centerY, double sideLength, String attributes) {
        line(svg, format(
                "      <polygon points=\"%s\"%s/>",
                regularPolygonPoints(centerX, centerY, sideLength, 6, 0.0),
                attributes
        ));
    }

    private static void appendSquare(StringBuilder svg, ParticleSquare square) {
        line(svg, format(
                "    <rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\" opacity=\"%s\"/>",
                number(square.x()),
                number(square.y()),
                number(square.size()),
                number(square.size()),
                number(square.opacity())
        ));
    }

    private static void appendDot(StringBuilder svg, ParticleDot dot) {
        line(svg, format(
                "    <circle cx=\"%s\" cy=\"%s\" r=\"%s\" opacity=\"%s\"/>",
                number(dot.centerX()),
                number(dot.centerY()),
                number(dot.radius()),
                number(dot.opacity())
        ));
    }

    private static String annotationPath(AnnotationBounds bounds) {
        StringBuilder path = new StringBuilder(640);

        path.append(format("M %s %s ",
                number(bounds.left() + 0.08 * bounds.width()),
                number(bounds.top() + 0.57 * bounds.height())));
        for (CubicCurve curve : ANNOTATION_CURVES) {
            appendCurve(path, bounds, curve);
        }

        path.append("Z");

        return path.toString();
    }

    private static void appendCurve(StringBuilder path, AnnotationBounds bounds, CubicCurve curve) {
        path.append(format(
                "C %s %s %s %s %s %s ",
                number(bounds.left() + curve.x1() * bounds.width()),
                number(bounds.top() + curve.y1() * bounds.height()),
                number(bounds.left() + curve.x2() * bounds.width()),
                number(bounds.top() + curve.y2() * bounds.height()),
                number(bounds.left() + curve.x() * bounds.width()),
                number(bounds.top() + curve.y() * bounds.height())
        ));
    }

    private static String regularPolygonPoints(double centerX, double centerY, double sideLength, int sides, double angleOffsetDegrees) {
        double radius = sideLength / (2.0 * Math.sin(Math.PI / sides));
        StringBuilder points = new StringBuilder();

        for (int i = 0; i < sides; i++) {
            if (i > 0) {
                points.append(' ');
            }
            double angle = Math.toRadians(angleOffsetDegrees + 360.0 * i / sides);
            double x = centerX + radius * Math.cos(angle);
            double y = centerY + radius * Math.sin(angle);
            points.append(number(x)).append(',').append(number(y));
        }

        return points.toString();
    }

    private static void line(StringBuilder svg, String text) {
        svg.append(text).append('\n');
    }

    private static String format(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }

    private static String number(double value) {
        String formatted = format("%.2f", value);
        int end = formatted.length();
        while (end > 0 && formatted.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && formatted.charAt(end - 1) == '.') {
            end--;
        }
        return formatted.substring(0, end);
    }
}
